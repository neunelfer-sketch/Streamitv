package de.qwikster.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.qwikster.player.data.model.Category
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.repository.IptvRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryVisibilityUiState(
    val kind: StreamKind = StreamKind.LIVE,
    val categories: List<Category> = emptyList(),
    /** IDs der ausgeblendeten Kategorien des gerade gezeigten Bereichs. */
    val hidden: Set<String> = emptySet(),
) {
    fun isVisible(categoryId: String): Boolean = categoryId !in hidden

    val visibleCount: Int get() = categories.count { it.id !in hidden }
}

/**
 * Sichtbarkeit der Kategorien – je Bereich getrennt.
 *
 * Panels liefern regelmäßig hunderte Kategorien, davon der weitaus größte
 * Teil in Sprachen, die den Zuschauer nichts angehen. Wer nur deutsche und
 * englische Inhalte sehen will, blättert sonst bei jedem Aufruf an dutzenden
 * fremdsprachigen Reitern vorbei.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryVisibilityViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val kind = MutableStateFlow(StreamKind.LIVE)

    val uiState: StateFlow<CategoryVisibilityUiState> = kind
        .flatMapLatest { streamKind ->
            combine(
                repository.observeCategories(streamKind),
                settingsStore.settings,
            ) { categories, settings ->
                CategoryVisibilityUiState(
                    kind = streamKind,
                    categories = categories,
                    hidden = settings.hiddenCategories(streamKind),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryVisibilityUiState())

    fun selectKind(value: StreamKind) {
        kind.value = value
    }

    fun toggle(categoryId: String) {
        viewModelScope.launch {
            val current = kind.value
            val hidden = settingsStore.settings.first().hiddenCategories(current)
            val next = if (categoryId in hidden) hidden - categoryId else hidden + categoryId
            settingsStore.setHiddenCategories(current, next)
        }
    }

    fun showAll() {
        viewModelScope.launch { settingsStore.setHiddenCategories(kind.value, emptySet()) }
    }

    /**
     * Blendet alles aus – gedacht als Startpunkt: erst alles weg, dann die
     * Handvoll Kategorien wieder anschalten, die man wirklich sehen will.
     * Bei dreistelligen Kategorienzahlen ist das der kürzere Weg.
     */
    fun hideAll() {
        viewModelScope.launch {
            val ids = uiState.value.categories.map { it.id }.toSet()
            settingsStore.setHiddenCategories(kind.value, ids)
        }
    }
}
