package de.qwikster.player.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.R
import de.qwikster.player.core.toErrorCode
import de.qwikster.player.core.withErrorCode
import de.qwikster.player.data.local.ChannelDao
import de.qwikster.player.data.local.EpgDao
import de.qwikster.player.data.local.EpgProgramEntity
import de.qwikster.player.data.local.PlaylistDao
import de.qwikster.player.data.local.toEntity
import de.qwikster.player.data.local.toModel
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.ChannelWithProgram
import de.qwikster.player.data.model.EpgProgram
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.model.PlaylistType
import de.qwikster.player.data.remote.epg.XmltvParser
import de.qwikster.player.data.remote.xtream.XtreamApi
import de.qwikster.player.data.remote.xtream.XtreamMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Fortschritt des EPG-Imports. */
sealed interface EpgSyncProgress {
    data class Step(val message: String) : EpgSyncProgress
    data class Done(val programCount: Int) : EpgSyncProgress
    data class Failed(val message: String) : EpgSyncProgress
}

/**
 * Verwaltet die Programmzeitschrift.
 *
 * Zwei Quellen, in dieser Reihenfolge:
 * 1. **XMLTV** (vollständig, mehrere Tage) – die eigentliche Grundlage für
 *    das Guide-Raster.
 * 2. **`get_short_epg`** des Panels – Notnagel für einzelne Sender, wenn
 *    keine XMLTV-Datei konfiguriert ist oder ein Sender darin fehlt.
 */
