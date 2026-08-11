package de.qwikster.player.data.remote.xtream

import de.qwikster.player.data.remote.json.FlexibleBooleanSerializer
import de.qwikster.player.data.remote.json.FlexibleDoubleSerializer
import de.qwikster.player.data.remote.json.FlexibleIntSerializer
import de.qwikster.player.data.remote.json.FlexibleLongSerializer
import de.qwikster.player.data.remote.json.FlexibleNullableStringSerializer
import de.qwikster.player.data.remote.json.FlexibleStringSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 1:1-Abbildungen der Xtream-Codes-JSON-Antworten.
 *
 * Grundregeln in dieser Datei:
 *  - **Jedes** Feld hat einen Defaultwert, damit fehlende Keys nicht crashen.
 *  - Jedes Feld benutzt einen toleranten Serializer (siehe `remote/json`).
 *  - Es findet hier *keine* Logik statt – das Mapping in die Domänenmodelle
 *    liegt in [XtreamMapper].
 */

// ---------------------------------------------------------------------------
// player_api.php  (ohne action) -> Authentifizierung & Server-Infos
// ---------------------------------------------------------------------------

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null,
)

@Serializable
data class XtreamUserInfo(
    @Serializable(FlexibleStringSerializer::class)
    val username: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val password: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val message: String = "",
    /** 1 = Login erfolgreich. Alles andere behandeln wir als Fehler. */
    @Serializable(FlexibleIntSerializer::class)
    val auth: Int = 0,
    /** "Active", "Banned", "Disabled", "Expired" … */
    @Serializable(FlexibleStringSerializer::class)
    val status: String = "",
    /** Ablaufdatum als Unix-Timestamp in Sekunden; leer = unbegrenzt. */
    @SerialName("exp_date")
    @Serializable(FlexibleNullableStringSerializer::class)
    val expDate: String? = null,
    @SerialName("is_trial")
    @Serializable(FlexibleBooleanSerializer::class)
    val isTrial: Boolean = false,
    @SerialName("active_cons")
    @Serializable(FlexibleIntSerializer::class)
    val activeConnections: Int = 0,
    @SerialName("max_connections")
    @Serializable(FlexibleIntSerializer::class)
    val maxConnections: Int = 1,
    /** z. B. ["m3u8", "ts", "rtmp"] – bestimmt die bevorzugte Container-Endung. */
    @SerialName("allowed_output_formats")
    val allowedOutputFormats: List<String> = emptyList(),
) {
    val isAuthenticated: Boolean get() = auth == 1 && !status.equals("Banned", ignoreCase = true)
}

@Serializable
data class XtreamServerInfo(
    @Serializable(FlexibleStringSerializer::class)
    val url: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val port: String = "",
    @SerialName("https_port")
    @Serializable(FlexibleStringSerializer::class)
    val httpsPort: String = "",
    @SerialName("server_protocol")
    @Serializable(FlexibleStringSerializer::class)
    val serverProtocol: String = "http",
    @Serializable(FlexibleStringSerializer::class)
    val timezone: String = "UTC",
    /** Serverzeit als Unix-Timestamp (Sekunden) – nützlich für EPG-Drift-Korrektur. */
    @SerialName("timestamp_now")
    @Serializable(FlexibleLongSerializer::class)
    val timestampNow: Long = 0L,
)

// ---------------------------------------------------------------------------
// get_live_categories / get_vod_categories / get_series_categories
// ---------------------------------------------------------------------------

@Serializable
data class XtreamCategoryDto(
    @SerialName("category_id")
    @Serializable(FlexibleStringSerializer::class)
    val categoryId: String = "",
    @SerialName("category_name")
    @Serializable(FlexibleStringSerializer::class)
    val categoryName: String = "",
    @SerialName("parent_id")
    @Serializable(FlexibleIntSerializer::class)
    val parentId: Int = 0,
)

// ---------------------------------------------------------------------------
// get_live_streams
// ---------------------------------------------------------------------------

