package de.neunelf.player.data.prefs

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.neunelf.player.R
import de.neunelf.player.data.model.AspectRatioMode
import de.neunelf.player.data.model.Playlist
import de.neunelf.player.data.model.PlaylistType
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.model.VodSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "neunelf_player_settings")

/**
 * Eine auswählbare Puffergröße: Beschriftung als Ressourcen-Kennung,
 * damit sie der jeweils eingestellten Sprache folgt.
 */
data class BufferPreset(
    @StringRes val labelRes: Int,
    val valueMs: Int,
)

/** Nutzereinstellungen der App. */
data class AppSettings(
    /** `.m3u8` statt `.ts` für Live-Streams anfordern. */
    val preferHls: Boolean = false,
    /** Puffergröße in Millisekunden – siehe [BUFFER_PRESETS]. */
    val bufferMs: Int = 15_000,
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    /** Bevorzugte Audiosprache als ISO-639-2-Code, z. B. "deu". */
    val preferredAudioLanguage: String = "deu",
    val preferredSubtitleLanguage: String = "",
    /** Untertitel automatisch einschalten, wenn die Sprache passt. */
    val subtitlesEnabled: Boolean = false,
    /** EPG-Raster: sichtbare Zeitspanne in Minuten (60/90/120/180). */
    val guideWindowMinutes: Int = 120,
    /** Beim Start direkt den zuletzt gesehenen Sender abspielen. */
    val resumeLastChannel: Boolean = true,
    /** Vorschaubild in der Senderliste anzeigen (kostet Bandbreite). */
    val showPreviewPlayer: Boolean = true,
    /** Reihenfolge im Filme-Raster. */
    val movieSort: VodSort = VodSort.RECENT,
    /** Reihenfolge im Serien-Raster. */
    val seriesSort: VodSort = VodSort.NAME_ASC,
)

/**
 * Persistiert die Einstellungen in DataStore.
 *
 * Bewusst kein SharedPreferences: DataStore liefert einen `Flow`, sodass
 * z. B. eine Änderung des Seitenverhältnisses im Schnellmenü sofort beim
 * laufenden Player ankommt.
 */
