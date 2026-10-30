package com.trackr.ui.screens.more

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.ScheduledReminder
import com.trackr.domain.model.TransitCodeProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MoreViewModel(
    application: Application,
    private val repository: ITransitRepository,
    private val reminderRepository: IScheduledReminderRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        (application as? TrackrApp)?.repository ?: TrackrApp.instance.repository,
        (application as? TrackrApp)?.reminderRepository ?: TrackrApp.instance.reminderRepository
    )

    constructor() : this(
        TrackrApp.instance,
        TrackrApp.instance.repository,
        TrackrApp.instance.reminderRepository
    )

    val engineMode: StateFlow<EngineMode> = repository.engineMode
    val nearbyStopsCount: StateFlow<Int> = repository.nearbyStopsCount
    val preferredTransitCode: StateFlow<TransitCodeProvider> = repository.preferredTransitCodeProvider
    val reminders: StateFlow<List<ScheduledReminder>> = reminderRepository.reminders

    fun setEngineMode(mode: EngineMode) {
        repository.setEngineMode(mode)
    }

    fun setNearbyStopsCount(count: Int) {
        val clamped = count.coerceIn(1, 16)
        repository.setNearbyStopsCount(clamped)
    }

    fun setPreferredTransitCode(provider: TransitCodeProvider) {
        viewModelScope.launch {
            repository.setPreferredTransitCodeProvider(provider)
        }
    }

    fun toggleReminder(id: String, enabled: Boolean) {
        viewModelScope.launch {
            reminderRepository.toggleReminder(id, enabled)
        }
    }

    fun deleteReminder(id: String) {
        viewModelScope.launch {
            reminderRepository.deleteReminder(id)
        }
    }
}
