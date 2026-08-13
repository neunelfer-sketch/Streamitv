package de.qwikster.player.ui.vod

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.R
import de.qwikster.player.data.model.Category
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.model.VodSort
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.repository.IptvRepository
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

/**
 * Startansicht mit waagerechten Reihen – die erste Kategorie und der
 * Einstieg beim Öffnen von "Filme" bzw. "Serien".
 *
 * Sie zeigt nicht *eine* Kategorie, sondern einen Querschnitt: oben das
 * Angefangene, darunter Neuzugänge, darunter je Kategorie eine Reihe. Genau
 * so steigen Netflix und Disney+ ein, und aus gutem Grund: Ein Raster
 * verlangt, dass man vorher weiß, wonach man sucht. Reihen bieten etwas an.
 */
const val OVERVIEW_CATEGORY_ID = "__overview__"

/**
 * Der gesamte Bestand in einem Raster, ohne Kategorie-Einschränkung.
 *
 * Bis hierher gab es das zwar (eine leere Auswahl zeigte alles), aber
 * keinen Eintrag dafür – erreichbar war es nur, indem man keine Kategorie
 * anwählte, und darauf kommt niemand. Jetzt steht es sichtbar ganz oben.
 */
const val ALL_CATEGORY_ID = "__all__"

/** Eine waagerechte Reihe der Startansicht. */
data class VodRow(
    val id: String,
    val title: String,
    val items: List<VodItem>,
)

/**
 * Kategorien mit Buchstaben zuerst, mit Ziffern beginnende danach.
 *
 * Panels mischen beides oft wild durcheinander (z. B. "18+", "24/7" oder
 * "4K" zwischen den eigentlichen Genre-Kategorien) – ein reiner
 * Zeichenvergleich stellte sie außerdem vor "A", weil Ziffern in der
 * Unicode-Reihenfolge vor Buchstaben liegen. So bleibt die Liste innerhalb
 * jeder der beiden Gruppen alphabetisch, aber die Ziffern-Kategorien
 * stehen geschlossen unten.
 */
private val CATEGORY_ORDER = compareBy<Category>(
    { if (it.name.firstOrNull()?.isDigit() == true) 1 else 0 },
    { it.name.lowercase() },
)

