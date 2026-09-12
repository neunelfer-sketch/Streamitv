package de.xott.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.xott.player.data.model.Category
import de.xott.player.data.model.ManagedChannel
import de.xott.player.data.model.StreamKind
import de.xott.player.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelManagerUiState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<ManagedChannel> = emptyList(),
)

/**
 * Zustand der Kanalverwaltung: Sender einer Kategorie ausblenden oder ihre
 * Reihenfolge ändern. Nur für Live TV gedacht – VOD/Serien haben kein
 * vergleichbares "Senderplatz"-Konzept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChannelManagerViewModel @Inject constructor(
    private val repository: IptvRepository,
) : ViewModel() {

    private val explicitCategoryId = MutableStateFlow<String?>(null)

    private val categories: StateFlow<List<Category>> =
        repository.observeCategories(StreamKind.LIVE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Solange der Nutzer nichts gewählt hat, gilt die erste Kategorie. */
    private val effectiveCategoryId: StateFlow<String?> = combine(
        categories,
        explicitCategoryId,
    ) { categoryList, explicit -> explicit ?: categoryList.firstOrNull()?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val channels: StateFlow<List<ManagedChannel>> = effectiveCategoryId
        .flatMapLatest { categoryId -> repository.observeManagedChannels(categoryId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<ChannelManagerUiState> = combine(
        categories,
        effectiveCategoryId,
        channels,
    ) { categoryList, selected, channelList ->
        ChannelManagerUiState(
            categories = categoryList,
            selectedCategoryId = selected,
            channels = channelList,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelManagerUiState())

    fun selectCategory(categoryId: String) {
        explicitCategoryId.value = categoryId
    }

    fun toggleHidden(channel: ManagedChannel) {
        viewModelScope.launch { repository.setChannelHidden(channel.streamId, !channel.isHidden) }
    }

    fun moveUp(channel: ManagedChannel) = move(channel, -1)

    fun moveDown(channel: ManagedChannel) = move(channel, +1)

    private fun move(channel: ManagedChannel, direction: Int) {
        val categoryId = uiState.value.selectedCategoryId
        viewModelScope.launch { repository.moveChannel(categoryId, channel.streamId, direction) }
    }
}
