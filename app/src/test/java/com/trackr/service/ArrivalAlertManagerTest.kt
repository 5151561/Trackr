package com.trackr.service

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.domain.model.TrackingSessionState
import org.junit.Assert.assertEquals
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

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        notificationManager = app.getSystemService(Application.NOTIFICATION_SERVICE) as NotificationManager
        app.repository.setLiveNotificationEnabled(true)
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
        app.repository.setLiveNotificationEnabled(false)

        ArrivalAlertManager.triggerApproachingAlert(app, activeTrackingState(stopsRemaining = 2))

        val posted = shadowOf(notificationManager).getNotification(ArrivalAlertManager.NOTIFICATION_ID_APPROACHING)
        val text = posted?.extras?.getCharSequence("android.text")?.toString().orEmpty()
        assertEquals(true, text.contains("还有 2 站"))
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
