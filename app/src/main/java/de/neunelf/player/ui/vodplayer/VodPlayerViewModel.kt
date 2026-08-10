package de.neunelf.player.ui.vodplayer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.data.repository.IptvRepository
import de.neunelf.player.player.PlaybackState
import de.neunelf.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VodPlayerUiState(
    val title: String = "",
    val playback: PlaybackState = PlaybackState(),
    val loadError: String? = null,
) {
    val error: String? get() = loadError ?: playback.error
}

/**
 * Spielt einen Film oder eine einzelne Episode ab.
 *
 * Bewusst getrennt von [de.neunelf.player.ui.player.PlayerViewModel]: der
 * Live-Player ist eng um "Sender" gebaut (Zappen, Senderliste, EPG-Leiste),
 * das passt inhaltlich nicht zu einem einzelnen Film mit Vor-/Zurückspulen.
 * Beide teilen sich denselben [PlayerManager] (und damit dieselbe
 * ExoPlayer-Instanz) – nur die Bedienoberfläche ist eine andere.
 *
 * Die Route liefert entweder `streamId` (Film) oder `episodeId` (Episode)
 * als Navigationsargument.
 */
@HiltViewModel
class VodPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: IptvRepository,
    private val settingsStore: SettingsStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val movieStreamId: String? = savedStateHandle["streamId"]
    private val episodeId: String? = savedStateHandle["episodeId"]

    private val title = MutableStateFlow("")
    private val loadError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<VodPlayerUiState> = combine(
        title,
        playerManager.state,
        loadError,
    ) { currentTitle, playback, error ->
        VodPlayerUiState(title = currentTitle, playback = playback, loadError = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodPlayerUiState())

    /** Der ExoPlayer für die `PlayerView` – wird von der UI direkt gebraucht. */
    fun player(): ExoPlayer = playerManager.getOrCreate()

    init {
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            playerManager.setPreferredLanguages(
                audio = settings.preferredAudioLanguage,
                subtitle = settings.preferredSubtitleLanguage,
                subtitlesOn = settings.subtitlesEnabled,
            )

            val source = resolveSource()
            if (source == null) {
                loadError.value = "Inhalt konnte nicht geladen werden"
                return@launch
            }
            title.value = source.title

            val resumeMs = repository.getResumePosition(source.playlistId, source.streamId, source.kind)
            playerManager.play(
                url = source.url,
                title = source.title,
                isLive = false,
                startPositionMs = resumeMs,
                bufferMs = settings.bufferMs,
            )

            // Fortschritt regelmäßig sichern statt nur beim Verlassen: so geht
            // die Fortsetzposition auch bei einem harten Abbruch (Absturz,
            // Strom weg) höchstens ein paar Sekunden verloren.
            while (isActive) {
                delay(10_000)
                val duration = playerManager.currentDuration()
                if (duration > 0) {
                    repository.markWatched(
                        playlistId = source.playlistId,
                        streamId = source.streamId,
                        kind = source.kind,
                        positionMs = playerManager.currentPosition(),
                        durationMs = duration,
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Steuerung
    // -----------------------------------------------------------------------

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun seekBy(deltaMs: Long) = playerManager.seekBy(deltaMs)

    override fun onCleared() {
        playerManager.stop()
        super.onCleared()
    }

    // -----------------------------------------------------------------------
    // Interna
    // -----------------------------------------------------------------------

    private data class ResolvedSource(
        val url: String,
        val title: String,
        val playlistId: Long,
        val streamId: String,
        val kind: StreamKind,
    )

    private suspend fun resolveSource(): ResolvedSource? {
        movieStreamId?.let { id ->
            val movie = repository.getMovie(id) ?: return null
            val url = repository.resolveMovieUrl(movie) ?: return null
            return ResolvedSource(
                url = url,
                title = movie.name,
                playlistId = movie.playlistId,
                streamId = movie.streamId,
                kind = StreamKind.VOD,
            )
        }
        episodeId?.let { id ->
            val episode = repository.getEpisode(id) ?: return null
            val playlist = repository.getActivePlaylist() ?: return null
            val url = repository.resolveEpisodeUrl(playlist.id, episode.episodeId, episode.containerExtension)
                ?: return null
            return ResolvedSource(
                url = url,
                title = "S${episode.season}E${episode.episodeNumber} · ${episode.title}",
                playlistId = playlist.id,
                streamId = episode.episodeId,
                kind = StreamKind.SERIES,
            )
        }
        return null
    }
}
