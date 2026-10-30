package com.trackr.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import com.trackr.domain.model.ScheduledReminder
import com.trackr.receiver.ScheduledReminderReceiver
import java.util.Calendar

/**
 * 定时提醒硬件闹钟调度器 (AlarmManager)
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    /**
     * 计算下一个准确触发的时间戳 (毫秒)
     *
     * @param hour 目标小时 (0..23)
     * @param minute 目标分钟 (0..59)
     * @param daysOfWeek 目标星期集合 (1=周一 .. 7=周日)；若为空表示仅响一次
     * @param nowMillis 当前时间毫秒戳，方便单元测试注入
     */
    fun calculateNextTriggerMillis(
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val nowCal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
        }

        if (daysOfWeek.isEmpty()) {
            // 仅一次模式
            val targetCal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (targetCal.timeInMillis <= nowMillis) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return targetCal.timeInMillis
        }

        // 周期重复模式 (1=周一 .. 7=周日)
        // Calendar 中的 DAY_OF_WEEK: SUNDAY=1, MONDAY=2, TUESDAY=3, WEDNESDAY=4, THURSDAY=5, FRIDAY=6, SATURDAY=7
        // 我们将其转换为 ISO-8601 标准：周一=1, 周日=7
        for (dayOffset in 0..7) {
            val candidateCal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (candidateCal.timeInMillis > nowMillis) {
                val isoDayOfWeek = when (candidateCal.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.SUNDAY -> 7
                    Calendar.MONDAY -> 1
                    Calendar.TUESDAY -> 2
                    Calendar.WEDNESDAY -> 3
                    Calendar.THURSDAY -> 4
                    Calendar.FRIDAY -> 5
                    Calendar.SATURDAY -> 6
                    else -> 1
                }
                if (daysOfWeek.contains(isoDayOfWeek)) {
                    return candidateCal.timeInMillis
                }
            }
        }

        // 降级保护：若未匹配则默认明天相同时间
        return Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 注册或更新单个提醒的系统闹钟
     */
    fun scheduleReminder(context: Context, reminder: ScheduledReminder) {
        if (!reminder.enabled) {
            cancelReminder(context, reminder.id)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val nextTriggerMillis = calculateNextTriggerMillis(
            hour = reminder.hour,
            minute = reminder.minute,
            daysOfWeek = reminder.daysOfWeek
        )

        val pendingIntent = createPendingIntent(context, reminder.id)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    AlarmManagerCompat.setExactAndAllowWhileIdle(
                        alarmManager,
                        AlarmManager.RTC_WAKEUP,
                        nextTriggerMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextTriggerMillis,
                        pendingIntent
                    )
                }
            } else {
                AlarmManagerCompat.setExactAndAllowWhileIdle(
                    alarmManager,
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerMillis,
                    pendingIntent
                )
            }
            Log.d(TAG, "Scheduled reminder ${reminder.id} at $nextTriggerMillis")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot schedule exact alarm, falling back to standard alarm", e)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextTriggerMillis,
                pendingIntent
            )
        }
    }

    /**
     * 取消指定提醒的系统闹钟
     */
    fun cancelReminder(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = createPendingIntent(context, reminderId)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
        Log.d(TAG, "Cancelled reminder $reminderId")
    }

    /**
     * 重新调度所有启用的提醒
     */
    fun rescheduleAll(context: Context, reminders: List<ScheduledReminder>) {
        reminders.forEach { reminder ->
            if (reminder.enabled) {
                scheduleReminder(context, reminder)
            } else {
                cancelReminder(context, reminder.id)
            }
        }
    }

    private fun createPendingIntent(context: Context, reminderId: String): PendingIntent {
        val intent = Intent(context, ScheduledReminderReceiver::class.java).apply {
            action = ScheduledReminderReceiver.ACTION_TRIGGER_REMINDER
            putExtra(ScheduledReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            flags
        )
    }
}
