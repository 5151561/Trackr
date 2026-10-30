package com.trackr.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.trackr.TrackrApp
import com.trackr.util.ReminderScheduler

/**
 * 手机开机自启或应用更新广播接收器
 * 负责恢复系统中由于重启丢失的精确闹钟
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "Device booted or package replaced, rescheduling reminders...")
        val app = context.applicationContext as? TrackrApp ?: return
        val reminders = app.reminderRepository.reminders.value
        ReminderScheduler.rescheduleAll(context, reminders)
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
