package de.xott.player.ui.vod

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.xott.player.data.model.Episode
import de.xott.player.data.model.Series
import de.xott.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeriesDetailUiState(
    val isLoading: Boolean = true,
    val series: Series? = null,
    val episodesBySeason: Map<Int, List<Episode>> = emptyMap(),
    val selectedSeason: Int? = null,
) {
    val seasons: List<Int> get() = episodesBySeason.keys.sorted()
    val visibleEpisodes: List<Episode> get() = episodesBySeason[selectedSeason].orEmpty()
}

/**
 * Lädt eine Serie und ihre Episoden (`get_series_info`, nur beim ersten
 * Öffnen – siehe [IptvRepository.ensureEpisodesLoaded]) und gruppiert sie
 * nach Staffel für den Staffel-Umschalter.
 */
@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    private val repository: IptvRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val playlistId: Long = checkNotNull(savedStateHandle["playlistId"])
    private val seriesId: String = checkNotNull(savedStateHandle["seriesId"])

    private val series = MutableStateFlow<Series?>(null)
    private val selectedSeason = MutableStateFlow<Int?>(null)

    private val episodesBySeason: StateFlow<Map<Int, List<Episode>>> =
        repository.observeEpisodes(playlistId, seriesId)
            .map { episodes -> episodes.groupBy { it.season } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val uiState: StateFlow<SeriesDetailUiState> = combine(
        series,
        episodesBySeason,
        selectedSeason,
    ) { s, episodes, season ->
        // Solange der Nutzer keine Staffel gewählt hat, zeigen wir die erste.
        val effectiveSeason = season ?: episodes.keys.minOrNull()
        SeriesDetailUiState(
            isLoading = s == null,
            series = s,
            episodesBySeason = episodes,
            selectedSeason = effectiveSeason,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesDetailUiState())

    init {
        viewModelScope.launch {
            val loaded = repository.getSeries(playlistId, seriesId) ?: return@launch
            series.value = loaded
            repository.ensureEpisodesLoaded(loaded)
        }
    }

    fun selectSeason(season: Int) {
        selectedSeason.value = season
    }

    suspend fun resolveEpisodeUrl(episode: Episode): String? =
        repository.resolveEpisodeUrl(playlistId, episode.episodeId, episode.containerExtension)
}
