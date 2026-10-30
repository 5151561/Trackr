package com.trackr.ui.screens.more

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.trackr.TrackrApp
import com.trackr.data.repository.IScheduledReminderRepository
import com.trackr.data.repository.ITransitRepository
import com.trackr.domain.model.AggregatedSourcePriority
import com.trackr.domain.model.EngineMode
import com.trackr.domain.model.ScheduledReminder
import com.trackr.domain.model.TransitCodeProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 更多页 UI 状态
 */
data class MoreUiState(
    val engineMode: EngineMode = EngineMode.CHELAILE,
    val aggregatedPriority: AggregatedSourcePriority = AggregatedSourcePriority.CHELAILE_FIRST,
    val nearbyStopsCount: Int = 4,
    val preferredTransitCode: TransitCodeProvider = TransitCodeProvider.ASK_ALWAYS,
    val isLiveNotificationEnabled: Boolean = true,
    val reminders: List<ScheduledReminder> = emptyList()
)

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
    val aggregatedPriority: StateFlow<AggregatedSourcePriority> = repository.aggregatedPriority
    val nearbyStopsCount: StateFlow<Int> = repository.nearbyStopsCount
    val preferredTransitCode: StateFlow<TransitCodeProvider> = repository.preferredTransitCodeProvider
    val isLiveNotificationEnabled: StateFlow<Boolean> = repository.isLiveNotificationEnabled
    val reminders: StateFlow<List<ScheduledReminder>> = reminderRepository.reminders

    val uiState: StateFlow<MoreUiState> = combine(
        engineMode,
        aggregatedPriority,
        nearbyStopsCount,
        preferredTransitCode,
        isLiveNotificationEnabled,
        reminders
    ) { args: Array<Any?> ->
        @Suppress("UNCHECKED_CAST")
        MoreUiState(
            engineMode = args[0] as EngineMode,
            aggregatedPriority = args[1] as AggregatedSourcePriority,
            nearbyStopsCount = args[2] as Int,
            preferredTransitCode = args[3] as TransitCodeProvider,
            isLiveNotificationEnabled = args[4] as Boolean,
            reminders = args[5] as List<ScheduledReminder>
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MoreUiState(
            engineMode = repository.engineMode.value,
            aggregatedPriority = repository.aggregatedPriority.value,
            nearbyStopsCount = repository.nearbyStopsCount.value,
            preferredTransitCode = repository.preferredTransitCodeProvider.value,
            isLiveNotificationEnabled = repository.isLiveNotificationEnabled.value,
            reminders = reminderRepository.reminders.value
        )
    )

    fun setLiveNotificationEnabled(enabled: Boolean) {
        repository.setLiveNotificationEnabled(enabled)
    }

    fun setEngineMode(mode: EngineMode) {
        repository.setEngineMode(mode)
    }

    fun setAggregatedPriority(priority: AggregatedSourcePriority) {
        repository.setAggregatedPriority(priority)
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
