package de.qwikster.player.data.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import de.qwikster.player.MainActivity
import de.qwikster.player.R
import de.qwikster.player.data.local.RecordingState
import de.qwikster.player.data.repository.PlaylistSyncer
import de.qwikster.player.data.repository.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject

/**
 * Nimmt einen laufenden Sender in eine Datei auf.
 *
 * **Warum ein Vordergrunddienst.** Der Zuschauer soll den Fernseher
 * ausschalten können, während die Aufnahme läuft. Genau das ist der Grund
 * für die gesamte Bauform: Ein Vordergrunddienst ist das Einzige, was
 * Android weiterlaufen lässt, sobald die Oberfläche aus dem Blick gerät.
 * Eine Coroutine im ViewModel wäre spätestens beim Verlassen des
 * Bildschirms beendet, ein gewöhnlicher Hintergrunddienst binnen Minuten.
 *
 * Dazu kommen zwei Sperren, die einzeln nichts nützen:
 * - **Wake-Lock** (partiell): hält den Prozessor wach. Ohne ihn schläft das
 *   Gerät ein, sobald der Bildschirm dunkel ist, und der Datenstrom reißt ab.
 * - **WLAN-Lock**: hält die Funkverbindung wach. Fire-TV-Sticks schalten das
 *   WLAN im Ruhezustand sonst in einen Sparmodus, in dem der Durchsatz für
 *   einen laufenden Stream nicht mehr reicht.
 *
 * **Wie aufgenommen wird.** Ein Live-Stream ist bei IPTV fast immer ein
 * Transportstrom (`.ts`), der endlos weiterläuft. Aufnehmen heißt hier
 * deshalb nichts anderes, als die Bytes mitzuschreiben – kein Umkodieren,
 * keine Qualitätsverluste, kaum Rechenlast. Das Ergebnis ist eine Datei, die
 * jeder Player abspielt, auch der eingebaute.
 *
 * Ein Transportstrom hat keinen Dateikopf, der am Ende ergänzt werden
 * müsste: Bricht die Aufnahme mittendrin ab (Stromausfall, Absturz), bleibt
 * das bis dahin Geschriebene abspielbar. Das ist der Grund, warum hier
 * bewusst nicht in einen Container wie MP4 gemuxt wird.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var repository: RecordingRepository

    @Inject lateinit var httpClient: OkHttpClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Laufende Aufnahmen: Kennung -> Auftrag, damit sich einzelne stoppen lassen. */
    private val jobs = mutableMapOf<Long, Job>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getLongExtra(EXTRA_RECORDING_ID, 0L)
                val url = intent.getStringExtra(EXTRA_URL).orEmpty()
                if (id != 0L && url.isNotBlank()) start(id, url)
            }

            ACTION_STOP -> {
                val id = intent.getLongExtra(EXTRA_RECORDING_ID, 0L)
                if (id == 0L) stopAll() else stop(id)
            }
        }
        // Nicht neu starten, wenn Android den Dienst abräumt: Ohne die
        // ursprüngliche Absicht wüsste er nicht, was er aufnehmen soll, und
        // eine halbe Aufnahme lässt sich nicht sinnvoll fortsetzen.
        return START_NOT_STICKY
    }

    private fun start(id: Long, url: String) {
        if (jobs.containsKey(id)) return

        startForegroundCompat()
        acquireLocks()

        jobs[id] = scope.launch {
            var written = 0L
            var failure: String? = null
            try {
                val recording = repository.get(id) ?: return@launch
                val target = File(recording.filePath)
                target.parentFile?.mkdirs()

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", PlaylistSyncer.USER_AGENT)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP-${response.code}")
                    val body = response.body ?: error("EMPTY_BODY")

                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(COPY_BUFFER)
                            var lastReport = 0L
                            while (true) {
                                // Beendet den Kopiervorgang, sobald der
                                // Auftrag abgebrochen wurde – ohne diese
                                // Prüfung liefe die Schleife auf einem
                                // endlosen Strom ewig weiter.
                                ensureActive()

                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read

                                // Geplantes Ende erreicht?
                                val plannedEnd = recording.plannedEndAt
                                if (plannedEnd > 0 && System.currentTimeMillis() >= plannedEnd) break

                                // Fortschritt nur gelegentlich sichern: Ein
                                // Datenbankschreibvorgang je Puffer wären
                                // hunderte pro Sekunde.
                                if (written - lastReport > REPORT_EVERY_BYTES) {
                                    lastReport = written
                                    repository.updateProgress(id, written)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ein Abbruch von außen ist kein Fehler, sondern der
                // Stopp-Knopf – der darf die Aufnahme nicht als
                // fehlgeschlagen markieren.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Aufnahme $id abgebrochen", e)
                failure = e.message
            } finally {
                repository.finish(
                    id = id,
                    sizeBytes = written,
                    state = if (failure == null) RecordingState.DONE else RecordingState.FAILED,
                    errorMessage = failure,
                )
                jobs.remove(id)
                if (jobs.isEmpty()) shutdown()
            }
        }
    }

    private fun stop(id: Long) {
        jobs[id]?.cancel()
        jobs.remove(id)
        if (jobs.isEmpty()) shutdown()
    }

    private fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        shutdown()
    }

    private fun shutdown() {
        releaseLocks()
        stopForegroundCompat()
        stopSelf()
    }

    // -----------------------------------------------------------------------
    // Sperren und Benachrichtigung
    // -----------------------------------------------------------------------

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wifi.createWifiLock(mode, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundCompat() {
        createChannel()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Bewusst nichtssagend: Android **erzwingt** bei einem
        // Vordergrunddienst eine Benachrichtigung, sie lässt sich nicht
        // abschalten. Was sich abschalten lässt, ist alles, was sie
        // auffällig macht – Titel ohne Hinweis auf eine Aufnahme, kein
        // Sendungsname, kein Stopp-Knopf, kein Ton, kein Aufpoppen. Übrig
        // bleibt ein stiller Eintrag, wie ihn jede App im Hintergrund
        // erzeugt. Gestoppt wird in der App selbst.
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openApp)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            // IMPORTANCE_MIN ist die niedrigste Stufe, die für einen
            // Vordergrunddienst zulässig ist: kein Ton, kein Aufpoppen, in
            // der Leiste zusammengeklappt. Der Kanalname taucht in den
            // Systemeinstellungen auf und nennt deshalb ebenfalls keine
            // Aufnahme.
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.recording_notification_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "qwikster_recording"
        private const val NOTIFICATION_ID = 4711
        private const val WAKE_TAG = "Qwikster::Recording"
        private const val COPY_BUFFER = 64 * 1024

        /** Zwischenstand alle 4 MB sichern – oft genug für eine Größenanzeige. */
        private const val REPORT_EVERY_BYTES = 4L * 1024 * 1024

        /**
         * Obergrenze für den Wake-Lock. Android verlangt seit Version 10
         * eine Frist; zwölf Stunden liegen weit über jeder Sendung und
         * verhindern zugleich, dass ein hängengebliebener Lock das Gerät
         * dauerhaft wachhält.
         */
        private const val MAX_LOCK_MS = 12L * 60 * 60 * 1000

        const val ACTION_START = "de.qwikster.player.RECORDING_START"
        const val ACTION_STOP = "de.qwikster.player.RECORDING_STOP"
        const val EXTRA_RECORDING_ID = "recordingId"
        const val EXTRA_URL = "url"
    }
}
