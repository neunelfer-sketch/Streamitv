package de.qwikster.player.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.qwikster.player.R
import de.qwikster.player.core.CodedException
import de.qwikster.player.core.httpErrorMessage
import de.qwikster.player.core.toErrorCode
import de.qwikster.player.core.withErrorCode
import de.qwikster.player.data.local.CategoryDao
import de.qwikster.player.data.local.ChannelDao
import de.qwikster.player.data.local.PlaylistDao
import de.qwikster.player.data.local.VodDao
import de.qwikster.player.data.local.toEntity
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.Movie
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.model.PlaylistType
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.remote.m3u.M3uParser
import de.qwikster.player.data.remote.xtream.XtreamApi
import de.qwikster.player.data.remote.xtream.XtreamCredentials
import de.qwikster.player.data.remote.xtream.XtreamMapper
import de.qwikster.player.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    @ApplicationContext private val context: Context,
    private val xtreamApi: XtreamApi,
    private val httpClient: OkHttpClient,
    private val playlistDao: PlaylistDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val vodDao: VodDao,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** Verhindert doppelt laufende Anreicherungen, falls die Playlist mehrfach kurz hintereinander aktualisiert wird. */
    private val enrichmentInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

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
            val message = e.message ?: context.getString(R.string.error_unknown)
            emit(SyncProgress.Failed(message.withErrorCode(e.toErrorCode()), e))
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

        emit(SyncProgress.Step(context.getString(R.string.sync_connecting), 5))
        xtreamApi.authenticate(credentials)

        // --- Live TV -------------------------------------------------------
        emit(SyncProgress.Step(context.getString(R.string.sync_loading_channels), 15))
        val liveCategoriesTask = async { xtreamApi.getCategories(credentials, StreamKind.LIVE) }
        val liveStreamsTask = async { xtreamApi.getLiveStreams(credentials) }

        val liveCategories = XtreamMapper.toCategories(
            liveCategoriesTask.await(), StreamKind.LIVE, playlist.id,
        )
        val channels = XtreamMapper.toChannels(liveStreamsTask.await(), playlist.id)

        emit(SyncProgress.Step(context.getString(R.string.sync_saving_channels, channels.size), 45))
        categoryDao.replaceAll(playlist.id, StreamKind.LIVE.name, liveCategories.map { it.toEntity() })
        channelDao.replaceAll(playlist.id, channels.map { it.toEntity() })
        playlistDao.markSynced(playlist.id, System.currentTimeMillis())
        emit(SyncProgress.LiveReady(channels.size))

        // --- Filme ---------------------------------------------------------
        emit(SyncProgress.Step(context.getString(R.string.sync_loading_movies), 60))
        val vodCategories = runCatching {
            XtreamMapper.toCategories(
                xtreamApi.getCategories(credentials, StreamKind.VOD), StreamKind.VOD, playlist.id,
            )
        }.getOrDefault(emptyList())

        val allMovies = runCatching {
            XtreamMapper.toMovies(xtreamApi.getVodStreams(credentials), playlist.id)
        }.onFailure {
            // Nicht jedes Panel hat VOD freigeschaltet – kein Grund, den
            // kompletten Import scheitern zu lassen.
            Log.w(TAG, "VOD-Import übersprungen: ${it.message}")
        }.getOrDefault(emptyList())

        // --- Dauerkanäle aus dem Filmbereich holen --------------------------
        // Panels legen "24/7"-Kanäle als VOD an, weil sie technisch über den
        // Film-Endpunkt ausgeliefert werden. Inhaltlich sind es Kanäle: Sie
        // laufen endlos und haben keinen Anfang, den man starten könnte. Im
        // Poster-Raster stehen sie deshalb falsch – dort erwartet man etwas,
        // das von vorn beginnt.
        //
        // Anders als bei M3U genügt hier kein Umetikettieren: Die
        // Wiedergabe-Adresse baut sich bei Xtream aus der Art auf, ein als
        // LIVE geführter Eintrag bekäme also `/live/…` und liefe ins Leere.
        // Die Adresse wird deshalb hier schon fertig gebaut und als
        // `directUrl` mitgegeben – die hat beim Abspielen Vorrang.
        // Die Laufzeit ist das bessere Signal – ein Film hat eine, ein
        // Dauerkanal nicht. Sie taugt hier aber nur als *Gegenbeweis*:
        // `get_vod_streams` liefert sie überhaupt nicht mit, sie entsteht
        // erst durch die Detailabfrage im Hintergrund (siehe
        // [enrichMoviePosters]). Beim Import steht sie also für fast alles
        // auf 0 – als alleiniges Merkmal würde sie den kompletten Katalog
        // nach Live TV schieben. Wo sie aber bekannt ist, entscheidet sie:
        // Ein Eintrag mit Laufzeit bleibt ein Film, auch wenn die Kategorie
        // "24/7" heißt.
        val knownDurations = vodDao.getMovieDurations(playlist.id)
        val continuousCategories = vodCategories.filter { isContinuousName(it.name) }
        val continuousIds = continuousCategories.map { it.id }.toSet()
        val (continuousMovies, movies) = allMovies.partition { movie ->
            movie.categoryId in continuousIds && (knownDurations[movie.streamId] ?: 0) <= 0
        }

        if (continuousMovies.isNotEmpty()) {
            val continuousChannels = continuousMovies.map { movie ->
                Channel(
                    // Eigener Namensraum: Ein Film und ein Sender können
                    // dieselbe Kennung tragen, und Favoriten hängen an ihr.
                    streamId = "vod-${movie.streamId}",
                    playlistId = playlist.id,
                    name = movie.name,
                    logoUrl = movie.posterUrl,
                    categoryId = movie.categoryId,
                    directUrl = xtreamApi.buildStreamUrl(
                        credentials = credentials,
                        kind = StreamKind.VOD,
                        streamId = movie.streamId,
                        extension = movie.containerExtension,
                    ),
                    containerExtension = movie.containerExtension,
                )
            }
            // Zweiter Durchgang für Live TV, nur wenn es wirklich etwas zu
            // verschieben gibt: `replaceAll` schreibt die ganze Tabelle neu,
            // und die meisten Playlists haben keine Dauerkanäle.
            categoryDao.replaceAll(
                playlist.id,
                StreamKind.LIVE.name,
                (liveCategories + continuousCategories.map { it.copy(kind = StreamKind.LIVE) })
                    .map { it.toEntity() },
            )
            channelDao.replaceAll(playlist.id, (channels + continuousChannels).map { it.toEntity() })
            Log.i(TAG, "${continuousChannels.size} Dauerkanäle aus Filmen nach Live TV verschoben")
        }

        // Die Kategorien der Dauerkanäle sind jetzt Live-Kategorien und haben
        // im Filmbereich nichts mehr verloren. Bewusst außerhalb einer
        // "nur wenn nicht leer"-Bedingung: Bleiben sie stehen, wenn nichts
        // mehr hineingehört, hat man leere Reiter, die sich nicht wegräumen
        // lassen.
        categoryDao.replaceAll(
            playlist.id,
            StreamKind.VOD.name,
            (vodCategories - continuousCategories.toSet()).map { it.toEntity() },
        )

        // Bereits gefundene, hochwertige Poster (siehe [enrichMoviePosters])
        // überstehen den Resync: [replaceMovies] schreibt die Tabelle sonst
        // komplett neu und würfe jedes zuvor angereicherte Cover wieder weg.
        val previousPosters = vodDao.getEnrichedMoviePosters(playlist.id).associateBy { it.streamId }
        val previousAddedAt = vodDao.getMovieAddedTimes(playlist.id)
        val now = System.currentTimeMillis()
        val moviesWithPosters = movies.mapIndexed { index, movie ->
            val withPoster = previousPosters[movie.streamId]?.let { enrichment ->
                movie.copy(posterUrl = enrichment.posterUrl ?: movie.posterUrl, plot = enrichment.plot)
            } ?: movie
            withPoster.copy(addedAt = resolveAddedAt(movie.addedAt, previousAddedAt[movie.streamId], now, index))
        }

        vodDao.replaceMovies(playlist.id, moviesWithPosters.map { it.toEntity() })
        enrichMoviePosters(playlist, moviesWithPosters)

        // --- Serien --------------------------------------------------------
        emit(SyncProgress.Step(context.getString(R.string.sync_loading_series), 80))
        val seriesCategories = runCatching {
            XtreamMapper.toCategories(
                xtreamApi.getCategories(credentials, StreamKind.SERIES), StreamKind.SERIES, playlist.id,
            )
        }.getOrDefault(emptyList())

        val allSeries = runCatching {
            XtreamMapper.toSeries(xtreamApi.getSeries(credentials), playlist.id)
        }.onFailure {
            Log.w(TAG, "Serien-Import übersprungen: ${it.message}")
        }.getOrDefault(emptyList())

        // Dieselbe Aufräumarbeit wie bei den Filmen. Verschoben wird hier
        // allerdings nichts: Eine Serie ist nur eine Hülle, die eigentlichen
        // Adressen stecken in ihren Folgen, und die lädt das Panel erst auf
        // Nachfrage. Ein "24/7"-Eintrag hier ist deshalb ohnehin keine
        // Serie im üblichen Sinn – er verschwindet aus dem Raster, statt
        // dort einen Reiter zu belegen, hinter dem nichts Abspielbares steht.
        val continuousSeriesCategories = seriesCategories.filter { isContinuousName(it.name) }
        val continuousSeriesIds = continuousSeriesCategories.map { it.id }.toSet()
        val series = allSeries.filterNot { it.categoryId in continuousSeriesIds }

        categoryDao.replaceAll(
            playlist.id,
            StreamKind.SERIES.name,
            (seriesCategories - continuousSeriesCategories.toSet()).map { it.toEntity() },
        )

        // Dieselbe Absicherung wie bei den Filmen: Nicht jedes Panel füllt
        // `last_modified`. Steht dort 0, wäre die Reihenfolge in "Neu
        // hinzugefügt" sonst reine Willkür.
        val previousModifiedXtream = vodDao.getSeriesModifiedTimes(playlist.id)
        val seriesNow = System.currentTimeMillis()
        val seriesWithDates = series.mapIndexed { index, entry ->
            entry.copy(
                lastModified = resolveAddedAt(
                    entry.lastModified,
                    previousModifiedXtream[entry.seriesId],
                    seriesNow,
                    index,
                ),
            )
        }
        vodDao.replaceSeries(playlist.id, seriesWithDates.map { it.toEntity() })

        emit(SyncProgress.Step(context.getString(R.string.sync_done), 100))
        emit(SyncProgress.Done(channels.size, movies.size, series.size))
    }

    /**
     * Ersetzt nach und nach das Vorschaubild aus der Senderliste
     * (`stream_icon` – bei vielen Panels nur ein Kamerabild aus dem Film,
     * oft verschwommen) durch das eigentliche Poster aus `get_vod_info`.
     *
     * Bewusst **nicht** Teil des eigentlichen Syncs: Eine Abfrage pro Film
     * bei tausenden Filmen würde den Import um ein Vielfaches verlangsamen.
     * Läuft stattdessen im Hintergrund weiter, ein Film nach dem anderen mit
     * einer kleinen Pause dazwischen, und schreibt jeden Treffer sofort in
     * die Datenbank – das Raster aktualisiert sich von selbst, sobald ein
     * besseres Cover da ist (Room liefert Änderungen als [Flow]). Nur
     * Filme ohne `plot` werden angefasst: das ist der Marker für "noch
     * nicht angereichert" (siehe [VodDao.getEnrichedMoviePosters]), ein
     * späterer Sync verarbeitet also nur echte Neuzugänge.
     */
    private fun enrichMoviePosters(playlist: Playlist, movies: List<Movie>) {
        if (playlist.type != PlaylistType.XTREAM) return
        val pending = movies.filter { it.plot.isNullOrBlank() }
        if (pending.isEmpty() || !enrichmentInProgress.add(playlist.id)) return

        appScope.launch(Dispatchers.IO) {
            try {
                val credentials = playlist.credentials()
                for (movie in pending) {
                    runCatching {
                        val info = xtreamApi.getVodInfo(credentials, movie.streamId)
                        val enriched = XtreamMapper.enrichMovie(movie, info)
                        val current = vodDao.getMovie(playlist.id, movie.streamId) ?: return@runCatching
                        vodDao.updateMovie(
                            current.copy(
                                posterUrl = enriched.posterUrl,
                                // Auch ohne echten Klappentext wird ein nicht-leerer
                                // Platzhalter gespeichert: Das ist der Marker "schon
                                // angereichert" (siehe getEnrichedMoviePosters) – ohne
                                // ihn würde derselbe Film bei jedem Sync erneut
                                // angefragt, weil das Panel dafür nie einen Klappentext
                                // liefert.
                                plot = enriched.plot?.takeIf { it.isNotBlank() } ?: "–",
                                year = enriched.year,
                                rating = enriched.rating,
                                durationSecs = enriched.durationSecs,
                            ),
                        )
                    }.onFailure {
                        Log.w(TAG, "Cover-Anreicherung übersprungen für '${movie.name}': ${it.message}")
                    }
                    delay(ENRICHMENT_DELAY_MS)
                }
            } finally {
                enrichmentInProgress.remove(playlist.id)
            }
        }
    }

    // -----------------------------------------------------------------------
    // M3U
    // -----------------------------------------------------------------------

    private suspend fun FlowCollector<SyncProgress>.syncM3u(playlist: Playlist) {
        emit(SyncProgress.Step(context.getString(R.string.sync_loading_playlist), 10))

        val request = Request.Builder()
            .url(playlist.m3uUrl)
            // Manche Hoster blocken den OkHttp-Standard-User-Agent.
            .header("User-Agent", USER_AGENT)
            .build()

        val parsed = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SyncException(
                    context.httpErrorMessage(response.code),
                    "HTTP-${response.code}",
                )
            }
            val body = response.body
                ?: throw SyncException(context.getString(R.string.error_empty_response), "EMPTY_BODY")
            val gzipped = playlist.m3uUrl.endsWith(".gz", ignoreCase = true) ||
                response.header("Content-Encoding").equals("gzip", ignoreCase = true)
            emit(SyncProgress.Step(context.getString(R.string.sync_parsing_playlist), 40))
            M3uParser.parse(body.byteStream(), gzipped)
        }

        val channels = M3uParser.toChannels(parsed, playlist.id)
        val movies = M3uParser.toMovies(parsed, playlist.id)

        // Eine EPG-Quelle wird automatisch hinterlegt, wenn der Nutzer keine
        // eigene eingetragen hat – noch vor LiveReady, damit der automatische
        // EPG-Import auf dem Hauptbildschirm (der direkt danach anläuft) sie
        // schon kennt. Zwei Quellen, in der Reihenfolge, wie sie
        // zuverlässiger sind:
        // 1. eine im Kopf der Datei angegebene `url-tvg`,
        // 2. sonst, falls der "M3U"-Link in Wahrheit ein Xtream-Codes-Export
        //    ist (`get.php?username=…&password=…`), die zu denselben
        //    Zugangsdaten gehörende `xmltv.php` – genau das, was TiviMate
        //    hier automatisch findet.
        if (playlist.epgUrl.isBlank()) {
            val detectedEpgUrl = parsed.epgUrls.firstOrNull() ?: deriveXtreamEpgUrl(playlist.m3uUrl)
            if (detectedEpgUrl != null) {
                playlistDao.insert(
                    playlist.copy(epgUrl = detectedEpgUrl).toEntity(isActive = true),
                )
            }
        }

        emit(SyncProgress.Step(context.getString(R.string.sync_saving_channels, channels.size), 75))
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
            val moviesWithAddedAt = movies.mapIndexed { index, movie ->
                movie.copy(addedAt = resolveAddedAt(0L, previousAddedAt[movie.streamId], now, index))
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
            emit(SyncProgress.Step(context.getString(R.string.sync_saving_series, series.size), 90))
            categoryDao.replaceAll(
                playlist.id,
                StreamKind.SERIES.name,
                M3uParser.toCategories(parsed, playlist.id, StreamKind.SERIES).map { it.toEntity() },
            )
            // Derselbe Grund wie bei den Filmen oben: ohne nachgetragenen
            // Zeitpunkt wäre "Neu hinzugefügt" bei Serien ebenso wirkungslos.
            val previousModified = vodDao.getSeriesModifiedTimes(playlist.id)
            val now = System.currentTimeMillis()
            val seriesWithModified = series.mapIndexed { index, entry ->
                entry.copy(
                    lastModified = resolveAddedAt(0L, previousModified[entry.seriesId], now, index),
                )
            }
            vodDao.replaceSeries(playlist.id, seriesWithModified.map { it.toEntity() })
            vodDao.replaceEpisodes(
                playlist.id,
                M3uParser.toEpisodes(parsed).map { it.toEntity(playlist.id) },
            )
        }

        emit(SyncProgress.Step(context.getString(R.string.sync_done), 100))
        emit(SyncProgress.Done(channels.size, movies.size, series.size))
    }

    /**
     * Viele als "M3U" eingerichtete Playlists sind in Wahrheit der
     * Xtream-Codes-Export desselben Panels (`get.php?username=…&password=…`).
     * Ohne eigene `url-tvg` im Dateikopf lässt sich die zugehörige
     * `xmltv.php` trotzdem aus genau diesen Zugangsdaten ableiten – dieselbe
     * Adresse, die bei einer direkt als Xtream eingerichteten Playlist
     * automatisch verwendet würde. `null`, wenn der Link kein solches Muster
     * zeigt (z. B. eine echte, statische M3U-Datei ohne Zugangsdaten).
     */
    private fun deriveXtreamEpgUrl(m3uUrl: String): String? {
        val url = m3uUrl.toHttpUrlOrNull() ?: return null
        val username = url.queryParameter("username")?.takeIf { it.isNotBlank() } ?: return null
        val password = url.queryParameter("password")?.takeIf { it.isNotBlank() } ?: return null
        return xtreamApi.buildXmltvUrl(XtreamCredentials(baseUrl = m3uUrl, username = username, password = password))
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
        const val USER_AGENT = "Qwikster/1.0 (Android TV)"

        /**
         * Zweitname für Panels, die nur bekannte Player durchlassen.
         *
         * VLC ist der mit Abstand am weitesten verbreitete Abspieler für
         * IPTV-Ströme und steht deshalb praktisch überall auf der
         * Positivliste. Er kommt ausschließlich dann zum Einsatz, wenn ein
         * Server den eigenen Namen bereits mit 403 abgewiesen hat – siehe
         * den Interceptor in `AppModule`.
         */
        const val FALLBACK_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

        /** Pause zwischen zwei `get_vod_info`-Abfragen bei der Cover-Anreicherung. */
        private const val ENRICHMENT_DELAY_MS = 200L
    }
}

