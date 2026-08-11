package de.neunelf.player.data.model

import androidx.annotation.StringRes
import de.neunelf.player.R

/**
 * Domänenmodelle der App.
 *
 * Diese Klassen sind bewusst frei von Netzwerk- und Datenbank-Details:
 * - Die Xtream-JSON-Antworten (siehe [de.neunelf.player.data.remote.xtream.XtreamDto])
 *   werden per Mapper hierher übersetzt.
 * - Die Room-Entities (siehe [de.neunelf.player.data.local.Entities]) spiegeln sie 1:1.
 *
 * Dadurch kann die Quelle (Xtream API vs. reine M3U-Datei) ausgetauscht werden,
 * ohne dass die UI etwas davon merkt.
 */

/** Art der Playlist-Quelle, die der Nutzer eingerichtet hat. */
enum class PlaylistType { XTREAM, M3U }

/** Inhaltstyp eines Streams. Bestimmt u. a. den URL-Aufbau bei Xtream. */
enum class StreamKind { LIVE, VOD, SERIES }

/**
 * Ein eingerichtetes Nutzerprofil / eine Playlist.
 * Die App unterstützt mehrere Profile; genau eines ist aktiv.
 */
data class Playlist(
    val id: Long = 0L,
    val name: String,
    val type: PlaylistType,
    /** Basis-URL ohne abschließenden Slash, z. B. `http://server.tv:8080`. Nur bei XTREAM. */
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** Direkter M3U-Link. Nur bei M3U. */
    val m3uUrl: String = "",
    /** Optionale XMLTV-URL. Bei Xtream wird sonst `xmltv.php` verwendet. */
    val epgUrl: String = "",
    /** Zeitpunkt der letzten erfolgreichen Synchronisierung (epoch millis). */
    val lastSyncAt: Long = 0L,
    val lastEpgSyncAt: Long = 0L,
)

/**
 * Kategorie (Bouquet) innerhalb einer Playlist, z. B. "Deutschland HD".
 * Bei M3U-Playlists wird sie aus dem `group-title`-Attribut abgeleitet.
 */
data class Category(
    val id: String,
    val name: String,
    val kind: StreamKind,
    val playlistId: Long,
    /** Sortierung wie vom Panel geliefert; sonst alphabetisch. */
    val sortOrder: Int = 0,
    val channelCount: Int = 0,
)

/**
 * Ein Live-Sender.
 *
 * @param streamId Xtream-interne ID; bei M3U eine stabile Hash-ID aus der URL.
 * @param epgChannelId `tvg-id` bzw. `epg_channel_id` – der Schlüssel, über den
 *        die XMLTV-Programmdaten zugeordnet werden.
 * @param directUrl Bei M3U bereits die vollständige Stream-URL. Bei Xtream leer,
 *        die URL wird dann zur Laufzeit gebaut (siehe `XtreamClient.buildStreamUrl`).
 */
data class Channel(
    val streamId: String,
    val playlistId: Long,
    val name: String,
    val logoUrl: String? = null,
    val categoryId: String? = null,
    val epgChannelId: String? = null,
    val number: Int = 0,
    val directUrl: String? = null,
    val containerExtension: String = "ts",
    val isFavorite: Boolean = false,
    val lastWatchedAt: Long = 0L,
    /** Nur bei Archiv-fähigen Panels: Anzahl Tage Catch-up. */
    val archiveDays: Int = 0,
) {
    val hasArchive: Boolean get() = archiveDays > 0
}

/**
 * Ein Film (VOD).
 */
data class Movie(
    val streamId: String,
    val playlistId: Long,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String? = null,
    val containerExtension: String = "mp4",
    val rating: Double = 0.0,
    val year: String? = null,
    val plot: String? = null,
    val durationSecs: Int = 0,
    val addedAt: Long = 0L,
    /** Bei M3U bereits die vollständige Stream-URL. Bei Xtream leer, die URL wird zur Laufzeit gebaut. */
    val directUrl: String? = null,
)

/**
 * Eine Serie. Staffeln/Episoden werden erst bei Bedarf nachgeladen
 * (`get_series_info`), weil die Panels sonst sehr große Antworten liefern.
 */
data class Series(
    val seriesId: String,
    val playlistId: Long,
    val name: String,
    val posterUrl: String? = null,
    val categoryId: String? = null,
    val plot: String? = null,
    val rating: Double = 0.0,
    val year: String? = null,
    val lastModified: Long = 0L,
)

/** Eine einzelne Episode innerhalb einer Staffel. */
data class Episode(
    val episodeId: String,
    val seriesId: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String = "mp4",
    val plot: String? = null,
    val durationSecs: Int = 0,
    val thumbnailUrl: String? = null,
    /** Bei M3U bereits die vollständige Stream-URL. Bei Xtream leer, die URL entsteht zur Laufzeit. */
    val directUrl: String? = null,
)

/**
 * Ein EPG-Eintrag (eine Sendung).
 *
 * Zeiten sind UTC-Millisekunden. Die Umrechnung in die lokale Zeitzone
 * passiert erst in der UI, damit Sortierung und Überlappungsprüfung
 * zeitzonenunabhängig bleiben.
 */
data class EpgProgram(
    val id: Long = 0L,
    val epgChannelId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val iconUrl: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val durationMs: Long get() = (endAt - startAt).coerceAtLeast(0L)

    fun isLiveAt(now: Long): Boolean = now in startAt until endAt

    /** Fortschritt 0f..1f – für den Balken in der Senderliste und im Info-Overlay. */
    fun progressAt(now: Long): Float {
        if (durationMs <= 0L) return 0f
        return ((now - startAt).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Kombination aus Sender und aktuell laufender/nächster Sendung –
 * das ist genau das, was die Senderliste im TiviMate-Stil anzeigt.
 */
data class ChannelWithProgram(
    val channel: Channel,
    val current: EpgProgram? = null,
    val next: EpgProgram? = null,
)

/**
 * Reihenfolge im Poster-Raster von Filmen und Serien.
 *
 * Wird je Bereich getrennt gemerkt: Bei Filmen ist "zuletzt hinzugefügt"
 * die sinnvolle Voreinstellung (man will sehen, was neu ist), bei Serien
 * die alphabetische – Serienkataloge ändern sich selten, und man sucht
 * dort meist einen bestimmten Titel.
 */
enum class VodSort(@StringRes val labelRes: Int) {
    /** Neuzugänge zuerst. Bei Serien zählt die letzte Änderung am Eintrag. */
    RECENT(R.string.vod_sort_recent),
    NAME_ASC(R.string.vod_sort_name_asc),
    NAME_DESC(R.string.vod_sort_name_desc),
}

/** Seitenverhältnis-Modi des Players (zyklisch per Schnellmenü umschaltbar). */
enum class AspectRatioMode(@StringRes val labelRes: Int) {
    FIT(R.string.aspect_ratio_fit),
    FILL(R.string.aspect_ratio_fill),
    ZOOM(R.string.aspect_ratio_zoom),
    FIXED_16_9(R.string.aspect_ratio_16_9),
    FIXED_4_3(R.string.aspect_ratio_4_3),
    ;

    fun next(): AspectRatioMode = entries[(ordinal + 1) % entries.size]
}
