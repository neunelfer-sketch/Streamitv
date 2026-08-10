package de.neunelf.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.neunelf.player.data.model.Channel
import de.neunelf.player.data.model.Movie
import de.neunelf.player.data.model.Series
import de.neunelf.player.data.repository.ChannelFilter
import de.neunelf.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
    val totalCount: Int get() = channels.size + movies.size + series.size
}

/**
 * Übergreifende Suche über Sender, Filme und Serien.
 *
 * Die Eingabe wird kurz abgewartet, bevor gesucht wird: Auf einer
 * Fernbedienung entsteht ein Buchstabe nach dem anderen, und jede
 * Zwischenstufe wäre eine eigene Datenbankabfrage über drei Tabellen. Erst
 * ab zwei Zeichen wird überhaupt gesucht – ein einzelner Buchstabe trifft
 * in einer großen Playlist praktisch alles und sagt nichts aus.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: IptvRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val effectiveQuery = _query
        .debounce(DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()

    private val channels = effectiveQuery.flatMapLatest { query ->
        if (query.length < MIN_QUERY) flowOf(emptyList())
        else repository.observeChannels(ChannelFilter.Search(query))
    }

    private val movies = effectiveQuery.flatMapLatest { query ->
        if (query.length < MIN_QUERY) flowOf(emptyList()) else repository.searchMovies(query)
    }

    private val series = effectiveQuery.flatMapLatest { query ->
        if (query.length < MIN_QUERY) flowOf(emptyList()) else repository.searchSeries(query)
    }

    val uiState: StateFlow<SearchUiState> = combine(
        _query,
        channels,
        movies,
        series,
    ) { query, channelList, movieList, seriesList ->
        SearchUiState(
            query = query,
            channels = channelList,
            movies = movieList,
            series = seriesList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    private companion object {
        /** Wartezeit nach dem letzten Tastendruck, bevor gesucht wird. */
        const val DEBOUNCE_MS = 300L

        /** Ab dieser Länge wird gesucht. */
        const val MIN_QUERY = 2
    }
}
