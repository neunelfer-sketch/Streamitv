package de.xott.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import de.xott.player.data.model.AspectRatioMode
import de.xott.player.data.model.Channel
import de.xott.player.data.model.ChannelWithProgram
import de.xott.player.data.model.EpgProgram
import de.xott.player.data.prefs.AppSettings
import de.xott.player.data.prefs.SettingsStore
import de.xott.player.data.repository.ChannelFilter
import de.xott.player.data.repository.EpgRepository
import de.xott.player.data.repository.IptvRepository
import de.xott.player.player.PlaybackState
import de.xott.player.player.PlayerManager
import de.xott.player.player.TrackOption
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

/** Woher der aktuell spielende Inhalt kommt – bestimmt, welche Bedienelemente sinnvoll sind. */
enum class PlaybackContentType {
    /** Normaler Live-Sender: Zappen, EPG, Favorit sind verfügbar. */
    LIVE,

    /** Aufgezeichnete/verpasste Sendung eines archivfähigen Senders. */
    CATCHUP,

    /** Film oder Serienepisode: kein Zappen, keine Senderliste. */
    VOD,
}

/** Nur für VOD/Episoden: der Player kennt hier keinen [Channel]. */
private data class VodPlayback(val title: String)

data class PlayerUiState(
    val contentType: PlaybackContentType = PlaybackContentType.LIVE,
    val title: String = "",
    val currentChannel: Channel? = null,
    val currentProgram: EpgProgram? = null,
    val nextProgram: EpgProgram? = null,
    val channels: List<ChannelWithProgram> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val overlay: OverlayMode = OverlayMode.NONE,
    val settings: AppSettings = AppSettings(),
    val isFavorite: Boolean = false,
) {
    /** Index des laufenden Senders – Basis für Zappen und Listen-Autoscroll. Nur bei LIVE sinnvoll. */
    val currentIndex: Int
        get() = channels.indexOfFirst { it.channel.streamId == currentChannel?.streamId }

    /** Zappen und die Senderliste (▼) ergeben nur bei einem laufenden Sender Sinn. */
    val isZappable: Boolean get() = contentType != PlaybackContentType.VOD
}

/**
 * Steuert den Vollbild-Player.
 *
 * Deckt drei Wiedergabe-Arten ab, die sich denselben Player und dieselben
 * Overlays teilen, aber unterschiedliche Metadaten mitbringen:
 * - **Live** ([playChannel]): kennt EPG, Favorit, Zappen.
 * - **Catch-up** ([playCatchup]): derselbe Sender, aber eine vergangene
 *   Sendung statt des Live-Feeds – die Info-Leiste zeigt deren fixe
 *   Zeit/Titel statt der gerade laufenden Sendung.
 * - **VOD** ([playVod]): Film oder Episode ohne Sender-Identität, deshalb
 *   ohne Zapp-/Senderlisten-Overlay ([PlayerUiState.isZappable]).
 *
 * Die Senderliste wird komplett vorgehalten (nicht nur der laufende Sender),
 * weil Zappen mit den Kanaltasten sonst einen Datenbankzugriff je
 * Tastendruck bräuchte – auf einem Fire TV Stick deutlich spürbar.
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
    private val catchupProgram = MutableStateFlow<EpgProgram?>(null)
    private val vodPlayback = MutableStateFlow<VodPlayback?>(null)
    private val overlay = MutableStateFlow(OverlayMode.NONE)

    private val channels: StateFlow<List<ChannelWithProgram>> =
        repository.observeChannels(ChannelFilter.All)
            .flatMapLatest { list ->
                epgRepository.observeChannelsWithProgram(list, System.currentTimeMillis())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Bündelt alle "was läuft gerade"-Flags in einen Flow, damit `combine` unter 5 Argumenten bleibt. */
    private val selection: StateFlow<Triple<String?, EpgProgram?, VodPlayback?>> =
        combine(currentChannelId, catchupProgram, vodPlayback) { id, catchup, vod -> Triple(id, catchup, vod) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Triple(null, null, null))

    val uiState: StateFlow<PlayerUiState> = combine(
        channels,
        selection,
        overlay,
        playerManager.state,
        settingsStore.settings,
    ) { channelList, (channelId, catchup, vod), overlayMode, playback, settings ->
        if (vod != null) {
            PlayerUiState(
                contentType = PlaybackContentType.VOD,
                title = vod.title,
                playback = playback,
                overlay = overlayMode,
                settings = settings,
            )
        } else {
            val entry = channelList.firstOrNull { it.channel.streamId == channelId }
            val isCatchup = catchup != null

            PlayerUiState(
                contentType = if (isCatchup) PlaybackContentType.CATCHUP else PlaybackContentType.LIVE,
                title = entry?.channel?.name.orEmpty(),
                currentChannel = entry?.channel,
                currentProgram = if (isCatchup) catchup else entry?.current,
                nextProgram = if (isCatchup) null else entry?.next,
                channels = channelList,
                playback = playback,
                overlay = overlayMode,
                settings = settings,
                isFavorite = entry?.channel?.isFavorite ?: false,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    /** Der ExoPlayer für die `PlayerView` – wird von der UI direkt gebraucht. */
    fun player(): ExoPlayer = playerManager.getOrCreate()

    // -----------------------------------------------------------------------
    // Wiedergabe
    // -----------------------------------------------------------------------

    /** Startet einen Live-Sender. Wird auch beim Zappen aufgerufen. */
    fun playChannel(channel: Channel) {
        currentChannelId.value = channel.streamId
        catchupProgram.value = null
        vodPlayback.value = null
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

    /**
     * Startet die Catch-up-Wiedergabe einer vergangenen Sendung (aus dem
     * TV-Guide angetippt). Nur möglich, wenn der Sender ein Archiv anbietet
     * ([Channel.hasArchive]) – siehe [IptvRepository.resolveCatchupUrl].
     */
    fun playCatchup(channel: Channel, program: EpgProgram) {
        currentChannelId.value = channel.streamId
        catchupProgram.value = program
        vodPlayback.value = null
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            playerManager.setPreferredLanguages(
                audio = settings.preferredAudioLanguage,
                subtitle = settings.preferredSubtitleLanguage,
                subtitlesOn = settings.subtitlesEnabled,
            )

            val url = repository.resolveCatchupUrl(channel, program) ?: return@launch
            playerManager.play(
                url = url,
                title = channel.name,
                isLive = false,
                bufferMs = settings.bufferMs,
            )
        }
    }

    /** Startet einen Film oder eine Serienepisode. */
    fun playVod(title: String, url: String, startPositionMs: Long = 0L) {
        currentChannelId.value = null
        catchupProgram.value = null
        vodPlayback.value = VodPlayback(title)
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            playerManager.play(
                url = url,
                title = title,
                isLive = false,
                startPositionMs = startPositionMs,
                bufferMs = settings.bufferMs,
            )
        }
    }

    /** Nächster Sender in der Liste (Kanal +). Ohne Wirkung außerhalb von LIVE/CATCHUP. */
    fun nextChannel() = zap(+1)

    /** Vorheriger Sender (Kanal −). */
    fun previousChannel() = zap(-1)

    private fun zap(direction: Int) {
        if (!uiState.value.isZappable) return
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
        if (mode == OverlayMode.CHANNELS && !uiState.value.isZappable) return
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
