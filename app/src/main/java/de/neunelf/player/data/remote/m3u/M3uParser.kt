package de.neunelf.player.data.remote.m3u

import de.neunelf.player.data.model.Category
import de.neunelf.player.data.model.Channel
import de.neunelf.player.data.model.Movie
import de.neunelf.player.data.model.StreamKind
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.math.absoluteValue

/**
 * Ein einzelner Eintrag aus einer M3U-Datei.
 * Bleibt bewusst nah am Dateiformat; die Umwandlung in [Channel]/[Movie]
 * macht [M3uParser.toChannels] bzw. [M3uParser.toMovies].
 */
data class M3uEntry(
    val url: String,
    val title: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val group: String? = null,
    /** `tvg-chno` bzw. `channel-number`: der gewünschte Senderplatz. */
    val channelNumber: Int = 0,
    /** Aus `#EXTVLCOPT:http-user-agent=` bzw. `#EXTHTTP:`. */
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Heuristisch bestimmt (Live vs. Film vs. Serie). */
    val kind: StreamKind = StreamKind.LIVE,
)

/** Ergebnis eines Parse-Laufs. */
data class M3uPlaylist(
    val entries: List<M3uEntry>,
    /** Aus `url-tvg` / `x-tvg-url` im `#EXTM3U`-Header – oft mehrere, komma-getrennt. */
    val epgUrls: List<String> = emptyList(),
)

/**
 * Parser für M3U- und M3U8-Playlists (inkl. des verbreiteten `m3u_plus`-Dialekts).
 *
 * Aufbau einer typischen Zeile:
 * ```
 * #EXTM3U url-tvg="http://server/xmltv.php?username=x&password=y"
 * #EXTINF:-1 tvg-id="rtl.de" tvg-name="RTL HD" tvg-logo="http://…/rtl.png" group-title="Deutschland",RTL HD
 * #EXTVLCOPT:http-user-agent=VLC/3.0.20
 * http://server:8080/live/user/pass/12345.ts
 * ```
 *
 * Umsetzungsdetails, die in der Praxis wichtig sind:
 * - **Streaming statt String-Split:** Playlists mit 50.000+ Sendern sind
 *   schnell 20 MB groß. Zeilenweises Lesen hält den Speicher auf einem
 *   Fire TV Stick (oft nur ~1 GB RAM) niedrig.
 * - **Attribute vor dem Komma, Anzeigename danach.** Der Anzeigename kann
 *   selbst Kommas enthalten, deshalb wird am *ersten* Komma nach dem letzten
 *   Attribut getrennt.
 * - Unbekannte `#`-Direktiven werden ignoriert statt als Fehler behandelt.
 */
object M3uParser {

    /** `key="value"` – Werte dürfen Leerzeichen und Sonderzeichen enthalten. */
    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    private const val EXTM3U = "#EXTM3U"
    private const val EXTINF = "#EXTINF"
    private const val EXTGRP = "#EXTGRP"
    private const val EXTVLCOPT = "#EXTVLCOPT"
    private const val EXTHTTP = "#EXTHTTP"
    private const val KODIPROP = "#KODIPROP"

