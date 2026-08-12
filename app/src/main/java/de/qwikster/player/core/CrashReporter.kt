package de.qwikster.player.core

import android.content.Context
import android.os.Build
import android.os.Process
import de.qwikster.player.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Hält den letzten Absturz fest.
 *
 * Auf einem Fire TV Stick gibt es keinen Weg an ein Logcat: Der Zuschauer
 * sieht die App verschwinden und kann nur "sie stürzt manchmal ab" melden.
 * Damit lässt sich nichts anfangen – ein Absturz kann aus dem Player, aus
 * einer Datenbankabfrage oder aus dem Bildlader kommen.
 *
 * Deshalb schreibt die App den Fehler selbst weg, bevor der Prozess endet,
 * und zeigt ihn beim nächsten Start unter *Einstellungen → Letzter Absturz*
 * an. Genau eine Datei, immer die neueste: Ein Verlauf brächte hier nichts,
 * der erste Absturz nach einem Update ist der interessante.
 *
 * **Grenze, die man kennen muss:** Erfasst wird nur, was als Java-Ausnahme
 * ankommt. Stürzt der Video-Decoder des Geräts selbst ab (nativer Absturz),
 * endet der Prozess sofort, ohne dass hier noch Code läuft. Bleibt die Datei
 * nach einem Absturz also leer, ist das kein Fehlschlag der Aufzeichnung
 * sondern selbst schon die Antwort: dann lag es nicht am App-Code.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    /**
     * Obergrenze für den gespeicherten Text. Ein Stapelabzug ist selten
     * länger; die Grenze schützt vor Ketten aus hunderten "Caused by",
     * wie sie eine überlaufende Rekursion erzeugt.
     */
    private const val MAX_CHARS = 20_000

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Der Prozess ist bereits verloren – hier darf nichts mehr
            // scheitern, sonst verschluckt eine zweite Ausnahme die erste.
            runCatching { write(appContext, thread, error) }

            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // Ohne Vorgänger (kommt auf einzelnen Fire-OS-Fassungen vor)
                // müssen wir selbst beenden: Ein abgefangener Absturz ohne
                // Prozessende hinterlässt eine Oberfläche, die auf keine
                // Taste mehr reagiert – für den Zuschauer schlimmer als ein
                // klarer Neustart.
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Der gespeicherte Bericht, oder `null`, wenn es keinen gibt. */
    fun read(context: Context): String? =
        runCatching {
            file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stackTrace = StringWriter().also { writer ->
            PrintWriter(writer).use { error.printStackTrace(it) }
        }.toString()

        val report = buildString {
            appendLine("Qwikster ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(TIMESTAMP.format(Date()))
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(stackTrace)
        }

        file(context).writeText(report.take(MAX_CHARS))
    }

    /** Fester Ort statt Gerätesprache: Der Bericht wird gemeldet, nicht gelesen. */
    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