class SettingsStore(
    private val context: Context,
) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            preferHls = prefs[KEY_PREFER_HLS] ?: false,
            bufferMs = prefs[KEY_BUFFER_MS] ?: 15_000,
            aspectRatio = prefs[KEY_ASPECT_RATIO]
                ?.let { name -> runCatching { AspectRatioMode.valueOf(name) }.getOrNull() }
                ?: AspectRatioMode.FIT,
            preferredAudioLanguage = prefs[KEY_AUDIO_LANG] ?: "deu",
            preferredSubtitleLanguage = prefs[KEY_SUBTITLE_LANG] ?: "",
            subtitlesEnabled = prefs[KEY_SUBTITLES_ON] ?: false,
            guideWindowMinutes = prefs[KEY_GUIDE_WINDOW] ?: 120,
            resumeLastChannel = prefs[KEY_RESUME_LAST] ?: true,
            showPreviewPlayer = prefs[KEY_SHOW_PREVIEW] ?: true,
            movieSort = prefs[KEY_MOVIE_SORT].toVodSort(VodSort.RECENT),
            seriesSort = prefs[KEY_SERIES_SORT].toVodSort(VodSort.NAME_ASC),
        )
    }

    /** Unbekannte Werte (etwa aus einer älteren Fassung) auf den Standard zurückführen. */
    private fun String?.toVodSort(fallback: VodSort): VodSort =
        this?.let { name -> runCatching { VodSort.valueOf(name) }.getOrNull() } ?: fallback

    suspend fun setPreferHls(value: Boolean) = edit { it[KEY_PREFER_HLS] = value }
    suspend fun setBufferMs(value: Int) = edit { it[KEY_BUFFER_MS] = value }
    suspend fun setAspectRatio(value: AspectRatioMode) = edit { it[KEY_ASPECT_RATIO] = value.name }
    suspend fun setAudioLanguage(value: String) = edit { it[KEY_AUDIO_LANG] = value }
    suspend fun setSubtitleLanguage(value: String) = edit { it[KEY_SUBTITLE_LANG] = value }
    suspend fun setSubtitlesEnabled(value: Boolean) = edit { it[KEY_SUBTITLES_ON] = value }
    suspend fun setGuideWindowMinutes(value: Int) = edit { it[KEY_GUIDE_WINDOW] = value }
    suspend fun setResumeLastChannel(value: Boolean) = edit { it[KEY_RESUME_LAST] = value }
    suspend fun setShowPreviewPlayer(value: Boolean) = edit { it[KEY_SHOW_PREVIEW] = value }

    /** Merkt die Reihenfolge für den jeweiligen Bereich getrennt. */
    suspend fun setVodSort(kind: StreamKind, value: VodSort) = edit {
        val key = if (kind == StreamKind.SERIES) KEY_SERIES_SORT else KEY_MOVIE_SORT
        it[key] = value.name
    }

    // -----------------------------------------------------------------------
    // Hinterlegte Verbindung
    // -----------------------------------------------------------------------

    /**
     * Sichert die Zugangsdaten der Playlist **außerhalb** der Datenbank.
     *
     * Die Datenbank ist ein reiner Zwischenspeicher und wird bei jeder
     * Schemaänderung verworfen (`fallbackToDestructiveMigration`). Bis hierher
     * lag die eingerichtete Verbindung aber ebenfalls dort – nach jedem
     * Update mit geändertem Schema musste sie also neu eingegeben werden.
     * Hier überlebt sie das, weil DataStore von den Datenbankversionen
     * unberührt bleibt.
     *
     * Zur Ablage im Klartext: Das war in der Datenbank nicht anders, beide
     * liegen im app-eigenen Bereich. Es entsteht also kein neues Risiko –
     * verschlüsseln müsste man dann beides.
     */
    suspend fun rememberPlaylist(playlist: Playlist) = edit { prefs ->
        prefs[KEY_PL_NAME] = playlist.name
        prefs[KEY_PL_TYPE] = playlist.type.name
        prefs[KEY_PL_SERVER] = playlist.serverUrl
        prefs[KEY_PL_USER] = playlist.username
        prefs[KEY_PL_PASS] = playlist.password
        prefs[KEY_PL_M3U] = playlist.m3uUrl
        prefs[KEY_PL_EPG] = playlist.epgUrl
    }

    /** Vergisst die Verbindung – beim Entfernen der Playlist. */
    suspend fun forgetPlaylist() = edit { prefs ->
        listOf(
            KEY_PL_NAME, KEY_PL_TYPE, KEY_PL_SERVER,
            KEY_PL_USER, KEY_PL_PASS, KEY_PL_M3U, KEY_PL_EPG,
        ).forEach { prefs.remove(it) }
    }

    /** Die gesicherte Verbindung, oder `null` wenn keine hinterlegt ist. */
    suspend fun rememberedPlaylist(): Playlist? {
        val prefs = context.dataStore.data.first()
        val type = prefs[KEY_PL_TYPE]
            ?.let { name -> runCatching { PlaylistType.valueOf(name) }.getOrNull() }
            ?: return null

        val serverUrl = prefs[KEY_PL_SERVER].orEmpty()
        val m3uUrl = prefs[KEY_PL_M3U].orEmpty()
        // Ohne Adresse ist der Eintrag wertlos – dann lieber neu einrichten
        // lassen, als mit einer leeren Verbindung in einen Fehler zu laufen.
        val hasSource = when (type) {
            PlaylistType.XTREAM -> serverUrl.isNotBlank()
            PlaylistType.M3U -> m3uUrl.isNotBlank()
        }
        if (!hasSource) return null

        return Playlist(
            name = prefs[KEY_PL_NAME].orEmpty()
                .ifBlank { context.getString(R.string.playlist_default_name) },
            type = type,
            serverUrl = serverUrl,
            username = prefs[KEY_PL_USER].orEmpty(),
            password = prefs[KEY_PL_PASS].orEmpty(),
            m3uUrl = m3uUrl,
            epgUrl = prefs[KEY_PL_EPG].orEmpty(),
            // Bewusst 0: Der Zwischenspeicher ist weg, also soll der
            // Hauptbildschirm sofort neu laden statt eine leere Liste zeigen.
            lastSyncAt = 0L,
            lastEpgSyncAt = 0L,
        )
    }

    // -----------------------------------------------------------------------
    // "Neu in dieser Version"
    // -----------------------------------------------------------------------

    /** Version, zu der zuletzt eine Neuigkeiten-Meldung gezeigt wurde – `null` bei einer frischen Installation. */
    suspend fun lastSeenVersion(): String? = context.dataStore.data.first()[KEY_LAST_SEEN_VERSION]

    suspend fun setLastSeenVersion(version: String) = edit { it[KEY_LAST_SEEN_VERSION] = version }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        private val KEY_PREFER_HLS = booleanPreferencesKey("prefer_hls")
        private val KEY_BUFFER_MS = intPreferencesKey("buffer_ms")
        private val KEY_ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        private val KEY_AUDIO_LANG = stringPreferencesKey("audio_language")
        private val KEY_SUBTITLE_LANG = stringPreferencesKey("subtitle_language")
        private val KEY_SUBTITLES_ON = booleanPreferencesKey("subtitles_enabled")
        private val KEY_GUIDE_WINDOW = intPreferencesKey("guide_window_minutes")
        private val KEY_RESUME_LAST = booleanPreferencesKey("resume_last_channel")
        private val KEY_SHOW_PREVIEW = booleanPreferencesKey("show_preview_player")
        private val KEY_MOVIE_SORT = stringPreferencesKey("movie_sort")
        private val KEY_SERIES_SORT = stringPreferencesKey("series_sort")

        // Die eingerichtete Verbindung – liegt hier, damit sie das Verwerfen
        // der Datenbank bei Schemaänderungen übersteht.
        private val KEY_PL_NAME = stringPreferencesKey("playlist_name")
        private val KEY_PL_TYPE = stringPreferencesKey("playlist_type")
        private val KEY_PL_SERVER = stringPreferencesKey("playlist_server_url")
        private val KEY_PL_USER = stringPreferencesKey("playlist_username")
        private val KEY_PL_PASS = stringPreferencesKey("playlist_password")
        private val KEY_PL_M3U = stringPreferencesKey("playlist_m3u_url")
        private val KEY_PL_EPG = stringPreferencesKey("playlist_epg_url")

        private val KEY_LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")

        /**
         * Auswählbare Puffergrößen.
         *
         * Faustregel auf TV-Sticks: kleiner Puffer = schneller Kanalwechsel,
         * größerer Puffer = weniger Aussetzer im WLAN. 15 s ist der
         * Kompromiss, mit dem die meisten Fire TV Sticks stabil laufen.
         *
         * Die Beschriftung steht als Ressourcen-Kennung hier, nicht als
         * fertiger Text: Diese Liste ist eine Konstante ohne Zugriff auf
         * einen Context, den Text holt daher erst die Oberfläche.
         */
        val BUFFER_PRESETS = listOf(
            BufferPreset(R.string.settings_buffer_small, 5_000),
            BufferPreset(R.string.settings_buffer_medium, 15_000),
            BufferPreset(R.string.settings_buffer_large, 30_000),
            BufferPreset(R.string.settings_buffer_xlarge, 60_000),
        )
    }
}
