package de.qwikster.player.data.prefs

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.qwikster.player.R
import de.qwikster.player.data.model.AspectRatioMode
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.model.PlaylistType
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.model.VodSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "qwikster_settings")

/**
 * Eine hinterlegte Verbindung, wie sie außerhalb der Datenbank liegt.
 *
 * Eigener Typ statt des Domänenmodells [Playlist]: Was hier gespeichert
 * wird, muss über Programmversionen hinweg lesbar bleiben. Das Domänenmodell
 * darf sich ändern (Felder dazu, Felder weg), diese Form nicht ohne
 * Rücksicht auf das, was auf den Geräten schon liegt. `id` fehlt bewusst –
 * sie vergibt die Datenbank, und die wird beim Wiederherstellen ohnehin neu
 * aufgebaut.
 */
@Serializable
private data class StoredPlaylist(
    val name: String,
    val type: String,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val isActive: Boolean = false,
)

/** Unbekannte Felder überlesen: So kann eine ältere App eine neuere Ablage lesen. */
private val PLAYLIST_JSON = Json { ignoreUnknownKeys = true }

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
    /**
     * Bildwiederholrate des Fernsehers an den laufenden Film anpassen.
     *
     * Standardmäßig an: Ohne die Anpassung ruckelt europäisches Material mit
     * 25 oder 50 Bildern auf einem 60-Hz-Bildschirm sichtbar, und das ist der
     * mit Abstand häufigste Grund für ein unruhiges Bild. Abschaltbar, weil
     * die Umstellung einen kurzen Schwarzbild-Moment kostet – siehe
     * [de.qwikster.player.ui.common.MatchDisplayFrameRate].
     */
    val matchFrameRate: Boolean = true,
    /** Reihenfolge im Filme-Raster. */
    val movieSort: VodSort = VodSort.RECENT,
    /** Reihenfolge im Serien-Raster. */
    /**
     * Auch bei Serien stehen Neuzugänge vorn – wie bei Netflix, TiviMate und
     * IBO Player. Hier stand alphabetisch, was die ganze Sortierarbeit im
     * Serienbereich unsichtbar machte: Was neu hereinkam, versteckte sich
     * irgendwo zwischen A und Z.
     */
    val seriesSort: VodSort = VodSort.RECENT,
    /**
     * Kategorien, die der Zuschauer ausgeblendet hat – je Bereich getrennt.
     *
     * Gespeichert werden die *ausgeblendeten*, nicht die sichtbaren. Der
     * Unterschied ist wesentlich: Ein Panel nimmt laufend neue Kategorien
     * auf, und die sollen von selbst erscheinen. Bei einer Liste sichtbarer
     * Kategorien bliebe dagegen jede Neuheit unsichtbar, bis jemand sie von
     * Hand freischaltet – und niemand kommt auf die Idee, dort nachzusehen.
     */
    val hiddenLiveCategories: Set<String> = emptySet(),
    val hiddenMovieCategories: Set<String> = emptySet(),
    val hiddenSeriesCategories: Set<String> = emptySet(),
    /**
     * Kennung, unter der sich die App beim Anbieter meldet – leer heißt
     * "automatisch durchprobieren".
     *
     * Es gibt Anbieter, deren Schutzschicht nur eine Handvoll bekannter
     * Abspielprogramme durchlässt und allem anderen eine Abfuhr erteilt.
     * Welches sie kennt, weiß nur der Zuschauer – nämlich das, in dem seine
     * Playlist bereits läuft. Deshalb ist das hier wählbar und nicht
     * geraten.
     */
    val userAgent: String = "",
    /** SOCKS5-Proxy: aus, solange keine Adresse hinterlegt ist. */
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    val proxyUser: String = "",
    val proxyPassword: String = "",
) {
    /** Die ausgeblendeten Kategorien des jeweiligen Bereichs. */
    fun hiddenCategories(kind: StreamKind): Set<String> = when (kind) {
        StreamKind.LIVE -> hiddenLiveCategories
        StreamKind.SERIES -> hiddenSeriesCategories
        else -> hiddenMovieCategories
    }
}

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
            matchFrameRate = prefs[KEY_MATCH_FRAME_RATE] ?: true,
            hiddenLiveCategories = prefs[KEY_HIDDEN_LIVE].orEmpty(),
            hiddenMovieCategories = prefs[KEY_HIDDEN_VOD].orEmpty(),
            hiddenSeriesCategories = prefs[KEY_HIDDEN_SERIES].orEmpty(),
            userAgent = prefs[KEY_USER_AGENT].orEmpty(),
            proxyEnabled = prefs[KEY_PROXY_ON] ?: false,
            proxyHost = prefs[KEY_PROXY_HOST].orEmpty(),
            proxyPort = prefs[KEY_PROXY_PORT] ?: 1080,
            proxyUser = prefs[KEY_PROXY_USER].orEmpty(),
            proxyPassword = prefs[KEY_PROXY_PASS].orEmpty(),
            movieSort = prefs[KEY_MOVIE_SORT].toVodSort(VodSort.RECENT),
            seriesSort = prefs[KEY_SERIES_SORT].toVodSort(VodSort.RECENT),
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
    suspend fun setMatchFrameRate(value: Boolean) = edit { it[KEY_MATCH_FRAME_RATE] = value }

    /** Leer entfernt den Eintrag – siehe [AppSettings.userAgent]. */
    suspend fun setUserAgent(value: String) = edit { prefs ->
        val trimmed = value.trim()
        if (trimmed.isEmpty()) prefs.remove(KEY_USER_AGENT) else prefs[KEY_USER_AGENT] = trimmed
    }

    /** Merkt die ausgeblendeten Kategorien eines Bereichs. */
    suspend fun setHiddenCategories(kind: StreamKind, ids: Set<String>) = edit { prefs ->
        val key = when (kind) {
            StreamKind.LIVE -> KEY_HIDDEN_LIVE
            StreamKind.SERIES -> KEY_HIDDEN_SERIES
            else -> KEY_HIDDEN_VOD
        }
        // Leere Mengen ganz entfernen statt leer zu speichern – so bleibt der
        // Ausgangszustand "nichts ausgeblendet" auch nach einem Zurücksetzen
        // wirklich der Ausgangszustand.
        if (ids.isEmpty()) prefs.remove(key) else prefs[key] = ids
    }

    suspend fun setProxy(enabled: Boolean, host: String, port: Int, user: String, password: String) = edit {
        it[KEY_PROXY_ON] = enabled
        it[KEY_PROXY_HOST] = host.trim()
        it[KEY_PROXY_PORT] = port
        it[KEY_PROXY_USER] = user.trim()
        it[KEY_PROXY_PASS] = password
    }

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
    suspend fun rememberPlaylists(playlists: List<Playlist>, activeId: Long?) = edit { prefs ->
        if (playlists.isEmpty()) {
            prefs.remove(KEY_PLAYLISTS)
        } else {
            prefs[KEY_PLAYLISTS] = PLAYLIST_JSON.encodeToString(
                playlists.map { playlist ->
                    StoredPlaylist(
                        name = playlist.name,
                        type = playlist.type.name,
                        serverUrl = playlist.serverUrl,
                        username = playlist.username,
                        password = playlist.password,
                        m3uUrl = playlist.m3uUrl,
                        epgUrl = playlist.epgUrl,
                        isActive = playlist.id == activeId,
                    )
                },
            )
        }
        // Die Einzelfelder der früheren Fassung fliegen mit raus, sobald hier
        // etwas Neues steht – sonst läge dieselbe Verbindung doppelt vor und
        // ein späterer Rückfall auf die alten Felder brächte einen Geist
        // zurück, den der Zuschauer längst entfernt hat.
        listOf(
            KEY_PL_NAME, KEY_PL_TYPE, KEY_PL_SERVER,
            KEY_PL_USER, KEY_PL_PASS, KEY_PL_M3U, KEY_PL_EPG,
        ).forEach { prefs.remove(it) }
    }

    /**
     * Alle hinterlegten Verbindungen.
     *
     * Liegt noch nichts in der Listenform vor, wird die Einzelverbindung der
     * früheren Fassung gelesen und als einelementige Liste zurückgegeben –
     * wer vor diesem Update eine Playlist eingerichtet hatte, behält sie.
     */
    suspend fun rememberedPlaylists(): List<Playlist> {
        val raw = context.dataStore.data.first()[KEY_PLAYLISTS]
        if (raw.isNullOrBlank()) return listOfNotNull(rememberedPlaylist())

        val stored = runCatching {
            PLAYLIST_JSON.decodeFromString<List<StoredPlaylist>>(raw)
        }.getOrElse { return listOfNotNull(rememberedPlaylist()) }

        return stored.mapNotNull { entry ->
            val type = runCatching { PlaylistType.valueOf(entry.type) }.getOrNull()
                ?: return@mapNotNull null
            val hasSource = when (type) {
                PlaylistType.XTREAM -> entry.serverUrl.isNotBlank()
                PlaylistType.M3U -> entry.m3uUrl.isNotBlank()
            }
            if (!hasSource) return@mapNotNull null
            Playlist(
                name = entry.name.ifBlank { context.getString(R.string.playlist_default_name) },
                type = type,
                serverUrl = entry.serverUrl,
                username = entry.username,
                password = entry.password,
                m3uUrl = entry.m3uUrl,
                epgUrl = entry.epgUrl,
                // Bewusst 0: Der Zwischenspeicher ist weg, also soll die
                // ausgewählte Playlist sofort neu laden.
                lastSyncAt = 0L,
                lastEpgSyncAt = 0L,
            )
        }
    }

    /** Welche der hinterlegten Verbindungen zuletzt ausgewählt war. */
    suspend fun rememberedActiveIndex(): Int {
        val raw = context.dataStore.data.first()[KEY_PLAYLISTS] ?: return 0
        val stored = runCatching {
            PLAYLIST_JSON.decodeFromString<List<StoredPlaylist>>(raw)
        }.getOrNull() ?: return 0
        return stored.indexOfFirst { it.isActive }.takeIf { it >= 0 } ?: 0
    }

    /** Die gesicherte Verbindung der früheren Fassung, oder `null`. */
    private suspend fun rememberedPlaylist(): Playlist? {
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
        private val KEY_MATCH_FRAME_RATE = booleanPreferencesKey("match_frame_rate")
        private val KEY_PLAYLISTS = stringPreferencesKey("remembered_playlists")
        private val KEY_HIDDEN_LIVE = stringSetPreferencesKey("hidden_categories_live")
        private val KEY_HIDDEN_VOD = stringSetPreferencesKey("hidden_categories_vod")
        private val KEY_HIDDEN_SERIES = stringSetPreferencesKey("hidden_categories_series")
        private val KEY_USER_AGENT = stringPreferencesKey("user_agent")
        private val KEY_PROXY_ON = booleanPreferencesKey("proxy_enabled")
        private val KEY_PROXY_HOST = stringPreferencesKey("proxy_host")
        private val KEY_PROXY_PORT = intPreferencesKey("proxy_port")
        private val KEY_PROXY_USER = stringPreferencesKey("proxy_user")
        private val KEY_PROXY_PASS = stringPreferencesKey("proxy_password")
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
