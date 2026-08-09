package de.neunelf.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.neunelf.player.core.TimeFormat
import de.neunelf.player.data.model.AspectRatioMode
import de.neunelf.player.data.prefs.AppSettings
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.data.repository.EpgSyncProgress
import de.neunelf.player.data.repository.EpgRepository
import de.neunelf.player.data.repository.IptvRepository
import de.neunelf.player.data.repository.PlaylistSyncer
import de.neunelf.player.data.repository.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val playlistName: String? = null,
    val lastSyncLabel: String = "Nie",
    val lastEpgSyncLabel: String = "Nie",
    val programCount: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val syncer: PlaylistSyncer,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val programCount = MutableStateFlow(0)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        repository.observeActivePlaylist(),
        message,
        programCount,
    ) { settings, playlist, statusMessage, count ->
        SettingsUiState(
            settings = settings,
            playlistName = playlist?.name,
            lastSyncLabel = playlist?.lastSyncAt.toLabel(),
            lastEpgSyncLabel = playlist?.lastEpgSyncAt.toLabel(),
            programCount = count,
            message = statusMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            repository.getActivePlaylist()?.let {
                programCount.value = epgRepository.programCount(it.id)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Playlist
    // -----------------------------------------------------------------------

    fun refreshPlaylist() {
        viewModelScope.launch {
            val playlist = repository.getActivePlaylist() ?: return@launch
            syncer.sync(playlist).collect { progress ->
                message.value = when (progress) {
                    is SyncProgress.Step -> progress.message
                    is SyncProgress.Done -> "${progress.channels} Sender aktualisiert"
                    is SyncProgress.Failed -> "Fehler: ${progress.message}"
                }
            }
        }
    }

    fun refreshEpg() {
        viewModelScope.launch {
            val playlist = repository.getActivePlaylist() ?: return@launch
            epgRepository.refresh(playlist).collect { progress ->
                message.value = when (progress) {
                    is EpgSyncProgress.Step -> progress.message
                    is EpgSyncProgress.Done -> "${progress.programCount} Sendungen geladen"
                    is EpgSyncProgress.Failed -> "Fehler: ${progress.message}"
                }
            }
            programCount.value = epgRepository.programCount(playlist.id)
        }
    }

    fun removePlaylist() {
        viewModelScope.launch {
            repository.getActivePlaylist()?.let { repository.deletePlaylist(it.id) }
        }
    }

    // -----------------------------------------------------------------------
    // Wiedergabe-Einstellungen
    // -----------------------------------------------------------------------

    /** Schaltet zyklisch durch die Puffer-Voreinstellungen. */
    fun cycleBuffer() {
        viewModelScope.launch {
            val values = SettingsStore.BUFFER_PRESETS.values.toList()
            val current = settingsStore.settings.first().bufferMs
            val nextIndex = (values.indexOf(current) + 1).takeIf { it in values.indices } ?: 0
            settingsStore.setBufferMs(values[nextIndex])
        }
    }

    fun togglePreferHls() {
        viewModelScope.launch {
            settingsStore.setPreferHls(!settingsStore.settings.first().preferHls)
        }
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        viewModelScope.launch { settingsStore.setAspectRatio(mode) }
    }

    /** Wechselt zwischen den gängigsten Tonspur-Sprachen. */
    fun cycleAudioLanguage() {
        viewModelScope.launch {
            val languages = listOf("deu", "eng", "fra", "ita", "spa", "tur", "")
            val current = settingsStore.settings.first().preferredAudioLanguage
            val nextIndex = (languages.indexOf(current) + 1) % languages.size
            settingsStore.setAudioLanguage(languages[nextIndex])
        }
    }

    fun toggleSubtitles() {
        viewModelScope.launch {
            settingsStore.setSubtitlesEnabled(!settingsStore.settings.first().subtitlesEnabled)
        }
    }

    /** "Nie" oder "Heute 14:32" / "Mo 05.08. 09:11". */
    private fun Long?.toLabel(): String {
        if (this == null || this == 0L) return "Nie"
        val isToday = TimeFormat.startOfDay(this) == TimeFormat.startOfDay(System.currentTimeMillis())
        return if (isToday) {
            "Heute ${TimeFormat.clock(this)}"
        } else {
            "${TimeFormat.dayShort(this)} ${TimeFormat.clock(this)}"
        }
    }
}
