package com.trackr.util

import com.trackr.domain.model.ScheduledReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ReminderSchedulerTest {

    @Test
    fun testOneTimeReminder_beforeCurrentTime_triggersNextDay() {
        // 构造固定时间：2026-10-30 10:00:00
        val baseCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = baseCal.timeInMillis

        // 设为 08:00 单次（已过）
        val triggerMillis = ReminderScheduler.calculateNextTriggerMillis(
            hour = 8,
            minute = 0,
            daysOfWeek = emptySet(),
            nowMillis = nowMillis
        )

        val triggerCal = Calendar.getInstance().apply {
            timeInMillis = triggerMillis
        }

        assertTrue(triggerMillis > nowMillis)
        assertEquals(2026, triggerCal.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, triggerCal.get(Calendar.MONTH))
        assertEquals(31, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testOneTimeReminder_afterCurrentTime_triggersSameDay() {
        // 构造固定时间：2026-10-30 07:00:00
        val baseCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 7, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMillis = baseCal.timeInMillis

        // 设为 08:30 单次（今天还未到）
        val triggerMillis = ReminderScheduler.calculateNextTriggerMillis(
            hour = 8,
            minute = 30,
            daysOfWeek = emptySet(),
            nowMillis = nowMillis
        )

        val triggerCal = Calendar.getInstance().apply {
            timeInMillis = triggerMillis
        }

        assertTrue(triggerMillis > nowMillis)
        assertEquals(30, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testWorkdayRepeatingReminder_onFridayAfterTime_triggersMonday() {
        // 2026-10-30 是星期五。设当前时间为周五 09:00:00
        val fridayCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 30, 9, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(Calendar.FRIDAY, fridayCal.get(Calendar.DAY_OF_WEEK))

        // 设为 08:00 工作日（周一至周五：1, 2, 3, 4, 5）
        val triggerMillis = ReminderScheduler.calculateNextTriggerMillis(
            hour = 8,
            minute = 0,
            daysOfWeek = setOf(1, 2, 3, 4, 5),
            nowMillis = fridayCal.timeInMillis
        )

        val triggerCal = Calendar.getInstance().apply {
            timeInMillis = triggerMillis
        }

        assertTrue(triggerMillis > fridayCal.timeInMillis)
        // 周五已过 8 点，下一个工作日是下周一 (2026-11-02)
        assertEquals(Calendar.MONDAY, triggerCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(2, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.NOVEMBER, triggerCal.get(Calendar.MONTH))
        assertEquals(8, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testWeekendRepeatingReminder_onMonday_triggersSaturday() {
        // 2026-10-26 是星期一 08:00:00
        val mondayCal = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 26, 8, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals(Calendar.MONDAY, mondayCal.get(Calendar.DAY_OF_WEEK))

        // 设定为周末（6=周六, 7=周日）09:30
        val triggerMillis = ReminderScheduler.calculateNextTriggerMillis(
            hour = 9,
            minute = 30,
            daysOfWeek = setOf(6, 7),
            nowMillis = mondayCal.timeInMillis
        )

        val triggerCal = Calendar.getInstance().apply {
            timeInMillis = triggerMillis
        }

        assertEquals(Calendar.SATURDAY, triggerCal.get(Calendar.DAY_OF_WEEK))
        assertEquals(31, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
    }

    private fun reminder(id: String, daysOfWeek: Set<Int>, enabled: Boolean = true) = ScheduledReminder(
        id = id,
        enabled = enabled,
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

    @Test
    fun testFindExpiredOneTimeReminderIds_onlyPastOneTimeWithRecord() {
        val now = 1_000_000L
        val reminders = listOf(
            reminder("one-expired", emptySet()),
            reminder("one-future", emptySet()),
            reminder("one-no-record", emptySet()),
            reminder("one-disabled", emptySet(), enabled = false),
            reminder("repeat-expired", setOf(1, 2, 3, 4, 5))
        )
        val planned = mapOf(
            "one-expired" to now - 60_000L,
            "one-future" to now + 60_000L,
            "one-disabled" to now - 60_000L,
            "repeat-expired" to now - 60_000L
        )

        val expired = ReminderScheduler.findExpiredOneTimeReminderIds(reminders, now) { planned[it] }

        // 只有「已启用 + 一次性 + 有计划记录且已过期」的提醒会被判定过期
        assertEquals(setOf("one-expired"), expired)
    }
}
