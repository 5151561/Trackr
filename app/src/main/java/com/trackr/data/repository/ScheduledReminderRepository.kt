package com.trackr.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.trackr.domain.model.ScheduledReminder
import com.trackr.util.ReminderScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 基于 SharedPreferences 与 JSON 的定时提醒持久化仓库实现
 */
class ScheduledReminderRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : IScheduledReminderRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /** 串行化所有读改写，避免 UI 与提醒接收器并发调用时丢失更新 */
    private val mutex = Mutex()

    private val _reminders = MutableStateFlow<List<ScheduledReminder>>(loadInitialReminders())
    override val reminders: StateFlow<List<ScheduledReminder>> = _reminders.asStateFlow()

    private fun loadInitialReminders(): List<ScheduledReminder> {
        val rawJson = prefs.getString(KEY_REMINDERS_JSON, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ScheduledReminder>>(rawJson)
        } catch (e: Exception) {
            // 解析失败时先备份原始数据，避免下次写入无声覆盖
            Log.e(TAG, "Failed to parse reminders json, backing up raw data", e)
            backupCorruptJson(rawJson)
            emptyList()
        }
    }

    /**
     * 将无法解析的原始 JSON 备份到 [KEY_CORRUPT_BACKUP_PREFIX]<时间戳>，只保留最近一份
     */
    private fun backupCorruptJson(rawJson: String) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(KEY_CORRUPT_BACKUP_PREFIX) }
            .forEach { editor.remove(it) }
        editor.putString(KEY_CORRUPT_BACKUP_PREFIX + System.currentTimeMillis(), rawJson)
        editor.apply()
    }

    override suspend fun saveReminder(reminder: ScheduledReminder) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _reminders.value.toMutableList()
            val index = current.indexOfFirst { it.id == reminder.id }
            if (index >= 0) {
                current[index] = reminder
            } else {
                current.add(reminder)
            }
            persistReminders(current)
            ReminderScheduler.scheduleReminder(context, reminder)
        }
    }

    override suspend fun deleteReminder(id: String) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _reminders.value.filterNot { it.id == id }
            persistReminders(current)
            ReminderScheduler.cancelReminder(context, id)
        }
    }

    override suspend fun toggleReminder(id: String, enabled: Boolean) = withContext(ioDispatcher) {
        mutex.withLock {
            val current = _reminders.value.map { reminder ->
                if (reminder.id == id) reminder.copy(enabled = enabled) else reminder
            }
            persistReminders(current)
            val updated = current.find { it.id == id }
            if (updated != null) {
                if (enabled) {
                    ReminderScheduler.scheduleReminder(context, updated)
                } else {
                    ReminderScheduler.cancelReminder(context, id)
                }
            }
        }
    }

    override fun getReminderById(id: String): ScheduledReminder? {
        return _reminders.value.find { it.id == id }
    }

    private fun persistReminders(list: List<ScheduledReminder>) {
        _reminders.value = list
        try {
            val rawJson = json.encodeToString(list)
            prefs.edit().putString(KEY_REMINDERS_JSON, rawJson).apply()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "ScheduledReminderRepo"
        private const val PREFS_NAME = "trackr_scheduled_reminders"
        private const val KEY_REMINDERS_JSON = "reminders_list_json"
        internal const val KEY_CORRUPT_BACKUP_PREFIX = "reminders_list_json_corrupt_backup_"
    }
}
