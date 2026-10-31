package com.trackr.service

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.domain.model.TrackingSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ArrivalAlertManagerTest {

    private lateinit var app: TrackrApp
    private lateinit var notificationManager: NotificationManager
    private var liveEnabled = true

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        notificationManager = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager
        liveEnabled = true
        ArrivalAlertManager.liveNotificationEnabledProvider = { liveEnabled }
        ArrivalAlertManager.liveUpdateActiveChecker = { true }
    }

    @After
    fun tearDown() {
        ArrivalAlertManager.resetLiveUpdateActiveChecker()
        ArrivalAlertManager.resetLiveNotificationEnabledProvider()
    }

    @Test
    fun testTriggerApproachingAlert_skipsStandaloneNotificationWhenLiveUpdateIsActive() {
        val state = activeTrackingState(stopsRemaining = 2)

        ArrivalAlertManager.triggerApproachingAlert(app, state)

        assertNull(
            "实时活动已展示同一趟车时不应再发一条内容重复的强提醒",
            shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_APPROACHING)
        )
    }

    @Test
    fun testTriggerArrivedAlert_skipsStandaloneNotificationWhenLiveUpdateIsActive() {
        val state = activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true)

        ArrivalAlertManager.triggerArrivedAlert(app, state)

        assertNull(
            shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_ARRIVED)
        )
    }

    @Test
    fun testTriggerArrivedAlert_clearsStaleApproachingNotification() {
        ArrivalAlertManager.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))
        ArrivalAlertManager.triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        assertNull(shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_APPROACHING))
    }

    @Test
    fun testTriggerApproachingAlert_postsNotificationWhenLiveUpdateDisabled() {
        liveEnabled = false

        ArrivalAlertManager.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_APPROACHING)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals(true, text.contains("还有 2 站"))
    }

    @Test
    fun testTriggerApproachingAlert_postsNotificationWhenSystemDoesNotSupportPromotion() {
        ArrivalAlertManager.liveUpdateActiveChecker = { false }

        ArrivalAlertManager.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_APPROACHING)
        assertNotNull("当系统不支持实时活动胶囊时，应降级发送独立强提醒", posted)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals(true, text.contains("还有 2 站"))
    }

    @Test
    fun testStandaloneAlerts_useReminderCategory() {
        liveEnabled = false

        ArrivalAlertManager.triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_ARRIVED)
        assertNotNull(posted)
        assertEquals(android.app.Notification.CATEGORY_REMINDER, posted?.category)
    }

    @Test
    fun testTransferAlert_cancelsArrivedAndApproachingOfSameStop() {
        liveEnabled = false
        ArrivalAlertManager.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 1))
        ArrivalAlertManager.triggerArrivedAlert(app, activeTrackingState(stopsRemaining = 0).copy(isBusArrivedAtStop = true))

        ArrivalAlertManager.triggerTransferAlert(app, "珞雄路雄楚大道", "M492")

        assertNull(shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_ARRIVED))
        assertNull(shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_APPROACHING))
        assertNotNull(shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_TRANSFER))
    }

    @Test
    fun testLiveNotificationFallback_isSameForBothHelpers() {
        ArrivalAlertManager.resetLiveNotificationEnabledProvider()
        LiveProgressNotificationHelper.resetLiveNotificationEnabledProvider()
        assertEquals(
            LiveProgressNotificationHelper.liveNotificationEnabledProvider(),
            ArrivalAlertManager.liveNotificationEnabledProvider()
        )
    }

    private fun activeTrackingState(stopsRemaining: Int) = TrackingSessionState(
        isActive = true,
        lineName = "788",
        endStopName = "珞雄路公交场站",
        targetStopName = "珞雄路雄楚大道",
        targetOrder = 11,
        startTrackingOrder = 10,
        currentBusOrder = 11,
        currentBusProgress = 0.4f,
        stopsRemaining = stopsRemaining,
        etaSeconds = 420
    )
}
