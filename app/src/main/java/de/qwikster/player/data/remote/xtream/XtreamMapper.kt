package de.qwikster.player.data.remote.xtream

import android.util.Base64
import de.qwikster.player.data.model.Category
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.EpgProgram
import de.qwikster.player.data.model.Episode
import de.qwikster.player.data.model.Movie
import de.qwikster.player.data.model.Series
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.remote.json.normalizeSeasonMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.TimeUnit

/**
 * Übersetzt die Xtream-DTOs in die Domänenmodelle.
 *
 * Hier – und nur hier – stehen die ganzen Panel-Eigenheiten:
 * Base64-kodierte EPG-Titel, Sekunden- statt Millisekunden-Timestamps,
 * "n/a"-Platzhalter in Logo-URLs usw.
 */
object XtreamMapper {

    /** Platzhalter, die Panels statt eines leeren Feldes schicken. */
    private val PLACEHOLDER_VALUES = setOf("n/a", "na", "null", "-", "none", "0")

    // -----------------------------------------------------------------------
    // Kategorien
    // -----------------------------------------------------------------------

    fun toCategories(
        dtos: List<XtreamCategoryDto>,
        kind: StreamKind,
        playlistId: Long,
    ): List<Category> = dtos
        .filter { it.categoryId.isNotBlank() }
        .mapIndexed { index, dto ->
            Category(
                id = dto.categoryId,
                name = dto.categoryName.cleanName().ifBlank { "Ohne Kategorie" },
                kind = kind,
                playlistId = playlistId,
                sortOrder = index,
            )
        }

    // -----------------------------------------------------------------------
    // Live-Sender
    // -----------------------------------------------------------------------

    fun toChannels(dtos: List<XtreamLiveStreamDto>, playlistId: Long): List<Channel> = dtos
        .filter { it.streamId.isNotBlank() }
        .map { dto ->
            Channel(
                streamId = dto.streamId,
                playlistId = playlistId,
                name = dto.name.cleanName(),
                logoUrl = dto.streamIcon.sanitizeUrl(),
                categoryId = dto.categoryId,
                // Manche Panels schreiben den tvg-Namen statt einer ID – trotzdem
                // brauchbar, weil XMLTV-Dateien häufig denselben Wert benutzen.
                epgChannelId = dto.epgChannelId?.trim()?.takeIf { it.isNotBlank() },
                number = dto.num,
                containerExtension = "ts",
                // `direct_source` nur übernehmen, wenn es wirklich eine URL ist.
                directUrl = dto.directSource?.takeIf { it.startsWith("http", ignoreCase = true) },
                archiveDays = if (dto.tvArchive > 0) dto.tvArchiveDuration.coerceAtLeast(1) else 0,
            )
        }

    // -----------------------------------------------------------------------
    // VOD
    // -----------------------------------------------------------------------

    fun toMovies(dtos: List<XtreamVodStreamDto>, playlistId: Long): List<Movie> = dtos
        .filter { it.streamId.isNotBlank() }
        .map { dto ->
            Movie(
                streamId = dto.streamId,
                playlistId = playlistId,
                name = dto.name.cleanName(),
                posterUrl = dto.streamIcon.sanitizeUrl(),
                categoryId = dto.categoryId,
                containerExtension = dto.containerExtension.ifBlank { "mp4" },
                rating = dto.rating,
                // `added` ist ein Unix-Timestamp in Sekunden.
                addedAt = TimeUnit.SECONDS.toMillis(dto.added),
            )
        }

    /** Reichert einen bereits bekannten Film mit den Details aus `get_vod_info` an. */
    fun enrichMovie(movie: Movie, response: XtreamVodInfoResponse): Movie {
        val info = response.info ?: return movie
        return movie.copy(
            posterUrl = info.movieImage.sanitizeUrl() ?: movie.posterUrl,
            plot = info.plot?.takeIf { it.isNotBlank() },
            year = info.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
            rating = info.rating.takeIf { it > 0.0 } ?: movie.rating,
            durationSecs = info.durationSecs,
        )
    }

    // -----------------------------------------------------------------------
    // Serien
    // -----------------------------------------------------------------------

