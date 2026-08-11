package de.qwikster.player.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.qwikster.player.data.model.Movie
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.repository.IptvRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovieDetailUiState(
    val movie: Movie? = null,
    /** Gespeicherte Fortsetzposition in Millisekunden; 0 = noch nicht angefangen. */
    val resumeMs: Long = 0L,
    val isLoading: Boolean = true,
) {
    val canResume: Boolean get() = resumeMs > 0L
}

/**
 * Beschreibungsseite eines Films.
 *
 * Bis hierher startete ein Film sofort beim Anwählen. Das ist bei einem
 * Sender richtig – dort *ist* das Bild die Information –, bei einem Film
 * aber nicht: Titel und Poster allein sagen nicht, worum es geht, wie lang
 * er ist und ob man ihn schon halb gesehen hat. Genau dafür haben Netflix
 * und Disney+ diese Zwischenseite, und sie beantwortet noch eine zweite
 * Frage, die vorher gar nicht zu stellen war: fortsetzen oder von vorn?
 */
@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: IptvRepository,
) : ViewModel() {

    private val streamId: String = checkNotNull(savedStateHandle["streamId"])

    private val movie = MutableStateFlow<Movie?>(null)
    private val resumeMs = MutableStateFlow(0L)
    private val isLoading = MutableStateFlow(true)

    val uiState: StateFlow<MovieDetailUiState> = combine(
        movie,
        resumeMs,
        isLoading,
    ) { film, position, loading ->
        MovieDetailUiState(movie = film, resumeMs = position, isLoading = loading)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovieDetailUiState())

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val found = repository.getMovie(streamId)
            movie.value = found
            resumeMs.value = found
                ?.let { repository.getResumePosition(it.playlistId, it.streamId, StreamKind.VOD) }
                ?: 0L
            isLoading.value = false
        }
    }

    /**
     * Setzt die Fortsetzposition zurück, damit die Wiedergabe von vorn
     * beginnt.
     *
     * Bewusst hier statt über einen zusätzlichen Navigationsparameter: Der
     * Player holt seine Startposition ohnehin aus dem Verlauf. "Von vorn
     * beginnen" *ist* damit genau das – die gemerkte Stelle verwerfen.
     * `onDone` läuft erst danach, sonst startete der Player gegen die alte,
     * noch nicht überschriebene Position.
     */
    fun startFromBeginning(onDone: () -> Unit) {
        val current = movie.value ?: return
        viewModelScope.launch {
            repository.markWatched(
                playlistId = current.playlistId,
                streamId = current.streamId,
                kind = StreamKind.VOD,
                positionMs = 0L,
            )
            resumeMs.value = 0L
            onDone()
        }
    }
}
