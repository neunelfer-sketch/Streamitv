package de.neunelf.player.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.neunelf.player.data.model.Playlist
import de.neunelf.player.data.model.PlaylistType
import de.neunelf.player.data.remote.xtream.XtreamApi
import de.neunelf.player.data.remote.xtream.XtreamCredentials
import de.neunelf.player.data.remote.xtream.XtreamException
import de.neunelf.player.data.repository.IptvRepository
import de.neunelf.player.data.repository.PlaylistSyncer
import de.neunelf.player.data.repository.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val type: PlaylistType = PlaylistType.XTREAM,
    val name: String = "Meine Playlist",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val isDone: Boolean = false,
) {
    /** Erst wenn die Pflichtfelder gefüllt sind, ist "Verbinden" sinnvoll. */
    val canSubmit: Boolean
        get() = when (type) {
            PlaylistType.XTREAM ->
                serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

            PlaylistType.M3U -> m3uUrl.isNotBlank()
        } && !isBusy
}

/**
 * Einrichtung einer Playlist.
 *
 * Bei Xtream wird vor dem Speichern eine Testanmeldung gemacht: eine
 * fehlerhafte Eingabe soll sofort auffallen und nicht erst, wenn später
 * die Senderliste leer bleibt.
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: IptvRepository,
    private val syncer: PlaylistSyncer,
    private val xtreamApi: XtreamApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun setType(type: PlaylistType) = _uiState.update { it.copy(type = type, errorMessage = null) }
    fun setName(value: String) = _uiState.update { it.copy(name = value) }
    fun setServerUrl(value: String) = _uiState.update { it.copy(serverUrl = value) }
    fun setUsername(value: String) = _uiState.update { it.copy(username = value) }
    fun setPassword(value: String) = _uiState.update { it.copy(password = value) }
    fun setM3uUrl(value: String) = _uiState.update { it.copy(m3uUrl = value) }
    fun setEpgUrl(value: String) = _uiState.update { it.copy(epgUrl = value) }

    /** Prüft die Eingaben, speichert die Playlist und startet den Erstimport. */
    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, statusMessage = "Verbinde…") }

            try {
                if (state.type == PlaylistType.XTREAM) {
                    val credentials = XtreamCredentials(
                        baseUrl = state.serverUrl,
                        username = state.username,
                        password = state.password,
                    )
                    val auth = xtreamApi.authenticate(credentials)
                    val info = auth.userInfo

                    // Abgelaufene Abos melden `auth = 1`, liefern aber keine
                    // Streams – deshalb hier explizit warnen.
                    if (info != null && info.status.equals("Expired", ignoreCase = true)) {
                        _uiState.update {
                            it.copy(isBusy = false, errorMessage = "Das Abonnement ist abgelaufen")
                        }
                        return@launch
                    }
                }

                val playlist = Playlist(
                    name = state.name.ifBlank { "Playlist" },
                    type = state.type,
                    serverUrl = state.serverUrl.trim(),
                    username = state.username.trim(),
                    password = state.password.trim(),
                    m3uUrl = state.m3uUrl.trim(),
                    epgUrl = state.epgUrl.trim(),
                )

                val id = repository.savePlaylist(playlist)
                runInitialSync(playlist.copy(id = id))
            } catch (e: XtreamException.Auth) {
                _uiState.update { it.copy(isBusy = false, errorMessage = e.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = e.message ?: "Verbindung fehlgeschlagen",
                    )
                }
            }
        }
    }

    /**
     * Läuft in [PlaylistSyncer.syncInBackground], nicht im eigenen
     * `viewModelScope`: Sobald die Live-Sender da sind, wechselt die App
     * sofort zum Hauptbildschirm (nicht erst, wenn auch Filme und Serien
     * fertig sind) – dieses ViewModel wird dabei zerstört. Liefe der Import
     * im eigenen Scope, würde genau das ihn mitten drin abbrechen.
     */
    private suspend fun runInitialSync(playlist: Playlist) {
        syncer.syncInBackground(playlist).collect { progress ->
            when (progress) {
                is SyncProgress.Step ->
                    _uiState.update { it.copy(statusMessage = progress.message) }

                is SyncProgress.LiveReady ->
                    _uiState.update {
                        it.copy(isBusy = false, statusMessage = null, isDone = true)
                    }

                is SyncProgress.Done -> Unit // Bereits auf dem Hauptbildschirm, nichts mehr zu tun.

                is SyncProgress.Failed ->
                    _uiState.update {
                        it.copy(isBusy = false, statusMessage = null, errorMessage = progress.message)
                    }
            }
        }
    }
}
