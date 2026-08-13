package de.qwikster.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.repository.IptvRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val activeId: Long? = null,
)

/**
 * Verwaltung mehrerer hinterlegter Playlists.
 *
 * Das Umschalten lädt bewusst **nichts** neu: Jede Tabelle führt ihre
 * `playlistId` mit, die Inhalte aller Playlists liegen also nebeneinander in
 * der Datenbank und bleiben beim Wechsel unangetastet. Der Wechsel ändert
 * nur, worauf die Abfragen zeigen. Ob die neu gewählte Playlist eine
 * Auffrischung braucht, entscheidet danach der Hauptbildschirm anhand ihres
 * eigenen Standes.
 */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: IptvRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaylistsUiState> = combine(
        repository.observePlaylists(),
        repository.observeActivePlaylist().map { it?.id },
    ) { playlists, activeId ->
        PlaylistsUiState(playlists = playlists, activeId = activeId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    fun select(playlist: Playlist) {
        if (playlist.id == uiState.value.activeId) return
        viewModelScope.launch { repository.setActivePlaylist(playlist.id) }
    }

    fun remove(playlist: Playlist) {
        viewModelScope.launch { repository.deletePlaylist(playlist.id) }
    }
}