@Singleton
class EpgRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val epgDao: EpgDao,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val httpClient: OkHttpClient,
    private val xtreamApi: XtreamApi,
) {

    // -----------------------------------------------------------------------
    // Abfragen für die UI
    // -----------------------------------------------------------------------

    /**
     * Programme für das EPG-Raster.
     *
     * Rückgabe ist eine Map `epgChannelId -> Sendungen`, damit die Guide-UI
     * pro Zeile in O(1) auf ihre Daten zugreifen kann statt die Gesamtliste
     * bei jedem Frame zu filtern.
     */
    fun observeGuide(
        channels: List<Channel>,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<Map<String, List<EpgProgram>>> {
        val channelIds = channels.mapNotNull { it.epgChannelId }.distinct()
        if (channelIds.isEmpty()) return flowOf(emptyMap())

        return observeWindow(channels.first().playlistId, channelIds, windowStart, windowEnd)
            .map { rows -> rows.map { it.toModel() }.groupBy { it.epgChannelId } }
    }

    /**
     * Sender mit der gerade laufenden Sendung – genau das, was die
     * Senderliste im TiviMate-Stil anzeigt.
     *
     * `next` bleibt hier bewusst leer: Die Senderliste zeigt es nicht, und
     * es mitzuladen hieße, statt einer Sendung je Sender ein ganzes
     * Zeitfenster zu holen. Wer "Danach" braucht – die Info-Leiste des
     * Players –, holt es über [observeCurrentAndNext] für den einen
     * betroffenen Sender.
     *
     * Die Zuordnung passiert im Speicher statt über ein `IN (…)` mit
     * tausenden Sender-IDs; Begründung siehe
     * [de.qwikster.player.data.local.EpgDao.observeCurrentPrograms].
     */
    fun observeChannelsWithProgram(channels: List<Channel>, now: Long): Flow<List<ChannelWithProgram>> {
        if (channels.none { it.epgChannelId != null }) {
            return flowOf(channels.map { ChannelWithProgram(it) })
        }

        return epgDao.observeCurrentPrograms(
            playlistId = channels.first().playlistId,
            now = now,
            // Keine Sendung dauert länger als einen Tag – ein Rückblick von
            // 24 Stunden findet also jede gerade laufende und hält zugleich
            // die gelesene Datenmenge klein.
            earliestStart = now - TimeUnit.HOURS.toMillis(24),
        ).map { rows ->
            val byChannel = rows.associateBy { it.epgChannelId }
            channels.map { channel ->
                ChannelWithProgram(
                    channel = channel,
                    current = channel.epgChannelId?.let { byChannel[it] }?.toModel(),
                )
            }
        }
    }

    /**
     * Laufende und folgende Sendung eines einzelnen Senders – für die
     * Info-Leiste des Players.
     */
    fun observeCurrentAndNext(
        playlistId: Long,
        epgChannelId: String?,
        now: Long,
    ): Flow<Pair<EpgProgram?, EpgProgram?>> {
        if (epgChannelId == null) return flowOf(null to null)
        return epgDao.observeAroundNow(playlistId, epgChannelId, now).map { rows ->
            val programs = rows.map { it.toModel() }
            programs.firstOrNull { it.isLiveAt(now) } to programs.firstOrNull { it.startAt > now }
        }
    }

    /**
     * Fragt das Zeitfenster in Blöcken ab und führt die Ergebnisse zusammen.
     *
     * SQLite erlaubt nur 999 gebundene Variablen je Statement – eine Playlist
     * mit mehreren tausend Sendern sprengt ein einzelnes `IN (…)` also
     * (`too many SQL variables`). Die Alternative, einfach die komplette
     * Playlist zu laden und im Speicher zu filtern, ist keine: Der
     * Hauptbildschirm fragt hier meist nur die Sender *einer Kategorie* an,
     * würde dann aber bei jedem Takt die Sendungen aller Sender einlesen –
     * auf einem Fire TV Stick mit 1 GB RAM schnell dreistellige Megabyte.
     */
    private fun observeWindow(
        playlistId: Long,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<EpgProgramEntity>> {
        val chunks = channelIds.chunked(CHANNEL_ID_CHUNK)
        if (chunks.size == 1) {
            return epgDao.observeWindowChunk(playlistId, chunks.first(), windowStart, windowEnd)
        }
        return combine(
            chunks.map { epgDao.observeWindowChunk(playlistId, it, windowStart, windowEnd) },
        ) { parts -> parts.flatMap { it } }
    }

    suspend fun getUpcoming(
        playlistId: Long,
        epgChannelId: String,
        limit: Int = 12,
    ): List<EpgProgram> =
        epgDao.getUpcoming(playlistId, epgChannelId, System.currentTimeMillis(), limit)
            .map { it.toModel() }

    // -----------------------------------------------------------------------
    // XMLTV-Import
    // -----------------------------------------------------------------------

    /**
     * Lädt die EPG-Daten der Playlist neu.
     *
     * Der Import läuft **streamend**: der Parser meldet jede Sendung einzeln,
     * wir sammeln sie in Blöcken von [BATCH_SIZE] und schreiben sie weg. So
     * bleibt der Speicherbedarf konstant, auch wenn die XMLTV-Datei
     * mehrere hundert Megabyte groß ist.
     */
    fun refresh(playlist: Playlist): Flow<EpgSyncProgress> = flow {
        val url = resolveEpgUrl(playlist)
        if (url.isNullOrBlank()) {
            emit(EpgSyncProgress.Failed(context.getString(R.string.epg_no_source_configured)))
            return@flow
        }

        emit(EpgSyncProgress.Step(context.getString(R.string.guide_loading)))

        // Nur Sender importieren, die es in dieser Playlist wirklich gibt.
        val relevantIds = channelDao.getEpgChannelIds(playlist.id).toSet()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", PlaylistSyncer.USER_AGENT)
            .header("Accept-Encoding", "gzip")
            .build()

        val count = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        EpgSyncProgress.Failed(
                            context.getString(R.string.error_http_status, response.code)
                                .withErrorCode("HTTP-${response.code}"),
                        ),
                    )
                    return@flow
                }
                val body = response.body ?: run {
                    emit(
                        EpgSyncProgress.Failed(
                            context.getString(R.string.error_empty_response)
                                .withErrorCode("EMPTY_BODY"),
                        ),
                    )
                    return@flow
                }

                emit(EpgSyncProgress.Step(context.getString(R.string.epg_parsing)))
                importXmltv(playlist.id, body.byteStream(), relevantIds.takeIf { it.isNotEmpty() })
            }
        } catch (e: Exception) {
            Log.e(TAG, "EPG-Import fehlgeschlagen", e)
            val message = e.message ?: context.getString(R.string.error_unknown)
            emit(EpgSyncProgress.Failed(message.withErrorCode(e.toErrorCode())))
            return@flow
        }

        playlistDao.markEpgSynced(playlist.id, System.currentTimeMillis())
        emit(EpgSyncProgress.Done(count))
    }.flowOn(Dispatchers.IO)

    private suspend fun importXmltv(
        playlistId: Long,
        input: java.io.InputStream,
        relevantIds: Set<String>?,
    ): Int = withContext(Dispatchers.IO) {
        // Altbestand wegräumen: die neue Datei ersetzt ihn vollständig.
        epgDao.deleteFor(playlistId)

        val batch = ArrayList<EpgProgramEntity>(BATCH_SIZE)
        var total = 0

        // Der Parser ist synchron; das Schreiben in die DB muss deshalb
        // blockierend erfolgen – wir sind bereits auf einem IO-Thread.
        XmltvParser.parse(
            input = input,
            relevantChannelIds = relevantIds,
            onProgram = { program ->
                batch += program.toEntity(playlistId)
                if (batch.size >= BATCH_SIZE) {
                    epgDao.insertAllBlocking(batch)
                    total += batch.size
                    batch.clear()
                }
            },
        )

        if (batch.isNotEmpty()) {
            epgDao.insertAll(batch)
            total += batch.size
        }

        // Was schon vorbei ist, brauchen wir nur noch für den Rückblick
        // im Guide (ein Tag reicht).
        epgDao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        total
    }

    /**
     * Ermittelt die EPG-URL:
     * 1. explizit vom Nutzer eingetragen,
     * 2. sonst bei Xtream automatisch `xmltv.php`.
     */
    private fun resolveEpgUrl(playlist: Playlist): String? = when {
        playlist.epgUrl.isNotBlank() -> playlist.epgUrl
        playlist.type == PlaylistType.XTREAM -> xtreamApi.buildXmltvUrl(playlist.credentials())
        else -> null
    }

    // -----------------------------------------------------------------------
    // Fallback über das Panel
    // -----------------------------------------------------------------------

    /**
     * Holt das Kurz-EPG eines einzelnen Senders direkt vom Panel.
     * Wird genutzt, wenn im Guide für einen Sender nichts hinterlegt ist.
     */
    suspend fun fetchShortEpg(playlist: Playlist, channel: Channel): List<EpgProgram> {
        if (playlist.type != PlaylistType.XTREAM) return emptyList()
        val epgId = channel.epgChannelId ?: return emptyList()

        return runCatching {
            val listings = xtreamApi.getShortEpg(playlist.credentials(), channel.streamId)
            val programs = XtreamMapper.toEpgPrograms(listings, epgId)
            // Direkt in den Cache legen, damit der Guide sie beim nächsten
            // Scrollen ohne Netzwerkzugriff hat.
            epgDao.insertAll(programs.map { it.toEntity(playlist.id) })
            programs
        }.onFailure {
            Log.w(TAG, "Kurz-EPG für '${channel.name}' nicht verfügbar: ${it.message}")
        }.getOrDefault(emptyList())
    }

    suspend fun programCount(playlistId: Long): Int = epgDao.count(playlistId)

    companion object {
        private const val TAG = "EpgRepository"

        /**
         * 1.000 Zeilen je Transaktion: groß genug, damit der SQLite-Overhead
         * kaum ins Gewicht fällt, klein genug für ~1 MB Spitzenspeicher.
         */
        private const val BATCH_SIZE = 1_000

        /**
         * Sender-IDs je Abfrage. SQLite lässt 999 gebundene Variablen zu;
         * die übrigen Parameter (Playlist, Fenstergrenzen) brauchen davon
         * drei, der Rest ist Sicherheitsabstand.
         */
        private const val CHANNEL_ID_CHUNK = 900
    }
}