    fun toSeries(dtos: List<XtreamSeriesDto>, playlistId: Long): List<Series> = dtos
        .filter { it.seriesId.isNotBlank() }
        .map { dto ->
            Series(
                seriesId = dto.seriesId,
                playlistId = playlistId,
                name = dto.name.cleanName(),
                posterUrl = dto.cover.sanitizeUrl(),
                categoryId = dto.categoryId,
                plot = dto.plot?.takeIf { it.isNotBlank() },
                rating = dto.rating,
                year = dto.releaseDate?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                lastModified = TimeUnit.SECONDS.toMillis(dto.lastModified),
            )
        }

    /**
     * Zieht die Episoden aus der `get_series_info`-Antwort.
     *
     * Das `episodes`-Feld ist absichtlich untypisiert (siehe [XtreamSeriesInfoResponse]),
     * weil Panels es entweder als `{"1": [...], "2": [...]}` oder als
     * `[[...], [...]]` liefern. [normalizeSeasonMap] glättet beides.
     */
    fun toEpisodes(seriesId: String, response: XtreamSeriesInfoResponse, json: Json): List<Episode> {
        val seasons = normalizeSeasonMap(response.episodes)
        return seasons.flatMap { (seasonNumber, array) ->
            array.mapNotNull { element: JsonElement ->
                val dto = runCatching { json.decodeFromJsonElement<XtreamEpisodeDto>(element) }
                    .getOrNull() ?: return@mapNotNull null
                if (dto.id.isBlank()) return@mapNotNull null

                Episode(
                    episodeId = dto.id,
                    seriesId = seriesId,
                    // `season` im DTO ist unzuverlässig – der Map-Key gewinnt.
                    season = if (dto.season > 0) dto.season else seasonNumber,
                    episodeNumber = dto.episodeNum,
                    title = dto.title.cleanName().ifBlank { "Episode ${dto.episodeNum}" },
                    containerExtension = dto.containerExtension.ifBlank { "mp4" },
                    plot = dto.info?.plot?.takeIf { it.isNotBlank() },
                    durationSecs = dto.info?.durationSecs ?: 0,
                    thumbnailUrl = dto.info?.movieImage.sanitizeUrl(),
                )
            }
        }.sortedWith(compareBy({ it.season }, { it.episodeNumber }))
    }

    // -----------------------------------------------------------------------
    // EPG (get_short_epg)
    // -----------------------------------------------------------------------

    /**
     * Wandelt Kurz-EPG-Einträge um. Titel und Beschreibung sind bei Xtream
     * Base64-kodiert – allerdings nicht immer, deshalb die Heuristik in
     * [decodeBase64OrPlain].
     */
    fun toEpgPrograms(listings: List<XtreamEpgListingDto>, epgChannelId: String): List<EpgProgram> =
        listings.mapNotNull { dto ->
            val start = TimeUnit.SECONDS.toMillis(dto.startTimestamp)
            val end = TimeUnit.SECONDS.toMillis(dto.stopTimestamp)
            if (start <= 0L || end <= start) return@mapNotNull null

            EpgProgram(
                epgChannelId = epgChannelId,
                startAt = start,
                endAt = end,
                title = decodeBase64OrPlain(dto.title).ifBlank { "Unbekannte Sendung" },
                description = decodeBase64OrPlain(dto.description).takeIf { it.isNotBlank() },
            )
        }.sortedBy { it.startAt }

    /**
     * Dekodiert Base64, fällt aber auf den Originaltext zurück, wenn das
     * Ergebnis offensichtlich Müll ist (Panels ohne Kodierung).
     */
    fun decodeBase64OrPlain(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            // Enthält das Ergebnis Steuerzeichen, war es kein Base64-Text.
            val looksBinary = decoded.any { it.code < 0x09 || (it.code in 0x0E..0x1F) }
            if (decoded.isBlank() || looksBinary) trimmed else decoded
        } catch (_: IllegalArgumentException) {
            trimmed
        }
    }

    // -----------------------------------------------------------------------
    // Gemeinsame Helfer
    // -----------------------------------------------------------------------

    /** Entfernt doppelte Leerzeichen und unsichtbare Zeichen aus Panel-Namen. */
    private fun String.cleanName(): String =
        replace('\u00A0', ' ') // geschütztes Leerzeichen
            .replace('\u200B', ' ') // Zero-Width-Space
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Verwirft Platzhalter und relative Pfade, die kein Bildlader auflösen kann. */
    private fun String?.sanitizeUrl(): String? {
        val value = this?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.lowercase() in PLACEHOLDER_VALUES) return null
        return value.takeIf { it.startsWith("http", ignoreCase = true) }
    }
}
