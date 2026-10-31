package com.trackr.receiver

import com.trackr.data.repository.IScheduledReminderRepository
import org.koin.core.context.GlobalContext
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.domain.model.ScheduledReminder
import com.trackr.util.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        // 未配置自动跟车
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
        assertFalse(ReminderScheduler.canScheduleExact(app))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(ReminderScheduler.canScheduleExact(app))
    }

    @Test
    fun testTryStartTracking_whenServiceStartNotAllowed_returnsFalse() {
        val reminder = autoStartReminder("auto-2")
        // 模拟 Android 12+ ForegroundServiceStartNotAllowedException（IllegalStateException 子类）
        val started = receiver.tryStartTracking(app, reminder) { _, _ ->
            throw IllegalStateException("startForegroundService() not allowed")
        }
        assertFalse(started)
    }

    @Test
    fun testTryStartTracking_whenStarterSucceeds_returnsTrue() {
        val reminder = autoStartReminder("auto-3")
        var invoked = false
        val started = receiver.tryStartTracking(app, reminder) { _, _ -> invoked = true }
        assertTrue(started)
        assertTrue(invoked)
    }
}