    /**
     * Liest eine Playlist aus einem Stream.
     *
     * @param gzipped `true`, wenn der Server die Datei gzip-komprimiert liefert
     *        (an `Content-Encoding` oder der Endung `.gz` erkennbar).
     */
    fun parse(input: InputStream, gzipped: Boolean = false): M3uPlaylist {
        val stream = if (gzipped) GZIPInputStream(input) else input
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8), DEFAULT_BUFFER_SIZE * 8)
            .use { parse(it) }
    }

    fun parse(text: String): M3uPlaylist = parse(text.reader().buffered())

    fun parse(reader: BufferedReader): M3uPlaylist {
        val entries = ArrayList<M3uEntry>(1024)
        var epgUrls: List<String> = emptyList()

        // Zustand des gerade in Arbeit befindlichen Eintrags.
        var pendingInfo: PendingEntry? = null

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachLine

            when {
                // Header: enthält ggf. die EPG-Quelle.
                line.startsWith(EXTM3U, ignoreCase = true) -> {
                    epgUrls = extractEpgUrls(line)
                }

                line.startsWith(EXTINF, ignoreCase = true) -> {
                    pendingInfo = parseExtInf(line)
                }

                // Gruppenangabe als eigene Zeile (ältere Exporter).
                line.startsWith(EXTGRP, ignoreCase = true) -> {
                    val group = line.substringAfter(':', "").trim()
                    if (group.isNotEmpty()) pendingInfo = pendingInfo?.copy(group = group)
                }

                line.startsWith(EXTVLCOPT, ignoreCase = true) -> {
                    val option = line.substringAfter(':', "")
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    pendingInfo = when (key) {
                        "http-user-agent" -> pendingInfo?.copy(userAgent = value)
                        "http-referrer", "http-referer" -> pendingInfo?.copy(referrer = value)
                        else -> pendingInfo
                    }
                }

                line.startsWith(EXTHTTP, ignoreCase = true) -> {
                    // JSON-artig: {"User-Agent":"…","Referer":"…"} – simpel ausgelesen.
                    val payload = line.substringAfter(':', "")
                    pendingInfo = pendingInfo?.copy(
                        userAgent = payload.extractJsonValue("User-Agent") ?: pendingInfo?.userAgent,
                        referrer = payload.extractJsonValue("Referer") ?: pendingInfo?.referrer,
                    )
                }

                // Kodi-Properties (DRM etc.) werden aktuell nicht ausgewertet.
                line.startsWith(KODIPROP, ignoreCase = true) -> Unit

                // Sonstige Kommentare überspringen.
                line.startsWith("#") -> Unit

                // Alles andere ist die URL des zuvor beschriebenen Eintrags.
                else -> {
                    val info = pendingInfo
                    if (info != null) {
                        entries += info.toEntry(line)
                        pendingInfo = null
                    }
                    // URL ohne vorheriges #EXTINF -> unbrauchbar, wird verworfen.
                }
            }
        }

        return M3uPlaylist(entries = entries, epgUrls = epgUrls)
    }

    // -----------------------------------------------------------------------
    // Umwandlung in Domänenmodelle
    // -----------------------------------------------------------------------

    /** Filtert die Live-Einträge heraus und nummeriert sie durch. */
    fun toChannels(playlist: M3uPlaylist, playlistId: Long): List<Channel> =
        playlist.entries
            .filter { it.kind == StreamKind.LIVE }
            .mapIndexed { index, entry ->
                Channel(
                    // M3U kennt keine Stream-IDs -> stabile ID aus der URL ableiten,
                    // damit Favoriten und Verlauf einen Reload überleben.
                    streamId = stableId(entry.url),
                    playlistId = playlistId,
                    name = entry.title,
                    logoUrl = entry.logoUrl,
                    categoryId = entry.group?.let { stableId(it) },
                    epgChannelId = entry.tvgId ?: entry.tvgName,
                    number = if (entry.channelNumber > 0) entry.channelNumber else index + 1,
                    directUrl = entry.url,
                    containerExtension = entry.url.substringAfterLast('.', "ts").take(5),
                )
            }

    fun toMovies(playlist: M3uPlaylist, playlistId: Long): List<Movie> =
        playlist.entries
            .filter { it.kind == StreamKind.VOD }
            .map { entry ->
                Movie(
                    streamId = stableId(entry.url),
                    playlistId = playlistId,
                    name = entry.title,
                    posterUrl = entry.logoUrl,
                    categoryId = entry.group?.let { stableId(it) },
                    containerExtension = entry.url.substringAfterLast('.', "mp4").take(5),
                    directUrl = entry.url,
                )
            }

    /** Leitet die Kategorien aus den `group-title`-Werten ab. */
    fun toCategories(playlist: M3uPlaylist, playlistId: Long, kind: StreamKind): List<Category> =
        playlist.entries
            .filter { it.kind == kind }
            .mapNotNull { it.group?.takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
            .mapIndexed { index, group ->
                Category(
                    id = stableId(group),
                    name = group,
                    kind = kind,
                    playlistId = playlistId,
                    sortOrder = index,
                )
            }

    /**
     * Deterministische ID aus einem beliebigen String.
     * Bewusst kein Zufalls-/Zeitanteil: dieselbe URL muss nach jedem Reload
     * dieselbe ID ergeben, sonst gehen Favoriten verloren.
     */
    fun stableId(value: String): String = value.hashCode().absoluteValue.toString(36)

    // -----------------------------------------------------------------------
    // Interna
    // -----------------------------------------------------------------------

    private data class PendingEntry(
        val title: String,
        val tvgId: String?,
        val tvgName: String?,
        val logoUrl: String?,
        val group: String?,
        val channelNumber: Int,
        val userAgent: String? = null,
        val referrer: String? = null,
    ) {
        fun toEntry(url: String) = M3uEntry(
            url = url,
            title = title,
            tvgId = tvgId,
            tvgName = tvgName,
            logoUrl = logoUrl,
            group = group,
            channelNumber = channelNumber,
            userAgent = userAgent,
            referrer = referrer,
            kind = guessKind(url, group),
        )
    }

    /**
     * Zerlegt `#EXTINF:-1 key="value" …,Anzeigename`.
     *
     * Der Anzeigename beginnt nach dem letzten Komma, das *außerhalb* von
     * Anführungszeichen steht – sonst zerreißt ein `group-title="Sport, DE"`
     * den Namen.
     */
    private fun parseExtInf(line: String): PendingEntry {
        val payload = line.substringAfter(':', "")
        val splitIndex = indexOfNameSeparator(payload)
        val attributePart = if (splitIndex >= 0) payload.substring(0, splitIndex) else payload
        val displayName = if (splitIndex >= 0) payload.substring(splitIndex + 1).trim() else ""

        val attributes = ATTRIBUTE_REGEX.findAll(attributePart)
            .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2].trim() }

        val tvgName = attributes["tvg-name"]?.takeIf { it.isNotBlank() }
        return PendingEntry(
            title = displayName.ifBlank { tvgName.orEmpty() }.ifBlank { "Unbenannt" },
            tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = tvgName,
            logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.startsWith("http") },
            group = (attributes["group-title"] ?: attributes["group"])?.takeIf { it.isNotBlank() },
            channelNumber = (attributes["tvg-chno"] ?: attributes["channel-number"])?.toIntOrNull() ?: 0,
        )
    }

    /** Index des Kommas, das Attribute vom Anzeigenamen trennt. */
    private fun indexOfNameSeparator(payload: String): Int {
        var inQuotes = false
        var lastComma = -1
        for (index in payload.indices) {
            when (payload[index]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) lastComma = index
            }
        }
        return lastComma
    }

    /** Liest `url-tvg`/`x-tvg-url` aus der `#EXTM3U`-Zeile. */
    private fun extractEpgUrls(headerLine: String): List<String> {
        val attributes = ATTRIBUTE_REGEX.findAll(headerLine)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val raw = attributes["url-tvg"] ?: attributes["x-tvg-url"] ?: return emptyList()
        return raw.split(',', ';')
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
    }

    /**
     * Rät den Inhaltstyp. Xtream-Exporte kodieren ihn im Pfad
     * (`/live/`, `/movie/`, `/series/`); sonst hilft die Gruppenbezeichnung.
     */
    private fun guessKind(url: String, group: String?): StreamKind {
        val lowerUrl = url.lowercase()
        return when {
            "/movie/" in lowerUrl || "/movies/" in lowerUrl -> StreamKind.VOD
            "/series/" in lowerUrl -> StreamKind.SERIES
            "/live/" in lowerUrl -> StreamKind.LIVE
            else -> {
                val lowerGroup = group?.lowercase().orEmpty()
                when {
                    listOf("vod", "film", "movie", "kino").any { it in lowerGroup } -> StreamKind.VOD
                    listOf("serie", "series", "staffel", "season").any { it in lowerGroup } -> StreamKind.SERIES
                    // Endet auf eine Container-Endung -> mit hoher Wahrscheinlichkeit VOD.
                    lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".mkv") -> StreamKind.VOD
                    else -> StreamKind.LIVE
                }
            }
        }
    }

    /** Sehr einfacher Extraktor für `"key":"value"` – reicht für `#EXTHTTP`. */
    private fun String.extractJsonValue(key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(this)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}
