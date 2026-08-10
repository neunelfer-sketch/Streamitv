package de.neunelf.player.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.neunelf.player.data.model.Episode
import de.neunelf.player.data.model.Series
import de.neunelf.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeriesDetailUiState(
    val series: Series? = null,
    /** Episoden gruppiert nach Staffel, aufsteigend sortiert. */
    val episodesBySeason: Map<Int, List<Episode>> = emptyMap(),
    val isLoading: Boolean = true,
)

/**
 * Staffel-/Episodenübersicht einer Serie.
 *
 * Episoden werden nicht beim Playlist-Sync geladen (das wäre bei Panels mit
 * tausenden Serien viel zu viel Traffic), sondern erst hier bei Bedarf über
 * `get_series_info` nachgefragt und in der lokalen DB zwischengespeichert.
 */
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: IptvRepository,
) : ViewModel() {

    private val seriesId: String = checkNotNull(savedStateHandle["seriesId"])

    private val series = MutableStateFlow<Series?>(null)
    private val isLoading = MutableStateFlow(true)

    val uiState: StateFlow<SeriesDetailUiState> = combine(
        series,
        repository.observeEpisodes(seriesId),
        isLoading,
    ) { seriesValue, episodes, loading ->
        SeriesDetailUiState(
            series = seriesValue,
            episodesBySeason = episodes.groupBy { it.season }.toSortedMap(),
            isLoading = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesDetailUiState())

    init {
        viewModelScope.launch {
            series.value = repository.getSeriesDetails(seriesId)
            repository.refreshSeriesEpisodes(seriesId)
            isLoading.value = false
        }
    }
}
