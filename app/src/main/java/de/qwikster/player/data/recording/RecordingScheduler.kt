package de.qwikster.player.data.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.data.repository.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Weckt die App zur Sendezeit einer vorgemerkten Aufnahme.
 *
 * Bewusst der [AlarmManager] und kein Dienst, der die Zeit abwartet: Bis zu
 * zehn Stunden lang einen Vordergrunddienst am Leben zu halten, nur um auf
 * eine Uhrzeit zu warten, wäre reine Verschwendung – und Android beendet
 * genau solche Dienste bei Speichermangel als Erstes. Ein Wecker kostet
 * nichts, bis er klingelt, und übersteht auch ein Aufräumen der App.
 *
 * `setExactAndAllowWhileIdle` ist dabei entscheidend: Ein gewöhnlicher
 * Wecker wird im Ruhezustand gebündelt und kann sich um viele Minuten
 * verspäten. Bei einer Sendung, die um 20:15 beginnt, ist das der
 * Unterschied zwischen Aufnahme und Anfang verpasst.
 */
@Singleton
class RecordingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(recordingId: Long, startAt: Long) {
        val intent = pendingIntent(recordingId)
        // Ab Android 12 darf eine App nur mit ausdrücklicher Erlaubnis auf
        // die Minute genau wecken. Fehlt sie, ist ein ungenauer Wecker immer
        // noch weit besser als gar keine Aufnahme – er geht nur eventuell
        // ein paar Minuten später los.
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, intent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startAt, intent)
            }
        }.onFailure { Log.e(TAG, "Wecker für Aufnahme $recordingId nicht gestellt", it) }
    }

    fun cancel(recordingId: Long) {
        runCatching { alarmManager.cancel(pendingIntent(recordingId)) }
    }

    /**
     * Die Kennung der Aufnahme dient zugleich als Anfrage-Code – so trifft
     * ein Abbestellen genau den einen Wecker und nicht die übrigen.
     */
    private fun pendingIntent(recordingId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        recordingId.toInt(),
        Intent(context, RecordingAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_RECORDING_ID, recordingId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "RecordingScheduler"
        const val ACTION_ALARM = "de.qwikster.player.RECORDING_ALARM"
        const val EXTRA_RECORDING_ID = "recordingId"
    }
}

/**
 * Nimmt den Weckruf entgegen und startet die vorgemerkte Aufnahme.
 *
 * Hört zusätzlich auf den abgeschlossenen Systemstart: Wecker überstehen
 * einen Neustart des Geräts nicht, alle Vormerkungen wären danach still
 * verfallen. Nach dem Hochfahren werden sie deshalb neu gestellt.
 */
@AndroidEntryPoint
class RecordingAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: RecordingRepository

    override fun onReceive(context: Context, intent: Intent) {
        // Der Empfänger darf nur kurz laufen; `goAsync` hält ihn so lange am
        // Leben, bis die Datenbankabfrage durch ist.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> repository.rescheduleAll()
                    else -> {
                        val id = intent.getLongExtra(RecordingScheduler.EXTRA_RECORDING_ID, 0L)
                        if (id != 0L) repository.startPlanned(id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
