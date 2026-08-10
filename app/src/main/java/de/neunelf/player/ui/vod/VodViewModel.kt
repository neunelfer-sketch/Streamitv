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
    /** Fortschritt 0f..1f für den Balken unten am Poster; null = kein Fortsetzpunkt. */
    val progress: Float? = null,
    /**
     * Nur bei "Zuletzt gesehen"-Serien gesetzt: die zuletzt geschaute Folge.
     * Ein Klick springt dann direkt in die Wiedergabe statt in die
     * Staffelübersicht – der Zuschauer muss sich Staffel und Folge nicht merken.
     */
    val resumeEpisodeId: String? = null,
)

/** Synthetische Kategorie, immer an erster Stelle – siehe [VodViewModel]. */
const val RECENT_CATEGORY_ID = "__recent__"

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

    // Startet direkt auf "Zuletzt gesehen" – genau das wollte der Nutzer:
    // ohne Umweg über eine Kategorie zu dem springen, was zuletzt lief.
    private val selectedCategoryId = MutableStateFlow<String?>(RECENT_CATEGORY_ID)

    private val realCategories: StateFlow<List<Category>> = kind
        .flatMapLatest { repository.observeCategories(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Immer ganz oben, unabhängig vom Bereich – siehe [RECENT_CATEGORY_ID]. */
    private val categories: StateFlow<List<Category>> = combine(kind, realCategories) { streamKind, real ->
        val recentCategory = Category(
            id = RECENT_CATEGORY_ID,
            name = "Zuletzt gesehen",
            kind = streamKind,
            playlistId = 0L,
        )
        listOf(recentCategory) + real
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Zuletzt gesehene Filme bzw. Folgen, umgesetzt in Poster-Einträge mit Fortschritt. */
    private val recentItems: StateFlow<List<VodItem>> = kind
        .flatMapLatest { streamKind ->
            if (streamKind == StreamKind.SERIES) {
                repository.observeRecentEpisodes().map { rows ->
                    // Je Serie nur die zuletzt geschaute Folge – die Liste ist
                    // bereits nach watchedAt absteigend sortiert.
                    rows.distinctBy { it.seriesId }.map { row ->
                        VodItem(
                            id = row.seriesId,
                            title = row.seriesName,
                            subtitle = "S%02dE%02d".format(row.season, row.episodeNumber),
                            posterUrl = row.posterUrl,
                            progress = progressOf(row.positionMs, row.durationMs),
                            resumeEpisodeId = row.episodeId,
                        )
                    }
                }
            } else {
                repository.observeRecentMovies().map { rows ->
                    rows.map { row ->
                        VodItem(
                            id = row.streamId,
                            title = row.name,
                            subtitle = null,
                            posterUrl = row.posterUrl,
                            progress = progressOf(row.positionMs, row.durationMs),
                        )
                    }
                }
            }
        }
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
                if (categoryId == RECENT_CATEGORY_ID) {
                    // "Neu hinzugefügt" ergibt hier keinen Sinn – die Liste ist
                    // schon nach zuletzt geschaut sortiert, das ist ihr Zweck.
                    // A-Z/Z-A gilt aber auch hier: der Drei-Punkte-Knopf soll
                    // nicht ausgerechnet auf dem Bildschirm wirkungslos sein,
                    // der beim Öffnen von Filme/Serien zuerst zu sehen ist.
                    recentItems.map { list ->
                        when (order) {
                            VodSort.RECENT -> list
                            VodSort.NAME_ASC ->
                                list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                            VodSort.NAME_DESC ->
                                list.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
                        }
                    }
                } else if (streamKind == StreamKind.SERIES) {
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
            // Kategorie-Auswahl gilt nicht über Bereiche hinweg – zurück auf
            // "Zuletzt gesehen", ganz oben.
            selectedCategoryId.value = RECENT_CATEGORY_ID
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

    /**
     * Fortschritt für den Balken am Poster, 0f..1f.
     *
     * Nahe am Ende (>95 %) wird als "fertig geschaut" wie ein frischer Start
     * behandelt (kein Balken) – sonst bliebe ein durchgeschauter Titel für
     * immer mit vollem Balken in "Zuletzt gesehen" stehen.
     */
    private fun progressOf(positionMs: Long, durationMs: Long): Float? {
        if (durationMs <= 0L) return null
        val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        return fraction.takeIf { it in 0.01f..0.95f }
    }
}
