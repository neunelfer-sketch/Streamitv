package de.neunelf.player.data.repository

import android.util.Log
import de.neunelf.player.data.local.CategoryDao
import de.neunelf.player.data.local.ChannelDao
import de.neunelf.player.data.local.PlaylistDao
import de.neunelf.player.data.local.VodDao
import de.neunelf.player.data.local.toEntity
import de.neunelf.player.data.model.Playlist
import de.neunelf.player.data.model.PlaylistType
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.remote.m3u.M3uParser
import de.neunelf.player.data.remote.xtream.XtreamApi
import de.neunelf.player.data.remote.xtream.XtreamMapper
import de.neunelf.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Fortschrittsmeldungen während des Imports – die UI zeigt sie als Statuszeile. */
sealed interface SyncProgress {
    data class Step(val message: String, val percent: Int) : SyncProgress

    /**
     * Die Live-Sender sind gespeichert und sofort nutzbar. Filme und Serien
     * laufen bei Xtream ggf. noch nach – wer nur darauf wartet, bis man den
     * Hauptbildschirm sehen kann, braucht nicht auf [Done] zu warten.
     */
    data class LiveReady(val channels: Int) : SyncProgress
    data class Done(val channels: Int, val movies: Int, val series: Int) : SyncProgress
    data class Failed(val message: String, val cause: Throwable? = null) : SyncProgress
}

/**
 * Lädt eine Playlist vollständig herunter und schreibt sie in die Datenbank.
 *
 * Ablauf bei Xtream: Kategorien und Streams werden **parallel** geholt
 * (`get_live_streams` ohne `category_id` liefert alles auf einmal – das ist
 * bei großen Panels um Größenordnungen schneller als ein Aufruf je Kategorie).
 *
 * VOD und Serien werden absichtlich *nach* Live TV geladen: Live ist das,
 * worauf der Nutzer wartet, der Rest darf nachlaufen (siehe [SyncProgress.LiveReady]
 * und [syncInBackground]).
 */
