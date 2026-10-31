package com.trackr.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.domain.model.ScheduledReminder
import com.trackr.util.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootCompletedReceiverTest {

    private lateinit var app: TrackrApp
    private lateinit var receiver: BootCompletedReceiver

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        receiver = BootCompletedReceiver()
    }

    private fun reminder(id: String, daysOfWeek: Set<Int>) = ScheduledReminder(
        id = id,
        enabled = true,
        hour = 8,
        minute = 0,
        daysOfWeek = daysOfWeek,
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

    /** 模拟关机期间错过：把计划触发时间改写为过去 */
    private fun markPlannedInPast(id: String) {
        app.getSharedPreferences("trackr_reminder_schedule_state", Context.MODE_PRIVATE)
            .edit()
            .putLong("next_trigger_$id", System.currentTimeMillis() - 60_000L)
            .commit()
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun testBootCompleted_disablesExpiredOneTimeReminder() = runBlocking {
        app.reminderRepository.saveReminder(reminder("boot-once", emptySet()))
        app.reminderRepository.saveReminder(reminder("boot-repeat", setOf(1, 2, 3, 4, 5, 6, 7)))
        markPlannedInPast("boot-once")
        markPlannedInPast("boot-repeat")

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        waitUntil { app.reminderRepository.getReminderById("boot-once")?.enabled == false }
        assertFalse(app.reminderRepository.getReminderById("boot-once")!!.enabled)
        // 周期提醒不受影响，且被重新调度到未来
        assertTrue(app.reminderRepository.getReminderById("boot-repeat")!!.enabled)
        val repeatPlanned = ReminderScheduler.getLastScheduledTriggerMillis(app, "boot-repeat")
        assertTrue(repeatPlanned!! > System.currentTimeMillis())
    }

    @Test
    fun testTimeChanged_reschedulesWithoutDisabling() = runBlocking {
        app.reminderRepository.saveReminder(reminder("time-once", emptySet()))
        markPlannedInPast("time-once")

        receiver.onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))

        // 时间变化只重新计算触发时间，不置为未启用
        assertTrue(app.reminderRepository.getReminderById("time-once")!!.enabled)
        val planned = ReminderScheduler.getLastScheduledTriggerMillis(app, "time-once")
        assertTrue(planned!! > System.currentTimeMillis())
    }

    @Test
    fun testUnrelatedAction_isIgnored() = runBlocking {
        app.reminderRepository.saveReminder(reminder("ignored-once", emptySet()))
        markPlannedInPast("ignored-once")

        receiver.onReceive(app, Intent("com.trackr.action.UNRELATED"))

        assertTrue(app.reminderRepository.getReminderById("ignored-once")!!.enabled)
        // 未处理的广播不会重新调度，计划时间仍是之前写入的过去时间
        val planned = ReminderScheduler.getLastScheduledTriggerMillis(app, "ignored-once")
        assertTrue(planned!! < System.currentTimeMillis())
    }
}
