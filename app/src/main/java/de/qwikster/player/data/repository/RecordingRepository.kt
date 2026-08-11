package de.qwikster.player.data.repository

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.R
import de.qwikster.player.data.local.RecordingDao
import de.qwikster.player.data.local.RecordingEntity
import de.qwikster.player.data.local.RecordingState
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.recording.RecordingService
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die anbietbaren Aufnahmelängen.
 *
 * [UNTIL_PROGRAM_END] ist der Regelfall und der Grund, warum es diese
 * Auswahl überhaupt gibt: Er richtet sich nach der Programmzeitschrift und
 * trifft damit genau die laufende Sendung. Die festen Längen springen ein,
 * wenn für den Sender kein EPG vorliegt – ohne sie wäre die Aufnahme
 * ausgerechnet bei den Sendern nicht zu gebrauchen, bei denen der Anbieter
 * keine Programmdaten liefert.
 */
enum class RecordingVariant(@StringRes val labelRes: Int) {
    UNTIL_PROGRAM_END(R.string.recording_variant_program),
    MINUTES_30(R.string.recording_variant_30),
    MINUTES_60(R.string.recording_variant_60),
    MINUTES_120(R.string.recording_variant_120),
    UNTIL_STOPPED(R.string.recording_variant_open),
    ;

    /** Geplantes Ende, oder 0 für "bis der Zuschauer stoppt". */
    fun plannedEndAt(now: Long, programEndAt: Long?): Long = when (this) {
        // Ein kleiner Nachlauf: Sendungen fangen selten auf die Sekunde
        // pünktlich an und enden noch seltener pünktlich.
        UNTIL_PROGRAM_END -> programEndAt?.plus(TimeUnit.MINUTES.toMillis(5))
            ?: (now + TimeUnit.MINUTES.toMillis(60))
        MINUTES_30 -> now + TimeUnit.MINUTES.toMillis(30)
        MINUTES_60 -> now + TimeUnit.MINUTES.toMillis(60)
        MINUTES_120 -> now + TimeUnit.MINUTES.toMillis(120)
        UNTIL_STOPPED -> 0L
    }
}

/**
 * Startet, stoppt und verwaltet Aufnahmen.
 *
 * Das eigentliche Mitschreiben erledigt [RecordingService]; hier liegt nur,
 * was davon in der Datenbank steht. Die Trennung ist wesentlich: Der Dienst
 * überlebt die Oberfläche, das Repository wird von ViewModels benutzt, die
 * das nicht tun.
 */
@Singleton
class RecordingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingDao: RecordingDao,
    private val iptvRepository: IptvRepository,
) {

    fun observeAll(): Flow<List<RecordingEntity>> = recordingDao.observeAll()

    suspend fun get(id: Long): RecordingEntity? = recordingDao.getById(id)

    /**
     * Legt eine Aufnahme an und startet den Dienst.
     *
     * @return die Kennung der Aufnahme, oder `null`, wenn sich für den
     *         Sender keine abspielbare Adresse ermitteln ließ.
     */
    suspend fun start(
        channel: Channel,
        programTitle: String?,
        programEndAt: Long?,
        variant: RecordingVariant,
    ): Long? {
        // Bewusst ohne HLS: Eine `.m3u8` liefert nur eine Liste von
        // Segmenten, keinen fortlaufenden Strom – mitschreiben ließe sich da
        // nichts. Der Transportstrom ist genau das, was hier gebraucht wird.
        val url = iptvRepository.resolveStreamUrl(channel, preferHls = false) ?: return null

        val now = System.currentTimeMillis()
        val title = programTitle?.takeIf { it.isNotBlank() } ?: channel.name
        val file = File(recordingDir(), fileName(channel.name, title, now))

        val id = recordingDao.insert(
            RecordingEntity(
                playlistId = channel.playlistId,
                streamId = channel.streamId,
                channelName = channel.name,
                title = title,
                filePath = file.absolutePath,
                startedAt = now,
                plannedEndAt = variant.plannedEndAt(now, programEndAt),
            ),
        )

        val intent = Intent(context, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RECORDING_ID, id)
            putExtra(RecordingService.EXTRA_URL, url)
            putExtra(RecordingService.EXTRA_TITLE, title)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        return id
    }

    fun stop(id: Long) {
        context.startService(
            Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
                putExtra(RecordingService.EXTRA_RECORDING_ID, id)
            },
        )
    }

    suspend fun updateProgress(id: Long, sizeBytes: Long) {
        val current = recordingDao.getById(id) ?: return
        recordingDao.update(current.copy(sizeBytes = sizeBytes))
    }

    suspend fun finish(id: Long, sizeBytes: Long, state: RecordingState, errorMessage: String?) {
        val current = recordingDao.getById(id) ?: return
        recordingDao.update(
            current.copy(
                sizeBytes = sizeBytes,
                endedAt = System.currentTimeMillis(),
                state = state.name,
                errorMessage = errorMessage,
            ),
        )
    }

    /** Löscht Eintrag **und** Datei – eine verwaiste Datei fände niemand wieder. */
    suspend fun delete(id: Long) {
        recordingDao.getById(id)?.let { runCatching { File(it.filePath).delete() } }
        recordingDao.delete(id)
    }

    /**
     * Schließt Aufnahmen ab, die beim letzten Lauf nicht sauber beendet
     * wurden – siehe [RecordingDao.closeDangling]. Läuft beim App-Start.
     */
    suspend fun closeDangling() {
        recordingDao.closeDangling(System.currentTimeMillis())
    }

    /**
     * Ablageort der Aufnahmen: der app-eigene Bereich auf dem externen
     * Speicher.
     *
     * Dort ist Platz (Aufnahmen werden schnell mehrere Gigabyte groß, der
     * interne Speicher eines Fire TV Sticks ist knapp), es braucht keine
     * Berechtigung, und beim Deinstallieren räumt Android mit auf.
     */
    private fun recordingDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "recordings").apply { mkdirs() }
    }

    /**
     * Dateiname aus Sender, Titel und Zeitpunkt.
     *
     * Alles außer Buchstaben, Ziffern und Bindestrich fliegt raus: Titel aus
     * dem EPG enthalten regelmäßig Schrägstriche, Doppelpunkte und
     * Anführungszeichen, an denen das Anlegen der Datei sonst scheitert.
     */
    private fun fileName(channelName: String, title: String, now: Long): String {
        fun clean(value: String) = value
            .replace(Regex("""[^\p{L}\p{N}\-]+"""), "_")
            .trim('_')
            .take(40)
            .ifBlank { "Aufnahme" }
        return "${clean(channelName)}_${clean(title)}_$now.ts"
    }
}
