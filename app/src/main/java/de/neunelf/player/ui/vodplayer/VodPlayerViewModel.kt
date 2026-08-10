package de.neunelf.player.ui.vodplayer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import de.neunelf.player.core.withErrorCode
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.data.repository.IptvRepository
import de.neunelf.player.di.ApplicationScope
import de.neunelf.player.player.PlaybackState
import de.neunelf.player.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
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
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val movieStreamId: String? = savedStateHandle["streamId"]
    private val episodeId: String? = savedStateHandle["episodeId"]

    private val title = MutableStateFlow("")
    private val loadError = MutableStateFlow<String?>(null)

    /** Woher der laufende Inhalt stammt – für das Sichern der Position. */
    private var source: ResolvedSource? = null

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
        // Sofort und ohne auf die Quellenauflösung zu warten: sonst bliebe
        // eine Fehlermeldung des zuvor gesehenen Senders oder Films kurz
        // sichtbar, obwohl dieser neue Titel einwandfrei anläuft – siehe
        // [PlayerManager.clearError].
        playerManager.clearError()
        viewModelScope.launch {
            val settings = settingsStore.settings.first()
            playerManager.setPreferredLanguages(
                audio = settings.preferredAudioLanguage,
                subtitle = settings.preferredSubtitleLanguage,
                subtitlesOn = settings.subtitlesEnabled,
            )

            val resolved = resolveSource().getOrElse { error ->
                loadError.value = "Inhalt konnte nicht geladen werden".withErrorCode(error.message ?: "UNKNOWN")
                return@launch
            }
            source = resolved
            title.value = resolved.title

            val resumeMs = repository.getResumePosition(resolved.playlistId, resolved.streamId, resolved.kind)
            playerManager.play(
                url = resolved.url,
                title = resolved.title,
                isLive = false,
                startPositionMs = resumeMs,
                bufferMs = settings.bufferMs,
            )

            // Fortschritt regelmäßig sichern statt nur beim Verlassen: so geht
            // die Fortsetzposition auch bei einem harten Abbruch (Absturz,
            // Strom weg) höchstens ein paar Sekunden verloren.
            while (isActive) {
                delay(10_000)
                savePosition()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Steuerung
    // -----------------------------------------------------------------------

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun seekBy(deltaMs: Long) = playerManager.seekBy(deltaMs)

    override fun onCleared() {
        // Position **vor** dem Stoppen lesen und über den App-Scope sichern:
        // `viewModelScope` ist hier schon abgebrochen, und `stop()` setzt die
        // Position des Players zurück. Ohne das ginge beim Verlassen der
        // Fortschritt seit dem letzten Takt verloren – bis zu zehn Sekunden.
        val current = source
        val positionMs = playerManager.currentPosition()
        val durationMs = playerManager.currentDuration()
        if (current != null && durationMs > 0) {
            appScope.launch {
                repository.markWatched(
                    playlistId = current.playlistId,
                    streamId = current.streamId,
                    kind = current.kind,
                    positionMs = positionMs,
                    durationMs = durationMs,
                )
            }
        }
        playerManager.stop()
        super.onCleared()
    }

    /** Sichert die Wiedergabeposition, sofern die Dauer schon bekannt ist. */
    private suspend fun savePosition() {
        val current = source ?: return
        val duration = playerManager.currentDuration()
        if (duration <= 0) return
        repository.markWatched(
            playlistId = current.playlistId,
            streamId = current.streamId,
            kind = current.kind,
            positionMs = playerManager.currentPosition(),
            durationMs = duration,
        )
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

    /**
     * Löst Film oder Episode auf.
     *
     * Der jeweilige Fehlschlag trägt einen eigenen, kurzen Code im
     * [Result] – so lässt sich aus der Ferne genau sagen, an welcher
     * Stelle es hakte, statt nur "Inhalt konnte nicht geladen werden" zu
     * sehen.
     */
    private suspend fun resolveSource(): Result<ResolvedSource> {
        movieStreamId?.let { id ->
            val movie = repository.getMovie(id)
                ?: return Result.failure(IllegalStateException("MOVIE_NOT_FOUND"))
            val url = repository.resolveMovieUrl(movie)
                ?: return Result.failure(IllegalStateException("MOVIE_NO_URL"))
            return Result.success(
                ResolvedSource(
                    url = url,
                    title = movie.name,
                    playlistId = movie.playlistId,
                    streamId = movie.streamId,
                    kind = StreamKind.VOD,
                ),
            )
        }
        episodeId?.let { id ->
            val episode = repository.getEpisode(id)
                ?: return Result.failure(IllegalStateException("EPISODE_NOT_FOUND"))
            val playlist = repository.getActivePlaylist()
                ?: return Result.failure(IllegalStateException("NO_PLAYLIST"))
            val url = repository.resolveEpisodeUrl(playlist.id, episode)
                ?: return Result.failure(IllegalStateException("EPISODE_NO_URL"))
            return Result.success(
                ResolvedSource(
                    url = url,
                    title = "S${episode.season}E${episode.episodeNumber} · ${episode.title}",
                    playlistId = playlist.id,
                    streamId = episode.episodeId,
                    kind = StreamKind.SERIES,
                ),
            )
        }
        return Result.failure(IllegalStateException("NO_SOURCE_ARGUMENT"))
    }
}
