package de.qwikster.player.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.qwikster.player.data.local.RecordingEntity
import de.qwikster.player.data.local.RecordingState
import de.qwikster.player.data.repository.RecordingRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Eine Aufnahme, aufbereitet für die Liste. */
data class RecordingItem(
    val id: Long,
    val title: String,
    val channelName: String,
    val startedAt: Long,
    val sizeBytes: Long,
    val isRunning: Boolean,
    val hasFailed: Boolean,
    val errorMessage: String?,
)

data class RecordingsUiState(
    val items: List<RecordingItem> = emptyList(),
    val isLoading: Boolean = true,
) {
    val isEmpty: Boolean get() = !isLoading && items.isEmpty()
}

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val repository: RecordingRepository,
) : ViewModel() {

    val uiState: StateFlow<RecordingsUiState> = repository.observeAll()
        .map { list -> RecordingsUiState(items = list.map { it.toItem() }, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordingsUiState())

    fun stop(id: Long) = repository.stop(id)

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    private fun RecordingEntity.toItem() = RecordingItem(
        id = id,
        title = title,
        channelName = channelName,
        startedAt = startedAt,
        sizeBytes = sizeBytes,
        isRunning = state == RecordingState.RUNNING.name,
        hasFailed = state == RecordingState.FAILED.name,
        errorMessage = errorMessage,
    )
}
