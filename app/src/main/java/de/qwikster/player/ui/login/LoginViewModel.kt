package de.qwikster.player.ui.login

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.R
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.model.PlaylistType
import de.qwikster.player.data.remote.xtream.XtreamApi
import de.qwikster.player.data.remote.xtream.XtreamCredentials
import de.qwikster.player.data.remote.xtream.XtreamException
import de.qwikster.player.data.repository.IptvRepository
import de.qwikster.player.data.repository.PlaylistSyncer
import de.qwikster.player.data.repository.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val type: PlaylistType = PlaylistType.XTREAM,
    /** Wird vom ViewModel mit dem übersetzten Vorschlagsnamen belegt. */
    val name: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    /** Kein Fehler, aber erwähnenswert – etwa der Umweg über den M3U-Export. */
    val noticeMessage: String? = null,
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
    @ApplicationContext private val context: Context,
    private val repository: IptvRepository,
    private val syncer: PlaylistSyncer,
    private val xtreamApi: XtreamApi,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LoginUiState(name = context.getString(R.string.playlist_default_name)),
    )
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
            _uiState.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    noticeMessage = null,
                    statusMessage = context.getString(R.string.login_connecting),
                )
            }

            try {
                // Kann sich unterwegs noch ändern: Blockt der Anbieter die
                // Panel-Schnittstelle, wird daraus ein M3U-Zugang.
                var type = state.type
                var m3uUrl = state.m3uUrl.trim()
                var epgUrl = state.epgUrl.trim()

                if (state.type == PlaylistType.XTREAM) {
                    val credentials = XtreamCredentials(
                        baseUrl = state.serverUrl,
                        username = state.username,
                        password = state.password,
                    )

                    val auth = try {
                        xtreamApi.authenticate(credentials)
                    } catch (e: XtreamException.Http) {
                        // Etliche Anbieter sperren `player_api.php` und
                        // antworten mit 403 oder einem Code, den es im
                        // HTTP-Standard gar nicht gibt (z. B. 884), lassen den
                        // klassischen `get.php`-Export aber offen. Dann ist
                        // der Zugang völlig in Ordnung und die App richtet ihn
                        // über diesen Weg ein, statt den Zuschauer vor einer
                        // Fehlernummer stehen zu lassen.
                        if (!e.isBlocked || !xtreamApi.m3uExportAvailable(credentials)) throw e
                        type = PlaylistType.M3U
                        m3uUrl = xtreamApi.buildM3uUrl(credentials)
                        if (epgUrl.isBlank()) epgUrl = xtreamApi.buildXmltvUrl(credentials)
                        _uiState.update {
                            it.copy(
                                noticeMessage = context.getString(
                                    R.string.login_api_blocked_fallback,
                                    e.code,
                                ),
                            )
                        }
                        null
                    }

                    val info = auth?.userInfo

                    // Abgelaufene Abos melden `auth = 1`, liefern aber keine
                    // Streams – deshalb hier explizit warnen.
                    if (info != null && info.status.equals("Expired", ignoreCase = true)) {
                        _uiState.update {
                            it.copy(
                                isBusy = false,
                                errorMessage = context.getString(R.string.login_subscription_expired),
                            )
                        }
                        return@launch
                    }
                }

                val playlist = Playlist(
                    name = state.name.ifBlank {
                        context.getString(R.string.playlist_default_name)
                    },
                    type = type,
                    // Server und Zugangsdaten bleiben auch beim Umweg über den
                    // M3U-Export gespeichert: Sie kosten nichts und ersparen
                    // ein erneutes Eintippen, falls der Anbieter die
                    // Schnittstelle später wieder öffnet.
                    serverUrl = state.serverUrl.trim(),
                    username = state.username.trim(),
                    password = state.password.trim(),
                    m3uUrl = m3uUrl,
                    epgUrl = epgUrl,
                )

                val id = repository.savePlaylist(playlist)
                runInitialSync(playlist.copy(id = id))
            } catch (e: XtreamException.Auth) {
                _uiState.update { it.copy(isBusy = false, errorMessage = e.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        errorMessage = e.message
                            ?: context.getString(R.string.error_connection_failed),
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
