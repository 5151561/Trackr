package com.trackr.data.repository

import com.trackr.domain.model.ScheduledReminder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeScheduledReminderRepository(
    initialReminders: List<ScheduledReminder> = emptyList()
) : IScheduledReminderRepository {

    private val _reminders = MutableStateFlow(initialReminders)
    override val reminders: StateFlow<List<ScheduledReminder>> = _reminders.asStateFlow()

    override suspend fun saveReminder(reminder: ScheduledReminder) {
        _reminders.update { list ->
            val index = list.indexOfFirst { it.id == reminder.id }
            if (index >= 0) {
                list.toMutableList().apply { set(index, reminder) }
            } else {
                list + reminder
            }
        }
    }

    override suspend fun deleteReminder(id: String) {
        _reminders.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun toggleReminder(id: String, enabled: Boolean) {
        _reminders.update { list ->
            list.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }

    override fun getReminderById(id: String): ScheduledReminder? {
        return _reminders.value.firstOrNull { it.id == id }
    }
}
