package com.streamitv.tv.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.streamitv.tv.data.model.AspectRatioMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "streamitv_settings")

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
        )
    }

    suspend fun setPreferHls(value: Boolean) = edit { it[KEY_PREFER_HLS] = value }
    suspend fun setBufferMs(value: Int) = edit { it[KEY_BUFFER_MS] = value }
    suspend fun setAspectRatio(value: AspectRatioMode) = edit { it[KEY_ASPECT_RATIO] = value.name }
    suspend fun setAudioLanguage(value: String) = edit { it[KEY_AUDIO_LANG] = value }
    suspend fun setSubtitleLanguage(value: String) = edit { it[KEY_SUBTITLE_LANG] = value }
    suspend fun setSubtitlesEnabled(value: Boolean) = edit { it[KEY_SUBTITLES_ON] = value }
    suspend fun setGuideWindowMinutes(value: Int) = edit { it[KEY_GUIDE_WINDOW] = value }
    suspend fun setResumeLastChannel(value: Boolean) = edit { it[KEY_RESUME_LAST] = value }
    suspend fun setShowPreviewPlayer(value: Boolean) = edit { it[KEY_SHOW_PREVIEW] = value }

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

        /**
         * Auswählbare Puffergrößen.
         *
         * Faustregel auf TV-Sticks: kleiner Puffer = schneller Kanalwechsel,
         * größerer Puffer = weniger Aussetzer im WLAN. 15 s ist der
         * Kompromiss, mit dem die meisten Fire TV Sticks stabil laufen.
         */
        val BUFFER_PRESETS = linkedMapOf(
            "Klein (5 s) – schnellster Zap" to 5_000,
            "Mittel (15 s) – Standard" to 15_000,
            "Groß (30 s) – schwaches WLAN" to 30_000,
            "Sehr groß (60 s)" to 60_000,
        )
    }
}