@Serializable
data class XtreamLiveStreamDto(
    /** Senderplatz laut Panel (LCN). Wird für die Sortierung benutzt. */
    @Serializable(FlexibleIntSerializer::class)
    val num: Int = 0,
    @Serializable(FlexibleStringSerializer::class)
    val name: String = "",
    @SerialName("stream_type")
    @Serializable(FlexibleStringSerializer::class)
    val streamType: String = "live",
    @SerialName("stream_id")
    @Serializable(FlexibleStringSerializer::class)
    val streamId: String = "",
    @SerialName("stream_icon")
    @Serializable(FlexibleNullableStringSerializer::class)
    val streamIcon: String? = null,
    /** Schlüssel für die XMLTV-Zuordnung (entspricht `tvg-id` in M3U). */
    @SerialName("epg_channel_id")
    @Serializable(FlexibleNullableStringSerializer::class)
    val epgChannelId: String? = null,
    @Serializable(FlexibleStringSerializer::class)
    val added: String = "",
    @SerialName("category_id")
    @Serializable(FlexibleNullableStringSerializer::class)
    val categoryId: String? = null,
    /** 1 = Catch-up/Archiv verfügbar. */
    @SerialName("tv_archive")
    @Serializable(FlexibleIntSerializer::class)
    val tvArchive: Int = 0,
    @SerialName("tv_archive_duration")
    @Serializable(FlexibleIntSerializer::class)
    val tvArchiveDuration: Int = 0,
    /**
     * Wenn gesetzt, soll laut Panel direkt diese URL abgespielt werden
     * (statt der zusammengebauten `/live/user/pass/id.ts`).
     */
    @SerialName("direct_source")
    @Serializable(FlexibleNullableStringSerializer::class)
    val directSource: String? = null,
)

// ---------------------------------------------------------------------------
// get_vod_streams
// ---------------------------------------------------------------------------

@Serializable
data class XtreamVodStreamDto(
    @Serializable(FlexibleIntSerializer::class)
    val num: Int = 0,
    @Serializable(FlexibleStringSerializer::class)
    val name: String = "",
    @SerialName("stream_id")
    @Serializable(FlexibleStringSerializer::class)
    val streamId: String = "",
    @SerialName("stream_icon")
    @Serializable(FlexibleNullableStringSerializer::class)
    val streamIcon: String? = null,
    @Serializable(FlexibleDoubleSerializer::class)
    val rating: Double = 0.0,
    @SerialName("category_id")
    @Serializable(FlexibleNullableStringSerializer::class)
    val categoryId: String? = null,
    /** "mp4", "mkv", "avi" … – Teil der Wiedergabe-URL. */
    @SerialName("container_extension")
    @Serializable(FlexibleStringSerializer::class)
    val containerExtension: String = "mp4",
    @Serializable(FlexibleLongSerializer::class)
    val added: Long = 0L,
    @SerialName("direct_source")
    @Serializable(FlexibleNullableStringSerializer::class)
    val directSource: String? = null,
)

/** get_vod_info -> Detailinformationen zu einem Film. */
@Serializable
data class XtreamVodInfoResponse(
    val info: XtreamVodInfo? = null,
    @SerialName("movie_data") val movieData: XtreamVodStreamDto? = null,
)

@Serializable
data class XtreamVodInfo(
    @SerialName("movie_image")
    @Serializable(FlexibleNullableStringSerializer::class)
    val movieImage: String? = null,
    @Serializable(FlexibleNullableStringSerializer::class)
    val plot: String? = null,
    @Serializable(FlexibleNullableStringSerializer::class)
    val cast: String? = null,
    @Serializable(FlexibleNullableStringSerializer::class)
    val director: String? = null,
    @Serializable(FlexibleNullableStringSerializer::class)
    val genre: String? = null,
    @SerialName("releasedate")
    @Serializable(FlexibleNullableStringSerializer::class)
    val releaseDate: String? = null,
    @Serializable(FlexibleDoubleSerializer::class)
    val rating: Double = 0.0,
    @SerialName("duration_secs")
    @Serializable(FlexibleIntSerializer::class)
    val durationSecs: Int = 0,
)

// ---------------------------------------------------------------------------
// get_series / get_series_info
// ---------------------------------------------------------------------------