@Singleton
class PlaylistSyncer @Inject constructor(
    private val xtreamApi: XtreamApi,
    private val httpClient: OkHttpClient,
    private val playlistDao: PlaylistDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val vodDao: VodDao,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /**
     * Synchronisiert die Playlist und meldet den Fortschritt.
     * Der Flow endet mit [SyncProgress.Done] oder [SyncProgress.Failed].
     */
    fun sync(playlist: Playlist): Flow<SyncProgress> = flow {
        try {
            when (playlist.type) {
                PlaylistType.XTREAM -> syncXtream(playlist)
                PlaylistType.M3U -> syncM3u(playlist)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playlist-Sync fehlgeschlagen für '${playlist.name}'", e)
            emit(SyncProgress.Failed(e.message ?: "Unbekannter Fehler", e))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Startet [sync] in einem App-weiten Gültigkeitsbereich statt im
     * `viewModelScope` des Aufrufers.
     *
     * Grund: Die Ersteinrichtung soll zum Hauptbildschirm wechseln, sobald
     * die Live-Sender da sind ([SyncProgress.LiveReady]) – Filme und Serien
     * dürfen ruhig noch ein bis zwei Minuten brauchen. Würde der Import im
     * `viewModelScope` des Einrichtungsbildschirms laufen, würde genau dieser
     * Wechsel die zugehörige ViewModel zerstören und den Rest des Imports
     * mitten drin abbrechen. Der zurückgegebene [Flow] ist deshalb ein
     * `SharedFlow`: Beobachter können jederzeit abspringen, ohne den
     * eigentlichen Import zu stoppen.
     *
     * Der Wiederholpuffer ist groß genug für einen kompletten Importlauf,
     * und das ist keine Kosmetik: Der Import startet sofort, der Aufrufer
     * hängt sich erst danach an. Ohne Puffer fällt alles in diese Lücke
     * unter den Tisch – bei einer Playlist, die schon vor dem ersten
     * Netzwerkaufruf scheitert (Tippfehler in der URL), auch die
     * Fehlermeldung. Der Einrichtungsbildschirm bliebe dann dauerhaft auf
     * "Verbinde…" stehen, und weil "Verbinden" währenddessen gesperrt ist,
     * käme man ohne Neustart der App nicht mehr weiter.
     */
    fun syncInBackground(playlist: Playlist): Flow<SyncProgress> {
        val progress = MutableSharedFlow<SyncProgress>(replay = SYNC_REPLAY, extraBufferCapacity = 8)
        appScope.launch {
            sync(playlist).collect { progress.emit(it) }
        }
        return progress.asSharedFlow()
    }

    // -----------------------------------------------------------------------
    // Xtream
    // -----------------------------------------------------------------------

    // Die beiden Sync-Routinen laufen als Erweiterung des FlowCollectors,
    // damit sie ihren Fortschritt direkt per `emit` melden können.
    private suspend fun FlowCollector<SyncProgress>.syncXtream(playlist: Playlist) = coroutineScope {
        val credentials = playlist.credentials()

        emit(SyncProgress.Step("Verbinde mit Server…", 5))
        xtreamApi.authenticate(credentials)

        // --- Live TV -------------------------------------------------------
        emit(SyncProgress.Step("Lade Sender…", 15))
        val liveCategoriesTask = async { xtreamApi.getCategories(credentials, StreamKind.LIVE) }
        val liveStreamsTask = async { xtreamApi.getLiveStreams(credentials) }

        val liveCategories = XtreamMapper.toCategories(
            liveCategoriesTask.await(), StreamKind.LIVE, playlist.id,
        )
        val channels = XtreamMapper.toChannels(liveStreamsTask.await(), playlist.id)

        emit(SyncProgress.Step("Speichere ${channels.size} Sender…", 45))
        categoryDao.replaceAll(playlist.id, StreamKind.LIVE.name, liveCategories.map { it.toEntity() })
        channelDao.replaceAll(playlist.id, channels.map { it.toEntity() })
        playlistDao.markSynced(playlist.id, System.currentTimeMillis())
        emit(SyncProgress.LiveReady(channels.size))

        // --- Filme ---------------------------------------------------------
        emit(SyncProgress.Step("Lade Filme…", 60))
        val movies = runCatching {
            val categories = XtreamMapper.toCategories(
                xtreamApi.getCategories(credentials, StreamKind.VOD), StreamKind.VOD, playlist.id,
            )
            categoryDao.replaceAll(playlist.id, StreamKind.VOD.name, categories.map { it.toEntity() })
            XtreamMapper.toMovies(xtreamApi.getVodStreams(credentials), playlist.id)
        }.onFailure {
            // Nicht jedes Panel hat VOD freigeschaltet – kein Grund, den
            // kompletten Import scheitern zu lassen.
            Log.w(TAG, "VOD-Import übersprungen: ${it.message}")
        }.getOrDefault(emptyList())

        vodDao.replaceMovies(playlist.id, movies.map { it.toEntity() })

        // --- Serien --------------------------------------------------------
        emit(SyncProgress.Step("Lade Serien…", 80))
        val series = runCatching {
            val categories = XtreamMapper.toCategories(
                xtreamApi.getCategories(credentials, StreamKind.SERIES), StreamKind.SERIES, playlist.id,
            )
            categoryDao.replaceAll(playlist.id, StreamKind.SERIES.name, categories.map { it.toEntity() })
            XtreamMapper.toSeries(xtreamApi.getSeries(credentials), playlist.id)
        }.onFailure {
            Log.w(TAG, "Serien-Import übersprungen: ${it.message}")
        }.getOrDefault(emptyList())

        vodDao.replaceSeries(playlist.id, series.map { it.toEntity() })

        emit(SyncProgress.Step("Fertig", 100))
        emit(SyncProgress.Done(channels.size, movies.size, series.size))
    }

    // -----------------------------------------------------------------------
    // M3U
    // -----------------------------------------------------------------------

    private suspend fun FlowCollector<SyncProgress>.syncM3u(playlist: Playlist) {
        emit(SyncProgress.Step("Lade Playlist…", 10))

        val request = Request.Builder()
            .url(playlist.m3uUrl)
            // Manche Hoster blocken den OkHttp-Standard-User-Agent.
            .header("User-Agent", USER_AGENT)
            .build()

        val parsed = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server antwortete mit HTTP ${response.code}")
            val body = response.body ?: error("Leere Antwort")
            val gzipped = playlist.m3uUrl.endsWith(".gz", ignoreCase = true) ||
                response.header("Content-Encoding").equals("gzip", ignoreCase = true)
            emit(SyncProgress.Step("Verarbeite Playlist…", 40))
            M3uParser.parse(body.byteStream(), gzipped)
        }

        val channels = M3uParser.toChannels(parsed, playlist.id)
        val movies = M3uParser.toMovies(parsed, playlist.id)

        // Enthält die Playlist eine EPG-Quelle und der Nutzer hat keine
        // eigene eingetragen, übernehmen wir sie automatisch – noch vor
        // LiveReady, damit der automatische EPG-Import auf dem
        // Hauptbildschirm (der direkt danach anläuft) sie schon kennt.
        if (playlist.epgUrl.isBlank() && parsed.epgUrls.isNotEmpty()) {
            playlistDao.insert(
                playlist.copy(epgUrl = parsed.epgUrls.first()).toEntity(isActive = true),
            )
        }

        emit(SyncProgress.Step("Speichere ${channels.size} Sender…", 75))
        categoryDao.replaceAll(
            playlist.id,
            StreamKind.LIVE.name,
            M3uParser.toCategories(parsed, playlist.id, StreamKind.LIVE).map { it.toEntity() },
        )
        channelDao.replaceAll(playlist.id, channels.map { it.toEntity() })
        playlistDao.markSynced(playlist.id, System.currentTimeMillis())
        emit(SyncProgress.LiveReady(channels.size))

        if (movies.isNotEmpty()) {
            categoryDao.replaceAll(
                playlist.id,
                StreamKind.VOD.name,
                M3uParser.toCategories(parsed, playlist.id, StreamKind.VOD).map { it.toEntity() },
            )
            // M3U liefert kein "hinzugefügt am" – ohne dieses Nachtragen
            // bekäme bei jedem Sync die komplette Liste denselben Zeitstempel
            // und "Neu hinzugefügt" im Sortiermenü wäre wirkungslos.
            val previousAddedAt = vodDao.getMovieAddedTimes(playlist.id)
            val now = System.currentTimeMillis()
            val moviesWithAddedAt = movies.map { movie ->
                movie.copy(addedAt = previousAddedAt[movie.streamId] ?: now)
            }
            vodDao.replaceMovies(playlist.id, moviesWithAddedAt.map { it.toEntity() })
        }

        // --- Serien --------------------------------------------------------
        // Eine M3U-Datei kennt nur einzelne Folgen; der Parser fasst sie
        // anhand der Titel zu Serien zusammen. Anders als bei Xtream gibt es
        // hier nichts nachzuladen – alle Folgen stehen schon in der Datei,
        // also werden sie gleich mitgeschrieben.
        val series = M3uParser.toSeries(parsed, playlist.id)
        if (series.isNotEmpty()) {
            emit(SyncProgress.Step("Speichere ${series.size} Serien…", 90))
            categoryDao.replaceAll(
                playlist.id,
                StreamKind.SERIES.name,
                M3uParser.toCategories(parsed, playlist.id, StreamKind.SERIES).map { it.toEntity() },
            )
            // Derselbe Grund wie bei den Filmen oben: ohne nachgetragenen
            // Zeitpunkt wäre "Neu hinzugefügt" bei Serien ebenso wirkungslos.
            val previousModified = vodDao.getSeriesModifiedTimes(playlist.id)
            val now = System.currentTimeMillis()
            val seriesWithModified = series.map { entry ->
                entry.copy(lastModified = previousModified[entry.seriesId] ?: now)
            }
            vodDao.replaceSeries(playlist.id, seriesWithModified.map { it.toEntity() })
            vodDao.replaceEpisodes(
                playlist.id,
                M3uParser.toEpisodes(parsed).map { it.toEntity(playlist.id) },
            )
        }

        emit(SyncProgress.Step("Fertig", 100))
        emit(SyncProgress.Done(channels.size, movies.size, series.size))
    }

    companion object {
        private const val TAG = "PlaylistSyncer"

        /**
         * Reicht für sämtliche Meldungen eines Importlaufs – ein spät
         * hinzugekommener Beobachter bekommt so die vollständige Abfolge
         * nachgereicht statt eines Ausschnitts (siehe [syncInBackground]).
         */
        private const val SYNC_REPLAY = 16

        /** Wird auch beim Streamen benutzt – manche Panels prüfen darauf. */
        const val USER_AGENT = "9elfPlayer/1.0 (Android TV)"
    }
}
