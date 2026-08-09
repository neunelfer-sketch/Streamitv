package de.neunelf.player.data.remote.epg

import android.util.Xml
import de.neunelf.player.data.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/** Kanal-Kopfdaten aus einem `<channel>`-Element. */
data class XmltvChannel(
    val id: String,
    val displayNames: List<String>,
    val iconUrl: String? = null,
)

/**
 * SAX-artiger Parser für XMLTV-EPG-Dateien.
 *
 * Warum kein DOM/kein Objekt-Baum: eine EPG-Datei für 2.000 Sender × 7 Tage
 * hat schnell 150–400 MB entpackt. Der Parser hier hält deshalb immer nur
 * *eine* Sendung im Speicher und gibt sie sofort per Callback weiter – der
 * Aufrufer schreibt sie gebündelt in die Datenbank.
 *
 * Unterstützt wird der übliche XMLTV-Dialekt:
 * ```xml
 * <tv>
 *   <channel id="rtl.de">
 *     <display-name>RTL</display-name>
 *     <icon src="http://…/rtl.png"/>
 *   </channel>
 *   <programme start="20250809180000 +0200" stop="20250809190000 +0200" channel="rtl.de">
 *     <title lang="de">Wer wird Millionär?</title>
 *     <desc lang="de">Quizshow mit …</desc>
 *     <category lang="de">Show</category>
 *     <episode-num system="xmltv_ns">3.11.</episode-num>
 *   </programme>
 * </tv>
 * ```
 */
object XmltvParser {

    /**
     * Liest die Datei und meldet jedes gefundene Element per Callback.
     *
     * @param relevantChannelIds Wenn nicht `null`, werden Sendungen fremder
     *        Sender sofort verworfen. Das spart bei großen Sammel-EPGs
     *        (z. B. "alle europäischen Sender") den Großteil der Arbeit.
     * @param onChannel wird für jedes `<channel>` aufgerufen.
     * @param onProgram wird für jedes `<programme>` aufgerufen.
     * @return Anzahl der gemeldeten Sendungen.
     */
    @Throws(IOException::class, XmlPullParserException::class)
    fun parse(
        input: InputStream,
        relevantChannelIds: Set<String>? = null,
        onChannel: (XmltvChannel) -> Unit = {},
        onProgram: (EpgProgram) -> Unit,
    ): Int {
        // Viele Panels liefern xmltv.php gzip-komprimiert, ohne es im Header
        // anzugeben – deshalb erkennen wir es an der Magic Number.
        val stream = wrapIfGzip(input).buffered(64 * 1024)

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }

        var programCount = 0
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)?.let(onChannel)
                    "programme" -> {
                        val program = readProgramme(parser, relevantChannelIds)
                        if (program != null) {
                            onProgram(program)
                            programCount++
                        }
                    }
                }
            }
            event = parser.next()
        }
        return programCount
    }

    // -----------------------------------------------------------------------
    // Element-Reader
    // -----------------------------------------------------------------------

    /**
     * Liest ein `<channel>`-Element. Der Parser steht beim Aufruf auf dessen
     * START_TAG und beim Rücksprung auf dem zugehörigen END_TAG.
     */
    private fun readChannel(parser: XmlPullParser): XmltvChannel? {
        val id = parser.getAttributeValue(null, "id")?.trim().orEmpty()
        val displayNames = mutableListOf<String>()
        var icon: String? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "display-name" -> parser.readTextAndCloseTag()
                    .takeIf { it.isNotBlank() }
                    ?.let { displayNames += it }

                "icon" -> {
                    icon = icon ?: parser.getAttributeValue(null, "src")?.trim()
                    parser.skipElement()
                }

                else -> parser.skipElement()
            }
        }

        return if (id.isBlank()) null else XmltvChannel(id, displayNames, icon?.takeIf { it.isNotBlank() })
    }

    /**
     * Liest ein `<programme>`-Element.
     *
     * Uninteressante Sender werden über [skipElement] übersprungen, ohne
     * dass Strings für Titel/Beschreibung allokiert werden – das ist der
     * Hauptgrund, warum große Sammel-EPGs hier trotzdem schnell durchlaufen.
     */
    private fun readProgramme(parser: XmlPullParser, relevantChannelIds: Set<String>?): EpgProgram? {
        val channelId = parser.getAttributeValue(null, "channel")?.trim().orEmpty()
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))

        val irrelevant = channelId.isEmpty() ||
            (relevantChannelIds != null && channelId !in relevantChannelIds)

        var title = ""
        var description: String? = null
        var category: String? = null
        var icon: String? = null
        var season: Int? = null
        var episode: Int? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (irrelevant) {
                parser.skipElement()
                continue
            }
            when (parser.name) {
                // Der erste Treffer gewinnt: XMLTV erlaubt denselben Tag mehrfach
                // (eine Variante je Sprache), und wir wollen nur eine davon.
                "title" -> parser.readTextAndCloseTag().let { if (title.isEmpty()) title = it }
                "desc" -> parser.readTextAndCloseTag().let { if (description == null && it.isNotBlank()) description = it }
                "category" -> parser.readTextAndCloseTag().let { if (category == null && it.isNotBlank()) category = it }

                "icon" -> {
                    icon = icon ?: parser.getAttributeValue(null, "src")?.trim()
                    parser.skipElement()
                }

                "episode-num" -> {
                    val system = parser.getAttributeValue(null, "system").orEmpty()
                    val value = parser.readTextAndCloseTag()
                    parseEpisodeNum(system, value)?.let { (parsedSeason, parsedEpisode) ->
                        season = season ?: parsedSeason
                        episode = episode ?: parsedEpisode
                    }
                }

                else -> parser.skipElement()
            }
        }

        if (irrelevant || start <= 0L || stop <= start) return null

        return EpgProgram(
            epgChannelId = channelId,
            startAt = start,
            endAt = stop,
            title = title.ifBlank { "Unbekannte Sendung" },
            description = description,
            category = category,
            iconUrl = icon?.takeIf { it.startsWith("http", ignoreCase = true) },
            season = season,
            episode = episode,
        )
    }

    /**
     * Liest den Textinhalt des aktuellen Elements und lässt den Parser auf
     * dessen END_TAG stehen. Funktioniert auch für leere Elemente (`<desc/>`).
     */
    private fun XmlPullParser.readTextAndCloseTag(): String {
        var result = ""
        if (next() == XmlPullParser.TEXT) {
            result = text?.trim().orEmpty()
            nextTag()
        }
        return result
    }

    /**
     * Überspringt das aktuelle Element samt aller Kindelemente.
     * Vorbedingung: Parser steht auf START_TAG; danach steht er auf dem
     * passenden END_TAG.
     */
    private fun XmlPullParser.skipElement() {
        check(eventType == XmlPullParser.START_TAG) { "skipElement() erwartet START_TAG" }
        var depth = 1
        while (depth != 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    // -----------------------------------------------------------------------
    // Zeit- und Episodenformat
    // -----------------------------------------------------------------------

    /**
     * Wandelt einen XMLTV-Zeitstempel in UTC-Millisekunden.
     *
     * Erlaubte Formen (die Zeitzone ist optional, Sekunden ebenfalls):
     * ```
     * 20250809180000 +0200
     * 20250809180000+0200
     * 202508091800
     * 20250809180000 GMT
     * ```
     *
     * Bewusst von Hand geparst statt mit `SimpleDateFormat`: bei mehreren
     * hunderttausend Sendungen ist der Unterschied auf einem Fire TV Stick
     * mehrere Sekunden.
     */
    fun parseXmltvTime(value: String?): Long {
        val raw = value?.trim() ?: return 0L
        if (raw.length < 12) return 0L

        // Ziffernblock am Anfang isolieren.
        var digitsEnd = 0
        while (digitsEnd < raw.length && raw[digitsEnd].isDigit()) digitsEnd++
        if (digitsEnd < 12) return 0L

        val digits = raw.substring(0, digitsEnd)
        val year = digits.substring(0, 4).toIntOrNull() ?: return 0L
        val month = digits.substring(4, 6).toIntOrNull() ?: return 0L
        val day = digits.substring(6, 8).toIntOrNull() ?: return 0L
        val hour = digits.substring(8, 10).toIntOrNull() ?: return 0L
        val minute = digits.substring(10, 12).toIntOrNull() ?: return 0L
        val second = if (digits.length >= 14) digits.substring(12, 14).toIntOrNull() ?: 0 else 0

        val utcMillis = daysFromCivil(year, month, day) * 86_400_000L +
            hour * 3_600_000L + minute * 60_000L + second * 1_000L

        // Offset-Teil ("+0200", "-0530", "Z", "GMT" …) abziehen.
        val offsetMillis = parseOffset(raw.substring(digitsEnd).trim())
        return utcMillis - offsetMillis
    }

    private fun parseOffset(suffix: String): Long {
        if (suffix.isEmpty()) return 0L
        if (suffix.equals("Z", true) || suffix.equals("UTC", true) || suffix.equals("GMT", true)) return 0L

        val sign = when (suffix[0]) {
            '+' -> 1
            '-' -> -1
            else -> return 0L
        }
        val digits = suffix.drop(1).filter(Char::isDigit)
        if (digits.length < 4) return 0L
        val hours = digits.substring(0, 2).toIntOrNull() ?: return 0L
        val minutes = digits.substring(2, 4).toIntOrNull() ?: return 0L
        return sign * (hours * 3_600_000L + minutes * 60_000L)
    }

    /**
     * Tage seit 1970-01-01 nach Howard Hinnants `days_from_civil`-Algorithmus –
     * kalenderkorrekt inkl. Schaltjahren und ohne Objekt-Allokation.
     */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                        // [0, 399]
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy                // [0, 146096]
        return era * 146_097L + doe - 719_468L
    }

    /**
     * Liest Staffel/Episode. Zwei verbreitete Systeme:
     * - `xmltv_ns`: `"2.11.0/2"` – **nullbasiert**, Teile können leer sein.
     * - `onscreen`: `"S03E12"` – menschenlesbar, direkt übernehmbar.
     */
    fun parseEpisodeNum(system: String, value: String): Pair<Int?, Int?>? {
        val raw = value.trim()
        if (raw.isEmpty()) return null

        return when {
            system.equals("xmltv_ns", ignoreCase = true) -> {
                val parts = raw.split('.')
                val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
                val episode = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
                if (season == null && episode == null) null else season to episode
            }

            else -> {
                val match = Regex("""[Ss](\d{1,3})\s*[Ee](\d{1,4})""").find(raw) ?: return null
                match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
            }
        }
    }

    // -----------------------------------------------------------------------
    // GZIP-Erkennung
    // -----------------------------------------------------------------------

    /** Prüft die GZIP-Magic-Number (0x1F 0x8B) und packt den Stream ggf. aus. */
    private fun wrapIfGzip(input: InputStream): InputStream {
        val pushback = PushbackInputStream(input, 2)
        val header = ByteArray(2)
        val read = pushback.read(header, 0, 2)
        if (read > 0) pushback.unread(header, 0, read)

        val isGzip = read == 2 &&
            (header[0].toInt() and 0xFF) == 0x1F &&
            (header[1].toInt() and 0xFF) == 0x8B
        return if (isGzip) GZIPInputStream(pushback) else pushback
    }
}
