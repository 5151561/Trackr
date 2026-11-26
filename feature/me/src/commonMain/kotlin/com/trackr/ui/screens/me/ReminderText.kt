package com.trackr.ui.screens.me

import com.trackr.core.model.ScheduledReminder

/*
 * 定时提醒在「我的」里显示的短文字（v3：「工作日 07:50 · 325」）。
 */

private val dayNames = mapOf(1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日")

/** 重复周期的短写法：仅一次 / 工作日 / 每天 / 周末 / 周一、三 */
internal fun shortRepeatText(daysOfWeek: Set<Int>): String = when {
    daysOfWeek.isEmpty() -> "仅一次"
    daysOfWeek == setOf(1, 2, 3, 4, 5) -> "工作日"
    daysOfWeek == setOf(1, 2, 3, 4, 5, 6, 7) -> "每天"
    daysOfWeek == setOf(6, 7) -> "周末"
    else -> "周" + daysOfWeek.sorted().mapNotNull { dayNames[it] }.joinToString("、")
}

/** 「工作日 07:50 · 325」 */
internal fun reminderSummary(reminder: ScheduledReminder): String =
    "${shortRepeatText(reminder.daysOfWeek)} ${reminder.formatTime()} · ${reminder.lineName}"

/** 「定时提醒」那一行的副标题：最早一条开着的提醒；都关着时说一声；没有提醒为 null */
internal fun remindersRowSubtitle(reminders: List<ScheduledReminder>): String? {
    if (reminders.isEmpty()) return null
    val first = reminders.filter { it.enabled }.minByOrNull { it.hour * 60 + it.minute }
        ?: return "${reminders.size} 个都关着"
    return reminderSummary(first)
}