/**
 * Fehler beim Import mit eigenem Diagnosecode (z. B. "HTTP-403").
 *
 * Ohne das würden ganz unterschiedliche Ursachen (falsche URL, Server down,
 * abgelaufener Zugang, leere Antwort, …) alle als derselbe generische
 * `IllegalStateException`-Code beim Nutzer landen – nicht hilfreich für die
 * Ferndiagnose eines fehlgeschlagenen Playlist-Imports.
 */
private class SyncException(message: String, override val errorCode: String) : Exception(message), CodedException

/**
 * Ermittelt den "hinzugefügt am"-Zeitpunkt, nach dem "Neu hinzugefügt"
 * sortiert.
 *
 * Drei Quellen, in dieser Reihenfolge:
 *
 * 1. **Die Angabe des Panels.** Bei Xtream steht in `added` bzw.
 *    `last_modified` der tatsächliche Zeitpunkt – die beste Auskunft, die
 *    es gibt. Nicht jedes Panel füllt sie allerdings; dann steht dort 0.
 * 2. **Der bereits gemerkte Zeitpunkt.** Der Bestand wird bei jedem Sync
 *    komplett neu geschrieben; ohne dieses Nachtragen bekäme die ganze
 *    Liste jedes Mal denselben frischen Zeitstempel, und die Reihenfolge
 *    wäre nach dem ersten Refresh wertlos.
 * 3. **Die Position in der Liste.** Der Rückfall für alles Neue, und der
 *    Grund, warum hier überhaupt ein Index durchgereicht wird: Bekämen alle
 *    Neuzugänge denselben Zeitstempel – beim ersten Import also der gesamte
 *    Katalog –, wäre "Neu hinzugefügt" eine willkürliche Reihenfolge. Panels
 *    und M3U-Dateien hängen Neues hinten an, ein höherer Index heißt also
 *    "später dazugekommen". Eine Millisekunde je Eintrag genügt, um daraus
 *    eine eindeutige Sortierung zu machen; bei 30.000 Titeln sind das
 *    30 Sekunden Versatz.
 */
private fun resolveAddedAt(fromPanel: Long, remembered: Long?, now: Long, index: Int): Long =
    fromPanel.takeIf { it > 0L } ?: remembered?.takeIf { it > 0L } ?: (now + index)

/**
 * Erkennt Kategorien, die Dauerkanäle enthalten ("24/7", "24-7", "24 7").
 *
 * Bewusst irgendwo im Namen und nicht am Anfang: Anbieter stellen ihren
 * Kategorien gern Landeskürzel, Trennstriche oder Symbole voran ("|DE|
 * 24/7 Filme", "★ 24/7 Serien"). Genau deshalb standen sie in der Liste
 * auch nicht bei den Ziffern hinten, sondern mitten unter den übrigen.
 */
private val CONTINUOUS_CATEGORY = Regex("""24\s*[/\-.]?\s*7""")

private fun isContinuousName(name: String): Boolean =
    CONTINUOUS_CATEGORY.containsMatchIn(name.lowercase())
