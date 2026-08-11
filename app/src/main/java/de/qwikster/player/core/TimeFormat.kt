package de.qwikster.player.core

import android.content.Context
import de.qwikster.player.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Zeitformatierung für die Oberfläche.
 *
 * Alle Zeitstempel in der App sind UTC-Millisekunden; erst hier wird in die
 * Gerätezeitzone umgerechnet. Die Formatter werden zwischengespeichert, weil
 * sie in Listen sehr häufig aufgerufen werden und `DateTimeFormatter` (anders
 * als `SimpleDateFormat`) threadsicher ist.
 */
object TimeFormat {

    /**
     * Zwischenspeicher der Formatter, gebunden an die aktive Sprache.
     *
     * Wochentags- und Monatsnamen kommen aus der [Locale] – ein fest
     * verdrahtetes `Locale.GERMANY` (wie bis hierher) hätte "Mo"/"August"
     * auch dann geliefert, wenn die App auf Türkisch läuft. Weil die
     * Sprache zur Laufzeit umschaltbar ist, wird der Zwischenspeicher
     * verworfen, sobald sich die Standard-Locale ändert.
     */
    private class Formatters(val locale: Locale) {
        val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        val dayShort: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM.", locale)
        val dayLong: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd. MMMM", locale)
    }

    @Volatile
    private var cached: Formatters = Formatters(Locale.getDefault())

    private val formatters: Formatters
        get() {
            val current = Locale.getDefault()
            val existing = cached
            if (existing.locale == current) return existing
            return Formatters(current).also { cached = it }
        }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun toLocal(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    /** "20:15" */
    fun clock(epochMillis: Long): String = formatters.clock.format(toLocal(epochMillis))

    /** "20:15 – 21:45" */
    fun range(startMillis: Long, endMillis: Long): String =
        "${clock(startMillis)} – ${clock(endMillis)}"

    /** "Mo 09.08." */
    fun dayShort(epochMillis: Long): String = formatters.dayShort.format(toLocal(epochMillis))

    /** "Montag, 09. August" */
    fun dayLong(epochMillis: Long): String = formatters.dayLong.format(toLocal(epochMillis))

    /** "90 Min." bzw. "1 Std. 30 Min." */
    fun duration(context: Context, durationMillis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        if (totalMinutes < 60) return context.getString(R.string.duration_minutes, totalMinutes)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0L) {
            context.getString(R.string.duration_hours, hours)
        } else {
            context.getString(R.string.duration_hours_minutes, hours, minutes)
        }
    }

    /** "noch 23 Min." – Restlaufzeit der aktuellen Sendung. */
    fun remaining(
        context: Context,
        endMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): String {
        val remaining = (endMillis - now).coerceAtLeast(0L)
        return context.getString(R.string.duration_remaining, duration(context, remaining))
    }

    /** Wiedergabeposition im Player: "1:23:45" bzw. "23:45". */
    fun position(positionMillis: Long): String {
        val totalSeconds = (positionMillis / 1000).coerceAtLeast(0L)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Beginn des Tages, in dem [epochMillis] liegt – Basis für die Guide-Tagesnavigation. */
    fun startOfDay(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Beginn der laufenden halben Stunde – Startpunkt des EPG-Rasters.
     *
     * Bewusst über die lokale Zeit gerechnet statt per Modulo auf dem
     * Zeitstempel: es gibt Zeitzonen mit 45-Minuten-Versatz (z. B. Kathmandu),
     * dort läge das Raster sonst dauerhaft schief.
     */
    fun floorToHalfHour(epochMillis: Long): Long {
        val zoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return zoned
            .withMinute(if (zoned.minute < 30) 0 else 30)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
    }

    fun today(): LocalDate = LocalDate.now(zone)
}
