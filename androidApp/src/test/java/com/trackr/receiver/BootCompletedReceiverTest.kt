package com.trackr.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.trackr.TrackrApp
import com.trackr.core.model.ScheduledReminder
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.util.ReminderScheduleState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BootCompletedReceiverTest {

    private lateinit var app: TrackrApp

    /** App 启动时 Koin 构建的真实仓库（原来是 app.reminderRepository） */
    private val reminderRepository: IScheduledReminderRepository
        get() = GlobalContext.get().get()
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

    /** App 启动时选定的计划触发时刻存储（第 9 步起默认是 Room 里的 reminder.lastScheduledTriggerAt） */
    private val scheduleState: ReminderScheduleState
        get() = GlobalContext.get().get()

    /** 模拟关机期间错过：把计划触发时间改写为过去 */
    private fun markPlannedInPast(id: String) {
        scheduleState.record(id, System.currentTimeMillis() - 60_000L)
    }

    private fun waitUntil(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    @Test
    fun testBootCompleted_disablesExpiredOneTimeReminder() = runBlocking {
        reminderRepository.saveReminder(reminder("boot-once", emptySet()))
        reminderRepository.saveReminder(reminder("boot-repeat", setOf(1, 2, 3, 4, 5, 6, 7)))
        markPlannedInPast("boot-once")
        markPlannedInPast("boot-repeat")

        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        waitUntil { reminderRepository.getReminderById("boot-once")?.enabled == false }
        assertFalse(reminderRepository.getReminderById("boot-once")!!.enabled)
        // 周期提醒不受影响，且被重新调度到未来
        assertTrue(reminderRepository.getReminderById("boot-repeat")!!.enabled)
        val repeatPlanned = scheduleState.get("boot-repeat")
        assertTrue(repeatPlanned!! > System.currentTimeMillis())
    }

    @Test
    fun testTimeChanged_reschedulesWithoutDisabling() = runBlocking {
        reminderRepository.saveReminder(reminder("time-once", emptySet()))
        markPlannedInPast("time-once")

        receiver.onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))

        // 时间变化只重新计算触发时间，不置为未启用
        assertTrue(reminderRepository.getReminderById("time-once")!!.enabled)
        val planned = scheduleState.get("time-once")
        assertTrue(planned!! > System.currentTimeMillis())
    }

    @Test
    fun testUnrelatedAction_isIgnored() = runBlocking {
        reminderRepository.saveReminder(reminder("ignored-once", emptySet()))
        markPlannedInPast("ignored-once")

        receiver.onReceive(app, Intent("com.trackr.action.UNRELATED"))

        assertTrue(reminderRepository.getReminderById("ignored-once")!!.enabled)
        // 未处理的广播不会重新调度，计划时间仍是之前写入的过去时间
        val planned = scheduleState.get("ignored-once")
        assertTrue(planned!! < System.currentTimeMillis())
    }
}
