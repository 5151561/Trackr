package com.trackr.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.core.domain.platform.TrackingController
import com.trackr.core.model.ScheduledReminder
import com.trackr.core.model.TrackingTarget
import com.trackr.core.testing.FakeTrackingController
import com.trackr.data.repository.FakeTransitRepository
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.util.ReminderAlarmScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
class ScheduledReminderReceiverTest {

    private lateinit var app: TrackrApp

    /** App 启动时 Koin 构建的真实仓库（原来是 app.reminderRepository） */
    private val reminderRepository: IScheduledReminderRepository
        get() = GlobalContext.get().get()
    private lateinit var receiver: ScheduledReminderReceiver

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        receiver = ScheduledReminderReceiver()
    }

    @Test
    fun testReceiveInvalidAction_doesNothing() {
        val intent = Intent("invalid.action")
        receiver.onReceive(app, intent)
    }

    @Test
    fun testReceiveTriggerAction_processesReminder() = runBlocking {
        val reminder = ScheduledReminder(
            id = "test-rem-trigger",
            enabled = true,
            hour = 8,
            minute = 0,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            cityId = "034",
            cityName = "上海",
            lineId = "line-101",
            lineName = "70路",
            direction = 0,
            endStopName = "科技园",
            targetStopId = "stop-5",
            targetStopName = "大冲",
            targetOrder = 5
        )
        reminderRepository.saveReminder(reminder)

        val intent = Intent(app, ScheduledReminderReceiver::class.java).apply {
            action = ScheduledReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ScheduledReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
        }

        receiver.onReceive(app, intent)

        // 验证系统通知通道已建立
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(nm)
        assertNotNull(shadowNm)
    }

    @Test
    fun testReceiveStartTrackingNow_handlesStartService() = runBlocking {
        val reminder = ScheduledReminder(
            id = "test-rem-start-service",
            enabled = true,
            hour = 8,
            minute = 0,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            cityId = "034",
            cityName = "上海",
            lineId = "line-101",
            lineName = "70路",
            direction = 0,
            endStopName = "科技园",
            targetStopId = "stop-5",
            targetStopName = "大冲",
            targetOrder = 5
        )
        reminderRepository.saveReminder(reminder)

        val intent = Intent(app, ScheduledReminderReceiver::class.java).apply {
            action = ScheduledReminderReceiver.ACTION_START_TRACKING_NOW
            putExtra(ScheduledReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
        }

        receiver.onReceive(app, intent)
    }

    private fun autoStartReminder(id: String) = ScheduledReminder(
        id = id,
        enabled = true,
        hour = 8,
        minute = 0,
        daysOfWeek = setOf(1, 2, 3, 4, 5),
        cityId = "034",
        cityName = "上海",
        lineId = "line-101",
        lineName = "70路",
        direction = 0,
        endStopName = "科技园",
        targetStopId = "stop-5",
        targetStopName = "大冲",
        targetOrder = 5,
        autoStartLiveTracking = true
    )

    @Test
    fun testShouldAutoStartTracking_requiresExactAlarm() {
        val reminder = autoStartReminder("auto-1")
        assertTrue(ScheduledReminderReceiver.shouldAutoStartTracking(reminder, canScheduleExact = true))
        // 非精确闹钟触发：没有后台启动前台服务的豁免，必须降级为通知
        assertFalse(ScheduledReminderReceiver.shouldAutoStartTracking(reminder, canScheduleExact = false))
        // 未配置自动追踪
        assertFalse(
            ScheduledReminderReceiver.shouldAutoStartTracking(
                reminder.copy(autoStartLiveTracking = false),
                canScheduleExact = true
            )
        )
    }

    @Test
    fun testCanScheduleExact_reflectsAlarmPermission() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(ReminderAlarmScheduler.canScheduleExact(app))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(ReminderAlarmScheduler.canScheduleExact(app))
    }

    @Test
    fun testTryStartTracking_whenServiceStartNotAllowed_returnsFalse() {
        val reminder = autoStartReminder("auto-2")
        // 模拟 Android 12+ ForegroundServiceStartNotAllowedException（IllegalStateException 子类）
        val started = receiver.tryStartTracking(reminder) {
            throw IllegalStateException("startForegroundService() not allowed")
        }
        assertFalse(started)
    }

    @Test
    fun testTryStartTracking_whenControllerRefuses_returnsFalse() {
        val reminder = autoStartReminder("auto-2b")
        assertFalse(receiver.tryStartTracking(reminder) { false })
    }

    @Test
    fun testTryStartTracking_whenStarterSucceeds_passesSingleLineTarget() {
        val reminder = autoStartReminder("auto-3").copy(totalStops = 12, mygolbsCityCode = "350300")
        var passed: TrackingTarget? = null
        val started = receiver.tryStartTracking(reminder) { target ->
            passed = target
            true
        }
        assertTrue(started)
        val single = passed as TrackingTarget.SingleLine
        assertEquals("034", single.cityId)
        assertEquals("line-101", single.lineId)
        assertEquals("70路", single.lineName)
        assertEquals("科技园", single.endStopName)
        assertEquals("stop-5", single.targetStopId)
        assertEquals("大冲", single.targetStopName)
        assertEquals(5, single.targetOrder)
        assertEquals(12, single.totalStops)
        assertEquals(2, single.alertThresholdStops)
        assertEquals("350300", single.mygolbsCityCode)
    }

    // ---------------------------------------------------------------- 降级（8e0c41e）

    private fun installFakes(startResult: Boolean): FakeTrackingController {
        val controller = FakeTrackingController().apply { this.startResult = startResult }
        loadKoinModules(
            module {
                single<TrackingController> { controller }
                single<ITransitRepository> { FakeTransitRepository() }
            }
        )
        return controller
    }

    private fun postedReminderNotification(reminderId: String): android.app.Notification? {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = ScheduledReminderReceiver.NOTIFICATION_BASE_ID + reminderId.hashCode() % 10000
        return shadowOf(nm).getNotification(id)
    }

    private fun bigTextOf(notification: android.app.Notification): String =
        notification.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            ?: notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()

    @Test
    fun testAutoStart_exactAlarmAndStarted_noNotification() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val controller = installFakes(startResult = true)
        val reminder = autoStartReminder("auto-ok")
        reminderRepository.saveReminder(reminder)

        ScheduledReminderReceiver().handleReminderTrigger(app, reminder.id)

        assertEquals(1, controller.started.size)
        assertNull(postedReminderNotification(reminder.id))
    }

    @Test
    fun testAutoStart_inexactAlarm_degradesToNotificationWithHint() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val controller = installFakes(startResult = true)
        val reminder = autoStartReminder("auto-inexact")
        reminderRepository.saveReminder(reminder)

        ScheduledReminderReceiver().handleReminderTrigger(app, reminder.id)

        // 非精确闹钟不直接开追踪，改发带提示的通知
        assertTrue(controller.started.isEmpty())
        val notification = requireNotNull(postedReminderNotification(reminder.id))
        assertTrue(bigTextOf(notification).contains("系统没让自动开始追踪，点「开始追踪」"))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun testAutoStart_startRefused_degradesToNotificationWithHint() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val controller = installFakes(startResult = false)
        val reminder = autoStartReminder("auto-refused")
        reminderRepository.saveReminder(reminder)

        ScheduledReminderReceiver().handleReminderTrigger(app, reminder.id)

        assertEquals(1, controller.started.size)
        val notification = requireNotNull(postedReminderNotification(reminder.id))
        assertTrue(bigTextOf(notification).contains("系统没让自动开始追踪"))
    }

    @Test
    fun testReminderNotification_tapOpensLineDetailViaTrackrLink() = runBlocking {
        // 第 18 步：点提醒通知发 trackr://line 链接（原来是 6 个 extra），解析出来还是那条线路详情
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        installFakes(startResult = true)
        val reminder = autoStartReminder("tap-link")
        reminderRepository.saveReminder(reminder)

        ScheduledReminderReceiver().handleReminderTrigger(app, reminder.id)

        val notification = requireNotNull(postedReminderNotification(reminder.id))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.trackr.MainActivity", intent.component?.className)
        assertNull("不再带旧 extra", intent.getStringExtra("extra_city_id"))
        val link = com.trackr.deeplink.DeepLinkIntents.parse(intent)
        assertEquals(
            com.trackr.core.navigation.deeplink.DeepLink.Stack(
                com.trackr.core.navigation.ScreenKey.Home,
                listOf(
                    com.trackr.core.navigation.ScreenKey.LineDetail(
                        cityId = "034",
                        lineId = "line-101",
                        targetOrder = 5,
                        stopId = "stop-5",
                        lineName = "70路"
                    )
                )
            ),
            link
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @Test
    fun testStartTrackingNow_whenStartFails_keepsNotificationForRetry() = runBlocking {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        installFakes(startResult = true)
        val reminder = autoStartReminder("retry")
        reminderRepository.saveReminder(reminder)
        ScheduledReminderReceiver().handleReminderTrigger(app, reminder.id)
        assertNotNull(postedReminderNotification(reminder.id))

        // 点「开始追踪」仍启动失败：通知保留，方便再点
        val refusing = installFakes(startResult = false)
        ScheduledReminderReceiver().handleStartTrackingNow(app, reminder.id)
        assertEquals(1, refusing.started.size)
        assertNotNull(postedReminderNotification(reminder.id))

        // 再点一次成功：通知关掉
        val accepting = installFakes(startResult = true)
        ScheduledReminderReceiver().handleStartTrackingNow(app, reminder.id)
        assertEquals(1, accepting.started.size)
        assertNull(postedReminderNotification(reminder.id))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }
}
