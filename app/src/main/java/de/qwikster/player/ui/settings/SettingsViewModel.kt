package de.qwikster.player.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.R
import de.qwikster.player.core.TimeFormat
import de.qwikster.player.data.model.AspectRatioMode
import de.qwikster.player.data.prefs.AppLanguage
import de.qwikster.player.data.prefs.AppSettings
import de.qwikster.player.data.prefs.LanguageStore
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.repository.DownloadProgress
import de.qwikster.player.data.repository.EpgSyncProgress
import de.qwikster.player.data.repository.EpgRepository
import de.qwikster.player.data.repository.IptvRepository
import de.qwikster.player.data.repository.PlaylistSyncer
import de.qwikster.player.data.repository.SyncProgress
import de.qwikster.player.data.repository.UpdateInfo
import de.qwikster.player.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Stand der eingebauten Aktualisierung. */
sealed interface UpdateUiState {
    data object Unknown : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val file: File, val versionName: String) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val playlistName: String? = null,
    /** Leer, solange der erste Wert aus der Datenbank aussteht – die
     *  Oberfläche setzt dafür "Nie" ein (siehe SettingsScreen). */
    val lastSyncLabel: String = "",
    val lastEpgSyncLabel: String = "",
    val programCount: Int = 0,
    val message: String? = null,
    val currentVersion: String = "",
    val update: UpdateUiState = UpdateUiState.Unknown,
    /** Vom Nutzer hinterlegte XMLTV-Adresse; leer = automatisch ermitteln. */
    val epgUrl: String = "",
    val language: AppLanguage = AppLanguage.SYSTEM,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: IptvRepository,
    private val epgRepository: EpgRepository,
    private val syncer: PlaylistSyncer,
    private val settingsStore: SettingsStore,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)
    private val programCount = MutableStateFlow(0)
    private val update = MutableStateFlow<UpdateUiState>(UpdateUiState.Unknown)

    // Nur der aktuelle Stand für die Anzeige – gehalten wird die Wahl in
    // [LanguageStore].
    private val language = MutableStateFlow(currentAppLanguage(context))

    private val baseState: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        repository.observeActivePlaylist(),
        message,
        programCount,
        update,
    ) { settings, playlist, statusMessage, count, updateState ->
        SettingsUiState(
            settings = settings,
            playlistName = playlist?.name,
            lastSyncLabel = playlist?.lastSyncAt.toLabel(),
            lastEpgSyncLabel = playlist?.lastEpgSyncAt.toLabel(),
            programCount = count,
            message = statusMessage,
            currentVersion = updateRepository.currentVersion,
            update = updateState,
            epgUrl = playlist?.epgUrl.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = combine(baseState, language) { base, currentLanguage ->
        base.copy(language = currentLanguage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch {
            repository.getActivePlaylist()?.let {
                programCount.value = epgRepository.programCount(it.id)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Aktualisierung der App
    // -----------------------------------------------------------------------

    /**
     * Eine Taste für den ganzen Ablauf: prüfen, laden, installieren.
     *
     * Auf einer Fernbedienung ist das angenehmer als drei getrennte
     * Einträge – der Nutzer drückt schlicht so lange OK, bis die neue
     * Fassung läuft, und der Text darunter sagt, was gerade passiert.
     */
    fun onUpdateRowClick() {
        when (val state = update.value) {
            is UpdateUiState.Available -> downloadUpdate(state.info)
            is UpdateUiState.ReadyToInstall -> installUpdate(state.file)
            // Während Prüfung und Download passiert auf Tastendruck nichts.
            UpdateUiState.Checking, is UpdateUiState.Downloading -> Unit
            else -> checkForUpdate()
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            update.value = UpdateUiState.Checking
            update.value = runCatching { updateRepository.check() }
                .fold(
                    onSuccess = { info ->
                        if (info == null) UpdateUiState.UpToDate else UpdateUiState.Available(info)
                    },
                    onFailure = {
                        UpdateUiState.Failed(
                            it.message ?: context.getString(R.string.update_check_failed),
                        )
                    },
                )
        }
    }

    private fun downloadUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            updateRepository.cleanUp()
            updateRepository.download(info).collect { progress ->
                update.value = when (progress) {
                    is DownloadProgress.Running -> UpdateUiState.Downloading(progress.percent)
                    is DownloadProgress.Finished ->
                        UpdateUiState.ReadyToInstall(progress.file, info.versionName)

                    is DownloadProgress.Failed -> UpdateUiState.Failed(progress.message)
                }
            }
        }
    }

    private fun installUpdate(file: File) {
        // Fehlt die Erlaubnis, öffnet das Repository die Systemeinstellung;
        // der Hinweis erklärt, warum gerade nichts installiert wurde.
        val started = updateRepository.install(file)
        if (!started) {
            message.value = context.getString(R.string.update_install_permission)
        }
    }

    // -----------------------------------------------------------------------
    // Playlist
    // -----------------------------------------------------------------------

    fun refreshPlaylist() {
        viewModelScope.launch {
            val playlist = repository.getActivePlaylist() ?: return@launch
            syncer.sync(playlist).collect { progress ->
                message.value = when (progress) {
                    is SyncProgress.Step -> progress.message
                    is SyncProgress.LiveReady ->
                        context.getString(R.string.sync_live_ready, progress.channels)

                    is SyncProgress.Done ->
                        context.getString(R.string.sync_channels_updated, progress.channels)

                    is SyncProgress.Failed ->
                        context.getString(R.string.error_with_message, progress.message)
                }
            }
        }
    }

    fun refreshEpg() {
        viewModelScope.launch {
            val playlist = repository.getActivePlaylist() ?: return@launch
            epgRepository.refresh(playlist).collect { progress ->
                message.value = when (progress) {
                    is EpgSyncProgress.Step -> progress.message
                    is EpgSyncProgress.Done ->
                        context.getString(R.string.epg_programs_loaded, progress.programCount)

                    is EpgSyncProgress.Failed ->
                        context.getString(R.string.error_with_message, progress.message)
                }
            }
            programCount.value = epgRepository.programCount(playlist.id)
        }
    }

    /** Setzt die EPG-Quelle; der Import läuft danach von selbst an. */
    fun setEpgUrl(url: String) {
        viewModelScope.launch {
            repository.updateEpgUrl(url)
            message.value = if (url.isBlank()) {
                context.getString(R.string.epg_source_removed)
            } else {
                context.getString(R.string.epg_source_saved)
            }
        }
    }

    fun removePlaylist() {
        viewModelScope.launch {
            repository.getActivePlaylist()?.let { repository.deletePlaylist(it.id) }
        }
    }

    // -----------------------------------------------------------------------
    // Sprache
    // -----------------------------------------------------------------------

    /**
     * Setzt die App-Sprache um.
     *
     * Zwei Schritte, beide nötig: Der Anwendungskontext wird sofort
     * umgestellt (davon leben die Texte aus ViewModels und Repositories),
     * die Oberfläche selbst über das anschließende `recreate()` des
     * Bildschirms – siehe [LanguageStore] für die Begründung, warum das
     * hier von Hand geschieht und nicht über `AppCompatDelegate`.
     */
    fun setLanguage(value: AppLanguage) {
        LanguageStore.setTag(context, value.tag)
        LanguageStore.applyToRunning(context)
        language.value = value
    }

    // -----------------------------------------------------------------------
    // Wiedergabe-Einstellungen
    // -----------------------------------------------------------------------

    /** Schaltet zyklisch durch die Puffer-Voreinstellungen. */
    fun cycleBuffer() {
        viewModelScope.launch {
            val values = SettingsStore.BUFFER_PRESETS.map { it.valueMs }
            val current = settingsStore.settings.first().bufferMs
            val nextIndex = (values.indexOf(current) + 1).takeIf { it in values.indices } ?: 0
            settingsStore.setBufferMs(values[nextIndex])
        }
    }

    fun togglePreferHls() {
        viewModelScope.launch {
            settingsStore.setPreferHls(!settingsStore.settings.first().preferHls)
        }
    }

    fun setAspectRatio(mode: AspectRatioMode) {
        viewModelScope.launch { settingsStore.setAspectRatio(mode) }
    }

    /** Wechselt zwischen den gängigsten Tonspur-Sprachen. */
    fun cycleAudioLanguage() {
        viewModelScope.launch {
            val languages = listOf("deu", "eng", "fra", "ita", "spa", "tur", "")
            val current = settingsStore.settings.first().preferredAudioLanguage
            val nextIndex = (languages.indexOf(current) + 1) % languages.size
            settingsStore.setAudioLanguage(languages[nextIndex])
        }
    }

    fun toggleSubtitles() {
        viewModelScope.launch {
            settingsStore.setSubtitlesEnabled(!settingsStore.settings.first().subtitlesEnabled)
        }
    }

    /**
     * Schaltet die Live-Vorschau im Hauptbildschirm um.
     *
     * Abschaltbar, weil sie eine zweite Verbindung zum Panel braucht – bei
     * Zugängen mit nur einer erlaubten Verbindung stört das die Wiedergabe.
     */
    fun togglePreviewPlayer() {
        viewModelScope.launch {
            settingsStore.setShowPreviewPlayer(!settingsStore.settings.first().showPreviewPlayer)
        }
    }

    /** "Nie" oder "Heute 14:32" / "Mo 05.08. 09:11". */
    private fun Long?.toLabel(): String {
        if (this == null || this == 0L) return context.getString(R.string.settings_sync_never)
        val isToday = TimeFormat.startOfDay(this) == TimeFormat.startOfDay(System.currentTimeMillis())
        return if (isToday) {
            context.getString(R.string.settings_sync_today, TimeFormat.clock(this))
        } else {
            context.getString(
                R.string.settings_sync_datetime,
                TimeFormat.dayShort(this),
                TimeFormat.clock(this),
            )
        }
    }
}

/** Liest die aktuell wirksame Sprachauswahl aus. */
private fun currentAppLanguage(context: Context): AppLanguage {
    val tag = LanguageStore.tag(context)
    return AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
}
