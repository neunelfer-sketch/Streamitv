package de.xott.player.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.xott.player.data.model.Movie
import de.xott.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MovieDetailUiState(
    val isLoading: Boolean = true,
    val movie: Movie? = null,
)

/**
 * Lädt einen Film inklusive Detailinformationen (`get_vod_info`) nach, sobald
 * seine Beschreibung noch fehlt – das VOD-Listing selbst liefert nur Namen
 * und Poster.
 */
@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    private val repository: IptvRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])
    private val streamId: String = checkNotNull(savedStateHandle["streamId"])

    private val _uiState = MutableStateFlow(MovieDetailUiState())
    val uiState: StateFlow<MovieDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val cached = repository.getMovie(playlistId, streamId) ?: run {
                _uiState.value = MovieDetailUiState(isLoading = false, movie = null)
                return@launch
            }
            _uiState.value = MovieDetailUiState(isLoading = false, movie = cached)
            _uiState.value = MovieDetailUiState(isLoading = false, movie = repository.enrichMovieIfNeeded(cached))
        }
    }

    suspend fun resolvePlaybackUrl(): String? {
        val movie = _uiState.value.movie ?: return null
        return repository.resolveMovieUrl(movie)
    }
}
