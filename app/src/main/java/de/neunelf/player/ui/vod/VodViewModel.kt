package de.neunelf.player.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.neunelf.player.data.model.Category
import de.neunelf.player.data.model.StreamKind
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
import javax.inject.Inject

/** Ein Eintrag im Poster-Raster – vereinheitlicht Film und Serie. */
data class VodItem(
    val id: String,
    val playlistId: Long,
    val title: String,
    val subtitle: String?,
    val posterUrl: String?,
)

data class VodUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<VodItem> = emptyList(),
    val kind: StreamKind = StreamKind.VOD,
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
) : ViewModel() {

    private val kind = MutableStateFlow(StreamKind.VOD)
    private val selectedCategoryId = MutableStateFlow<String?>(null)

    private val categories: StateFlow<List<Category>> = kind
        .flatMapLatest { repository.observeCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val items: StateFlow<List<VodItem>> =
        combine(kind, selectedCategoryId) { streamKind, categoryId -> streamKind to categoryId }
            .flatMapLatest { (streamKind, categoryId) ->
                if (streamKind == StreamKind.SERIES) {
                    repository.observeSeries(categoryId).map { list ->
                        list.map { series ->
                            VodItem(
                                id = series.seriesId,
                                playlistId = series.playlistId,
                                title = series.name,
                                subtitle = series.year,
                                posterUrl = series.posterUrl,
                            )
                        }
                    }
                } else {
                    repository.observeMovies(categoryId).map { list ->
                        list.map { movie ->
                            VodItem(
                                id = movie.streamId,
                                playlistId = movie.playlistId,
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

    val uiState: StateFlow<VodUiState> =
        combine(categories, items, selectedCategoryId, kind) { categoryList, itemList, categoryId, streamKind ->
            VodUiState(
                categories = categoryList,
                selectedCategoryId = categoryId,
                items = itemList,
                kind = streamKind,
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
}