@Serializable
data class XtreamSeriesDto(
    @SerialName("series_id")
    @Serializable(FlexibleStringSerializer::class)
    val seriesId: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val name: String = "",
    @Serializable(FlexibleNullableStringSerializer::class)
    val cover: String? = null,
    @Serializable(FlexibleNullableStringSerializer::class)
    val plot: String? = null,
    @Serializable(FlexibleNullableStringSerializer::class)
    val genre: String? = null,
    /** Panels schreiben mal `releaseDate`, mal `release_date`. */
    @SerialName("releaseDate")
    @Serializable(FlexibleNullableStringSerializer::class)
    val releaseDate: String? = null,
    @SerialName("last_modified")
    @Serializable(FlexibleLongSerializer::class)
    val lastModified: Long = 0L,
    @Serializable(FlexibleDoubleSerializer::class)
    val rating: Double = 0.0,
    @SerialName("category_id")
    @Serializable(FlexibleNullableStringSerializer::class)
    val categoryId: String? = null,
)

/**
 * `episodes` bleibt bewusst ein roher [JsonElement]: die Struktur schwankt
 * zwischen Objekt (Staffel -> Liste) und Array. Die Normalisierung übernimmt
 * `normalizeSeasonMap` in [de.qwikster.player.data.remote.json].
 */
@Serializable
data class XtreamSeriesInfoResponse(
    val info: XtreamSeriesDto? = null,
    val episodes: JsonElement? = null,
)

@Serializable
data class XtreamEpisodeDto(
    @Serializable(FlexibleStringSerializer::class)
    val id: String = "",
    @SerialName("episode_num")
    @Serializable(FlexibleIntSerializer::class)
    val episodeNum: Int = 0,
    @Serializable(FlexibleStringSerializer::class)
    val title: String = "",
    @Serializable(FlexibleIntSerializer::class)
    val season: Int = 0,
    @SerialName("container_extension")
    @Serializable(FlexibleStringSerializer::class)
    val containerExtension: String = "mp4",
    val info: XtreamEpisodeInfo? = null,
)

@Serializable
data class XtreamEpisodeInfo(
    @Serializable(FlexibleNullableStringSerializer::class)
    val plot: String? = null,
    @SerialName("duration_secs")
    @Serializable(FlexibleIntSerializer::class)
    val durationSecs: Int = 0,
    @SerialName("movie_image")
    @Serializable(FlexibleNullableStringSerializer::class)
    val movieImage: String? = null,
)

// ---------------------------------------------------------------------------
// get_short_epg / get_simple_data_table
// ---------------------------------------------------------------------------

@Serializable
data class XtreamEpgResponse(
    @SerialName("epg_listings") val listings: List<XtreamEpgListingDto> = emptyList(),
)

/**
 * Achtung: `title` und `description` sind bei Xtream **Base64-kodiert**.
 * Die Dekodierung passiert im [XtreamMapper], nicht hier.
 */
@Serializable
data class XtreamEpgListingDto(
    @Serializable(FlexibleStringSerializer::class)
    val id: String = "",
    @SerialName("epg_id")
    @Serializable(FlexibleStringSerializer::class)
    val epgId: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val title: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val description: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val lang: String = "",
    /** Format "yyyy-MM-dd HH:mm:ss" in Serverzeit – wir nehmen die Timestamps. */
    @Serializable(FlexibleStringSerializer::class)
    val start: String = "",
    @Serializable(FlexibleStringSerializer::class)
    val end: String = "",
    @SerialName("channel_id")
    @Serializable(FlexibleStringSerializer::class)
    val channelId: String = "",
    @SerialName("start_timestamp")
    @Serializable(FlexibleLongSerializer::class)
    val startTimestamp: Long = 0L,
    @SerialName("stop_timestamp")
    @Serializable(FlexibleLongSerializer::class)
    val stopTimestamp: Long = 0L,
    @SerialName("now_playing")
    @Serializable(FlexibleBooleanSerializer::class)
    val nowPlaying: Boolean = false,
    @SerialName("has_archive")
    @Serializable(FlexibleBooleanSerializer::class)
    val hasArchive: Boolean = false,
)
