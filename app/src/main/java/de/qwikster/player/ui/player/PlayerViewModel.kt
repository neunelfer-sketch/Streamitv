package de.qwikster.player.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import de.qwikster.player.data.model.AspectRatioMode
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.ChannelWithProgram
import de.qwikster.player.data.model.EpgProgram
import de.qwikster.player.data.prefs.AppSettings
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.repository.ChannelFilter
import de.qwikster.player.data.repository.EpgRepository
import de.qwikster.player.data.repository.IptvRepository
import de.qwikster.player.player.PlaybackState
import de.qwikster.player.player.PlayerManager
import de.qwikster.player.player.TrackOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    /**
     * Laufende und folgende Sendung des *gerade gespielten* Senders.
     *
     * Getrennt von [channels], weil die Senderliste aus Speichergründen nur
     * die laufende Sendung mitbringt (siehe
     * [de.qwikster.player.data.repository.EpgRepository.observeChannelsWithProgram]).
     * Die Info-Leiste zeigt zusätzlich "Danach" – das lohnt eine eigene,
     * winzige Abfrage für genau einen Sender.
     */
    private val currentAndNext: StateFlow<Pair<EpgProgram?, EpgProgram?>> =
        combine(channels, currentChannelId) { channelList, channelId ->
            channelList.firstOrNull { it.channel.streamId == channelId }?.channel
        }
            .flatMapLatest { channel ->
                if (channel == null) {
                    flowOf(null to null)
                } else {
                    epgRepository.observeCurrentAndNext(
                        playlistId = channel.playlistId,
                        epgChannelId = channel.epgChannelId,
                        now = System.currentTimeMillis(),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    val uiState: StateFlow<PlayerUiState> = combine(
        channels,
        currentChannelId,
        overlay,
        // Zwei Quellen gebündelt, damit die typsichere `combine`-Variante mit
        // fünf Argumenten reicht – die Fassung darüber liefert nur ein
        // `Array<Any?>` und erzwänge ungeprüfte Umwandlungen.
        combine(playerManager.state, settingsStore.settings) { playback, settings ->
            playback to settings
        },
        currentAndNext,
    ) { channelList, channelId, overlayMode, (playback, settings), (current, next) ->
        val entry = channelList.firstOrNull { it.channel.streamId == channelId }

        PlayerUiState(
            currentChannel = entry?.channel,
            // Die Einzelabfrage ist genauer als der Eintrag aus der Liste –
            // fällt sie aus, bleibt die Liste als Rückfallebene.
            currentProgram = current ?: entry?.current,
            nextProgram = next,
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
        // Sofort, nicht erst nach der Quellenauflösung: sonst bliebe die
        // Fehlermeldung des vorigen Senders (oder eines zuvor gesehenen
        // Films – derselbe Player wird geteilt) kurz sichtbar, obwohl
        // dieser Sender einwandfrei anläuft. Siehe [PlayerManager.clearError].
        playerManager.clearError()
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
