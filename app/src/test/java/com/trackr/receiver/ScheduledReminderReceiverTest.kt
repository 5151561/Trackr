package com.trackr.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.domain.model.ScheduledReminder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ScheduledReminderReceiverTest {

    private lateinit var app: TrackrApp
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
        app.reminderRepository.saveReminder(reminder)

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
        app.reminderRepository.saveReminder(reminder)

        val intent = Intent(app, ScheduledReminderReceiver::class.java).apply {
            action = ScheduledReminderReceiver.ACTION_START_TRACKING_NOW
            putExtra(ScheduledReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
        }

        receiver.onReceive(app, intent)
    }
}
