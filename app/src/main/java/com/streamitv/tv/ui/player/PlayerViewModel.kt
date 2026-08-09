package com.streamitv.tv.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.streamitv.tv.data.model.AspectRatioMode
import com.streamitv.tv.data.model.Channel
import com.streamitv.tv.data.model.ChannelWithProgram
import com.streamitv.tv.data.model.EpgProgram
import com.streamitv.tv.data.prefs.AppSettings
import com.streamitv.tv.data.prefs.SettingsStore
import com.streamitv.tv.data.repository.ChannelFilter
import com.streamitv.tv.data.repository.EpgRepository
import com.streamitv.tv.data.repository.IptvRepository
import com.streamitv.tv.player.PlaybackState
import com.streamitv.tv.player.PlayerManager
import com.streamitv.tv.player.TrackOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Welches Overlay gerade über dem Bild liegt. */
enum class OverlayMode {
    /** Nichts – reines Vollbild. */
    NONE,

    /** Steuerkreuz unten: Senderliste mit EPG. */
    CHANNELS,

    /** Steuerkreuz oben: Schnelloptionen (Ton, Untertitel, Format …). */
    QUICK_OPTIONS,

    /** OK-Taste: Info-Leiste zur laufenden Sendung. */
    INFO,
}

data class PlayerUiState(
    val currentChannel: Channel? = null,
    val currentProgram: EpgProgram? = null,
    val nextProgram: EpgProgram? = null,
    val channels: List<ChannelWithProgram> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val overlay: OverlayMode = OverlayMode.NONE,
    val settings: AppSettings = AppSettings(),
    val isFavorite: Boolean = false,
) {
    /** Index des laufenden Senders – Basis für Zappen und Listen-Autoscroll. */
    val currentIndex: Int
        get() = channels.indexOfFirst { it.channel.streamId == currentChannel?.streamId }
}

/**
 * Steuert den Vollbild-Player.
 *
 * Die Senderliste wird hier komplett vorgehalten (nicht nur der laufende
 * Sender), weil Zappen mit den Kanaltasten sonst einen Datenbankzugriff
 * je Tastendruck bräuchte – auf einem Fire TV Stick deutlich spürbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val settingsStore: SettingsStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val currentChannelId = MutableStateFlow<String?>(null)
    private val overlay = MutableStateFlow(OverlayMode.NONE)

    private val channels: StateFlow<List<ChannelWithProgram>> =
        repository.observeChannels(ChannelFilter.All)
            .flatMapLatest { list ->
                epgRepository.observeChannelsWithProgram(list, System.currentTimeMillis())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<PlayerUiState> = combine(
        channels,
        currentChannelId,
        overlay,
        playerManager.state,
        settingsStore.settings,
    ) { channelList, channelId, overlayMode, playback, settings ->
        val entry = channelList.firstOrNull { it.channel.streamId == channelId }

        PlayerUiState(
            currentChannel = entry?.channel,
            currentProgram = entry?.current,
            nextProgram = entry?.next,
            channels = channelList,
            playback = playback,
            overlay = overlayMode,
            settings = settings,
            isFavorite = entry?.channel?.isFavorite ?: false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    /** Der ExoPlayer für die `PlayerView` – wird von der UI direkt gebraucht. */
    fun player(): ExoPlayer = playerManager.getOrCreate()

    // -----------------------------------------------------------------------
    // Wiedergabe
    // -----------------------------------------------------------------------

    /** Startet einen Sender. Wird auch beim Zappen aufgerufen. */
    fun playChannel(channel: Channel) {
        currentChannelId.value = channel.streamId
        viewModelScope.launch {
            val settings = settingsStore.settings.first()

            playerManager.setPreferredLanguages(
                audio = settings.preferredAudioLanguage,
                subtitle = settings.preferredSubtitleLanguage,
                subtitlesOn = settings.subtitlesEnabled,
            )

            val url = repository.resolveStreamUrl(channel, preferHls = settings.preferHls)
            if (url == null) {
                // Kein auflösbarer Stream: das passiert bei kaputten
                // M3U-Einträgen. Die Fehlermeldung kommt aus dem PlayerManager.
                return@launch
            }

            playerManager.play(
                url = url,
                title = channel.name,
                isLive = true,
                bufferMs = settings.bufferMs,
            )
            repository.markWatched(channel.playlistId, channel.streamId)
        }
    }

    /** Nächster Sender in der Liste (Kanal +). */
    fun nextChannel() = zap(+1)

    /** Vorheriger Sender (Kanal −). */
    fun previousChannel() = zap(-1)

    private fun zap(direction: Int) {
        val list = channels.value
        if (list.isEmpty()) return
        val index = uiState.value.currentIndex
        // Modulo mit Korrektur, damit -1 am Anfang zum letzten Sender führt.
        val target = ((if (index < 0) 0 else index) + direction + list.size) % list.size
        playChannel(list[target].channel)
    }

    fun stop() {
        playerManager.stop()
    }

    // -----------------------------------------------------------------------
    // Overlays
    // -----------------------------------------------------------------------

    fun showOverlay(mode: OverlayMode) {
        overlay.value = mode
    }

    fun hideOverlay() {
        overlay.value = OverlayMode.NONE
    }

    /** Reagiert auf die OK-Taste im Vollbild: Info-Leiste ein-/ausblenden. */
    fun toggleInfo() {
        overlay.value = if (overlay.value == OverlayMode.INFO) OverlayMode.NONE else OverlayMode.INFO
    }

    // -----------------------------------------------------------------------
    // Schnelloptionen
    // -----------------------------------------------------------------------

    fun selectAudioTrack(option: TrackOption) {
        playerManager.selectAudioTrack(option)
        option.language?.let { language ->
            viewModelScope.launch { settingsStore.setAudioLanguage(language) }
        }
    }

    fun selectSubtitleTrack(option: TrackOption) {
        playerManager.selectSubtitleTrack(option)
        viewModelScope.launch {
            settingsStore.setSubtitlesEnabled(!option.isOffOption)
            option.language?.let { settingsStore.setSubtitleLanguage(it) }
        }
    }

    fun cycleAspectRatio() {
        viewModelScope.launch {
            val current = settingsStore.settings.first().aspectRatio
            settingsStore.setAspectRatio(current.next())
        }
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        viewModelScope.launch { settingsStore.setAspectRatio(mode) }
    }

    fun toggleFavorite() {
        val channel = uiState.value.currentChannel ?: return
        viewModelScope.launch { repository.toggleFavorite(channel) }
    }

    override fun onCleared() {
        // Der PlayerManager ist ein Singleton (u. a. wegen PiP und
        // MediaSession) – hier nur stoppen, nicht freigeben.
        playerManager.stop()
        super.onCleared()
    }
}
