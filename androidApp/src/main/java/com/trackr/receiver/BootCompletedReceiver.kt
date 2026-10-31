package com.trackr.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.di.AppScopeQualifier
import com.trackr.util.ReminderAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 系统闹钟恢复广播接收器
 * 负责在开机、应用更新、系统时间/时区变化、精确闹钟权限状态变化后重新调度定时提醒
 */
class BootCompletedReceiver : BroadcastReceiver(), KoinComponent {

    private val reminderRepository: IScheduledReminderRepository by inject()
    private val reminderScheduler: ReminderAlarmScheduler by inject()
    private val appScope: CoroutineScope by inject(AppScopeQualifier)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == null || action !in HANDLED_ACTIONS) {
            return
        }

        Log.i(TAG, "Received $action, rescheduling reminders...")
        val reminders = reminderRepository.reminders.value

        // 开机/更新后：关机期间已错过的一次性提醒不再推到第二天，而是置为未启用
        val expiredIds = if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderAlarmScheduler.findExpiredOneTimeReminderIds(
                reminders = reminders,
                nowMillis = System.currentTimeMillis()
            ) { id -> reminderScheduler.getLastScheduledTriggerMillis(id) }
        } else {
            emptySet()
        }

        reminderScheduler.rescheduleAll(reminders.filterNot { it.id in expiredIds })

        if (expiredIds.isNotEmpty()) {
            Log.i(TAG, "Disabling ${expiredIds.size} expired one-time reminder(s)")
            val pendingResult = goAsync()
            // 应用级作用域（原来每次新建 CoroutineScope），仍在 IO 线程执行
            appScope.launch(Dispatchers.IO) {
                try {
                    expiredIds.forEach { id ->
                        reminderRepository.toggleReminder(id, false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disable expired reminders", e)
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"

        /** 对应 AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED（API 31+），用字面量避免低版本 InlinedApi 告警 */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED
        )
    }
}
