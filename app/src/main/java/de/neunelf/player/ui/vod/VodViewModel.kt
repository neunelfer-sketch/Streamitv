package de.neunelf.player.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.neunelf.player.data.model.Category
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.model.VodSort
import de.neunelf.player.data.prefs.SettingsStore
import de.neunelf.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Ein Eintrag im Poster-Raster – vereinheitlicht Film und Serie. */
data class VodItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
)

data class VodUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<VodItem> = emptyList(),
    val kind: StreamKind = StreamKind.VOD,
    val sort: VodSort = VodSort.RECENT,
)

/**
 * Gemeinsamer Zustand für "Filme" und "Serien".
 *
 * Die Vereinheitlichung auf [VodItem] hält die Rasteransicht frei von
 * Fallunterscheidungen – der einzige Unterschied bleibt, was beim Klick
 * passiert (Film abspielen vs. Staffelübersicht öffnen).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VodViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val kind = MutableStateFlow(StreamKind.VOD)
    private val selectedCategoryId = MutableStateFlow<String?>(null)

    private val categories: StateFlow<List<Category>> = kind
        .flatMapLatest { repository.observeCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Die gemerkte Reihenfolge des gerade gezeigten Bereichs. */
    private val sort: StateFlow<VodSort> =
        combine(kind, settingsStore.settings) { streamKind, settings ->
            if (streamKind == StreamKind.SERIES) settings.seriesSort else settings.movieSort
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodSort.RECENT)

    private val items: StateFlow<List<VodItem>> =
        combine(kind, selectedCategoryId, sort) { streamKind, categoryId, order ->
            Triple(streamKind, categoryId, order)
        }
            .flatMapLatest { (streamKind, categoryId, order) ->
                if (streamKind == StreamKind.SERIES) {
                    repository.observeSeries(categoryId).map { list ->
                        list.sortedFor(order, recentKey = { it.lastModified }, name = { it.name })
                            .map { series ->
                                VodItem(
                                    id = series.seriesId,
                                    title = series.name,
                                    subtitle = series.year,
                                    posterUrl = series.posterUrl,
                                )
                            }
                    }
                } else {
                    repository.observeMovies(categoryId).map { list ->
                        list.sortedFor(order, recentKey = { it.addedAt }, name = { it.name })
                            .map { movie ->
                                VodItem(
                                    id = movie.streamId,
                                    title = movie.name,
                                    subtitle = movie.year
                                        ?: movie.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
                                    posterUrl = movie.posterUrl,
                                )
                            }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<VodUiState> = combine(
        categories,
        items,
        selectedCategoryId,
        kind,
        sort,
    ) { categoryList, itemList, categoryId, streamKind, order ->
        VodUiState(
            categories = categoryList,
            selectedCategoryId = categoryId,
            items = itemList,
            kind = streamKind,
            sort = order,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState())

    fun setKind(value: StreamKind) {
        if (kind.value != value) {
            kind.value = value
            // Kategorie-Auswahl gilt nicht über Bereiche hinweg.
            selectedCategoryId.value = null
        }
    }

    fun selectCategory(categoryId: String?) {
        selectedCategoryId.value = categoryId
    }

    fun setSort(value: VodSort) {
        viewModelScope.launch { settingsStore.setVodSort(kind.value, value) }
    }

    /**
     * Sortiert Filme und Serien nach demselben Muster.
     *
     * Die Namen werden ohne Rücksicht auf Groß-/Kleinschreibung verglichen –
     * Panels mischen "DER PATE" und "Der Pate" munter, und ein reiner
     * Zeichenvergleich stellte sonst alle Großschreibungen vor die anderen.
     */
    private inline fun <T> List<T>.sortedFor(
        order: VodSort,
        crossinline recentKey: (T) -> Long,
        crossinline name: (T) -> String,
    ): List<T> = when (order) {
        VodSort.RECENT -> sortedByDescending { recentKey(it) }
        VodSort.NAME_ASC -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { name(it) })
        VodSort.NAME_DESC -> sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { name(it) })
    }
}