data class VodUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<VodItem> = emptyList(),
    /** Nur in der Startansicht gefüllt – siehe [OVERVIEW_CATEGORY_ID]. */
    val rows: List<VodRow> = emptyList(),
    val kind: StreamKind = StreamKind.VOD,
    val sort: VodSort = VodSort.RECENT,
) {
    /** Zeigt der Bildschirm gerade Reihen statt eines Rasters? */
    val isOverview: Boolean get() = selectedCategoryId == OVERVIEW_CATEGORY_ID
}

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
    @ApplicationContext private val context: Context,
    private val repository: IptvRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val kind = MutableStateFlow(StreamKind.VOD)

    // Startet auf der Reihenansicht. Deren erste Reihe ist das Angefangene,
    // die frühere Startkategorie "Zuletzt gesehen" steht damit weiterhin
    // ganz oben – nur eben neben Neuzugängen und Kategorien statt allein.
    private val selectedCategoryId = MutableStateFlow<String?>(OVERVIEW_CATEGORY_ID)

    /** Vom Zuschauer ausgeblendete Kategorien des gerade gezeigten Bereichs. */
    private val hiddenCategories: StateFlow<Set<String>> =
        combine(kind, settingsStore.settings) { streamKind, settings ->
            settings.hiddenCategories(streamKind)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val realCategories: StateFlow<List<Category>> = kind
        .flatMapLatest { repository.observeCategories(it) }
        .combine(hiddenCategories) { list, hidden -> list.filterNot { it.id in hidden } }
        .map { list -> list.sortedWith(CATEGORY_ORDER) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Genau wie bei den Live-TV-Kategorien (siehe
     * [de.qwikster.player.ui.home.HomeViewModel]): eine leere
     * Spezialkategorie wird ausgeblendet, statt die Liste bei einer
     * frischen Installation halb tot wirken zu lassen.
     */
    private val hasRecentItems: StateFlow<Boolean> = recentItems
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Die Startansicht steht immer ganz oben, "Zuletzt gesehen" nur, wenn es
     * dort tatsächlich etwas gibt – eine leere Spezialkategorie ließe die
     * Liste bei einer frischen Installation halb tot wirken.
     */
    private val categories: StateFlow<List<Category>> =
        combine(kind, realCategories, hasRecentItems) { streamKind, real, hasRecent ->
            fun synthetic(id: String, nameRes: Int) = Category(
                id = id,
                name = context.getString(nameRes),
                kind = streamKind,
                playlistId = 0L,
            )

            buildList {
                add(synthetic(ALL_CATEGORY_ID, R.string.category_all_titles))
                add(synthetic(OVERVIEW_CATEGORY_ID, R.string.category_overview))
                if (hasRecent) add(synthetic(RECENT_CATEGORY_ID, R.string.category_recent))
                addAll(real)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Die Eingaben der Startansicht – `Triple` reicht dafür nicht mehr. */
    private data class OverviewInput(
        val kind: StreamKind,
        val categories: List<Category>,
        val recent: List<VodItem>,
        val hidden: Set<String>,
    )

    /**
     * Die Reihen der Startansicht.
     *
     * **Jede Reihe holt sich ihre zwanzig Poster selbst aus der Datenbank.**
     * Das klingt nach mehr Arbeit als eine einzige große Abfrage, ist aber
     * das Gegenteil: Vorher wurde der gesamte Bestand geladen, im Speicher
     * sortiert, gruppiert und dann auf zwanzig je Reihe beschnitten. Bei
     * einem Panel mit 178.000 Filmen entstand so eine Viertelmillion
     * Objekte, um am Ende ein paar hundert Poster zu zeigen – und zwar bei
     * jeder Aktualisierung aufs Neue. Auf einem Fire TV Stick ist das der
     * Weg in dauernde Speicherbereinigungen und irgendwann in den Abbruch
     * wegen Speichermangels.
     *
     * Jetzt liest die Datenbank je Reihe genau zwanzig Zeilen, getragen vom
     * Index auf `(playlistId, categoryId)`.
     *
     * Die Zahl der Kategoriereihen ist auf [OVERVIEW_ROW_LIMIT] begrenzt:
     * Jede Reihe ist eine eigene offene Abfrage, und niemand blättert sich
     * mit dem Steuerkreuz durch hundert Reihen. Die übrigen Kategorien
     * bleiben über die Liste links vollständig erreichbar.
     */
    private val overviewRows: StateFlow<List<VodRow>> =
        combine(kind, realCategories, recentItems, hiddenCategories, ::OverviewInput)
            .flatMapLatest { (streamKind, cats, recent, hidden) ->
                val isSeries = streamKind == StreamKind.SERIES
                val shownCategories = cats.take(OVERVIEW_ROW_LIMIT)

                // "Neu hinzugefügt" geht quer durch alle Kategorien, kennt
                // also auch die ausgeblendeten. Deshalb wird großzügiger
                // gelesen und erst danach aussortiert – sonst bliebe die
                // Reihe bei vielen ausgeblendeten Kategorien halb leer.
                // Ein paar hundert Zeilen sind dafür ein billiger Preis.
                val newestFlow = if (isSeries) {
                    repository.observeNewestSeries(NEWEST_FETCH_LIMIT).map { list ->
                        list.withoutHidden(hidden) { it.categoryId }
                            .take(ROW_ITEM_LIMIT)
                            .map { series -> toItem(series) }
                    }
                } else {
                    repository.observeNewestMovies(NEWEST_FETCH_LIMIT).map { list ->
                        list.withoutHidden(hidden) { it.categoryId }
                            .take(ROW_ITEM_LIMIT)
                            .map { movie -> toItem(movie) }
                    }
                }

                val categoryFlows = shownCategories.map { category ->
                    if (isSeries) {
                        repository.observeCategorySeries(category.id, ROW_ITEM_LIMIT)
                            .map { list -> list.map { series -> toItem(series) } }
                    } else {
                        repository.observeCategoryMovies(category.id, ROW_ITEM_LIMIT)
                            .map { list -> list.map { movie -> toItem(movie) } }
                    }
                }

                combine(listOf(newestFlow) + categoryFlows) { results ->
                    buildList {
                        // 1. Angefangenes zuerst. Wer etwas offen hat, will fast
                        //    immer genau dort weitermachen – das gehört nicht in
                        //    eine Kategorie weiter unten, sondern nach ganz oben.
                        if (recent.isNotEmpty()) {
                            add(
                                VodRow(
                                    id = RECENT_CATEGORY_ID,
                                    title = context.getString(R.string.category_continue_watching),
                                    items = recent.take(ROW_ITEM_LIMIT),
                                ),
                            )
                        }

                        // 2. Neuzugänge über alle Kategorien hinweg.
                        results.first().takeIf { it.isNotEmpty() }?.let { newest ->
                            add(
                                VodRow(
                                    id = "__new__",
                                    title = context.getString(R.string.category_recently_added),
                                    items = newest,
                                ),
                            )
                        }

                        // 3. Je Kategorie eine Reihe, in derselben Reihenfolge wie
                        //    die Liste links – sonst suchte man eine Kategorie an
                        //    zwei Stellen an verschiedenen Positionen.
                        shownCategories.forEachIndexed { index, category ->
                            val items = results[index + 1]
                            if (items.isNotEmpty()) {
                                add(
                                    VodRow(
                                        id = category.id,
                                        title = category.name,
                                        items = items,
                                    ),
                                )
                            }
                        }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun toItem(movie: de.qwikster.player.data.model.Movie) = VodItem(
        id = movie.streamId,
        title = movie.name,
        subtitle = movie.year,
        posterUrl = movie.posterUrl,
    )

    private fun toItem(series: de.qwikster.player.data.model.Series) = VodItem(
        id = series.seriesId,
        title = series.name,
        subtitle = series.year,
        posterUrl = series.posterUrl,
    )

    /**
     * Die tatsächlich wirksame Auswahl: Fällt sie auf "Zuletzt gesehen",
     * ohne dass es dort etwas gibt, wird sie wie zuvor als "keine bestimmte
     * Kategorie" behandelt (zeigt alles), statt auf eine ausgeblendete
     * Kategorie zu zeigen, die niemand anwählen kann.
     */
    private val effectiveCategoryId: StateFlow<String?> =
        combine(selectedCategoryId, hasRecentItems) { selected, hasRecent ->
            if (selected == RECENT_CATEGORY_ID && !hasRecent) OVERVIEW_CATEGORY_ID else selected
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OVERVIEW_CATEGORY_ID)

    /** Die gemerkte Reihenfolge des gerade gezeigten Bereichs. */
    private val sort: StateFlow<VodSort> =
        combine(kind, settingsStore.settings) { streamKind, settings ->
            if (streamKind == StreamKind.SERIES) settings.seriesSort else settings.movieSort
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodSort.RECENT)

    /** Die vier Eingaben des Rasters. */
    private data class GridInput(
        val kind: StreamKind,
        val categoryId: String?,
        val sort: VodSort,
        val hidden: Set<String>,
    )

    private val items: StateFlow<List<VodItem>> =
        combine(kind, effectiveCategoryId, sort, hiddenCategories, ::GridInput)
            .flatMapLatest { (streamKind, categoryId, order, hidden) ->
                if (categoryId == OVERVIEW_CATEGORY_ID) {
                    // Die Startansicht zeigt Reihen, kein Raster – siehe
                    // [overviewRows]. Hier gäbe es nichts zu laden.
                    kotlinx.coroutines.flow.flowOf(emptyList())
                } else if (categoryId == RECENT_CATEGORY_ID) {
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
                    // "Alle Titel" heißt für die Datenbank: keine Einschränkung.
                    repository.observeSeries(categoryId.takeUnless { it == ALL_CATEGORY_ID }).map { list ->
                        list.withoutHidden(hidden) { it.categoryId }
                            .sortedFor(order, recentKey = { it.lastModified }, name = { it.name })
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
                    repository.observeMovies(categoryId.takeUnless { it == ALL_CATEGORY_ID }).map { list ->
                        list.withoutHidden(hidden) { it.categoryId }
                            .sortedFor(order, recentKey = { it.addedAt }, name = { it.name })
                            .map { movie ->
                                VodItem(
                                    id = movie.streamId,
                                    title = movie.name,
                                    subtitle = movie.year
                                        ?: movie.rating.takeIf { it > 0 }?.let {
                                            // Die Zahl wird hier formatiert, die
                                            // Ressource trägt nur das Sternsymbol –
                                            // "%.1f" liesse sich in XML nicht sauber
                                            // von einem Platzhalter unterscheiden.
                                            context.getString(
                                                R.string.rating_stars,
                                                "%.1f".format(it),
                                            )
                                        },
                                    posterUrl = movie.posterUrl,
                                )
                            }
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val gridState = combine(categories, items, effectiveCategoryId) { categoryList, itemList, categoryId ->
        Triple(categoryList, itemList, categoryId)
    }

    val uiState: StateFlow<VodUiState> = combine(
        gridState,
        overviewRows,
        kind,
        sort,
    ) { (categoryList, itemList, categoryId), rowList, streamKind, order ->
        VodUiState(
            categories = categoryList,
            selectedCategoryId = categoryId,
            items = itemList,
            rows = if (categoryId == OVERVIEW_CATEGORY_ID) rowList else emptyList(),
            kind = streamKind,
            sort = order,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState())

    fun setKind(value: StreamKind) {
        if (kind.value != value) {
            kind.value = value
            // Kategorie-Auswahl gilt nicht über Bereiche hinweg – zurück auf
            // die Startansicht, ganz oben.
            selectedCategoryId.value = OVERVIEW_CATEGORY_ID
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
    /**
     * Wirft die Einträge ausgeblendeter Kategorien weg.
     *
     * Die Abkürzung bei leerer Menge ist kein Geiz: Im Raster "Alle Titel"
     * geht das hier über den gesamten Bestand, und der ist bei manchen
     * Panels sechsstellig. Wer nichts ausgeblendet hat, soll dafür auch
     * nichts bezahlen.
     */
    private inline fun <T> List<T>.withoutHidden(
        hidden: Set<String>,
        crossinline categoryId: (T) -> String?,
    ): List<T> = if (hidden.isEmpty()) this else filterNot { categoryId(it) in hidden }

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

    private companion object {
        /**
         * Höchstzahl Poster je Reihe der Startansicht.
         *
         * Wer weiter will, wählt die Kategorie links an und bekommt das
         * vollständige Raster. Eine Reihe mit tausenden Einträgen wäre mit
         * dem Steuerkreuz ohnehin nicht zu durchqueren.
         */
        const val ROW_ITEM_LIMIT = 20

        /**
         * Höchstzahl Kategoriereihen in der Startansicht.
         *
         * Jede Reihe hält eine eigene offene Datenbankabfrage. Bei Panels
         * mit dreistellig vielen Kategorien wären das ebenso viele – und
         * niemand blättert sich mit dem Steuerkreuz durch hundert Reihen.
         * Die übrigen Kategorien bleiben über die Liste links vollständig
         * erreichbar.
         */
        const val OVERVIEW_ROW_LIMIT = 20

        /**
         * Wie viele Neuzugänge gelesen werden, bevor ausgeblendete
         * Kategorien aussortiert werden. Großzügig, damit die Reihe auch
         * dann voll wird, wenn der Zuschauer das meiste ausgeblendet hat.
         */
        const val NEWEST_FETCH_LIMIT = 300
    }
}
