package de.qwikster.player.data.repository

import de.qwikster.player.data.local.CategoryDao
import de.qwikster.player.data.local.ChannelDao
import de.qwikster.player.data.local.FavoriteEntity
import de.qwikster.player.data.local.PlaylistDao
import de.qwikster.player.data.local.RecentEpisodeRow
import de.qwikster.player.data.local.RecentMovieRow
import de.qwikster.player.data.local.RecentEntity
import de.qwikster.player.data.local.UserDataDao
import de.qwikster.player.data.local.VodDao
import de.qwikster.player.data.local.toEntity
import de.qwikster.player.data.model.Category
import de.qwikster.player.data.model.Channel
import de.qwikster.player.data.model.Episode
import de.qwikster.player.data.model.Movie
import de.qwikster.player.data.model.Playlist
import de.qwikster.player.data.model.PlaylistType
import de.qwikster.player.data.model.Series
import de.qwikster.player.data.model.StreamKind
import de.qwikster.player.data.prefs.SettingsStore
import de.qwikster.player.data.remote.xtream.XtreamApi
import de.qwikster.player.data.remote.xtream.XtreamCredentials
import de.qwikster.player.data.remote.xtream.XtreamMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wonach die Senderliste gerade gefiltert wird.
 *
 * Bewusst als eigener Typ statt als nullbarer String: die drei virtuellen
 * Kategorien (Alle / Favoriten / Zuletzt gesehen) verhalten sich anders als
 * echte Panel-Kategorien und sollen im Code nicht verwechselbar sein.
 */
sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favorites : ChannelFilter
    data object Recent : ChannelFilter
    data class Group(val categoryId: String) : ChannelFilter
    data class Search(val query: String) : ChannelFilter
}

/**
 * Zentraler Zugriffspunkt auf Sender, Kategorien, Favoriten und Verlauf.
 *
 * Die Repository-Schicht kennt als Einzige den Unterschied zwischen einer
 * Xtream- und einer M3U-Playlist. Die ViewModels sehen nur noch Domänenmodelle.
 */
@Singleton
class IptvRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val vodDao: VodDao,
    private val userDataDao: UserDataDao,
    private val xtreamApi: XtreamApi,
    private val json: Json,
    private val settingsStore: SettingsStore,
) {

    // -----------------------------------------------------------------------
    // Playlists
    // -----------------------------------------------------------------------

    fun observeActivePlaylist(): Flow<Playlist?> =
        playlistDao.observeActive().map { it?.toModel() }

    fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun getActivePlaylist(): Playlist? = playlistDao.getActive()?.toModel()

    /** Legt eine Playlist an und macht sie zur aktiven. Gibt die neue ID zurück. */
    suspend fun savePlaylist(playlist: Playlist): Long {
        val id = playlistDao.insertAndActivate(playlist.toEntity(isActive = true))
        rememberAllPlaylists()
        return id
    }

    /**
     * Wechselt die aktive Playlist.
     *
     * Die Daten der anderen bleiben unangetastet in der Datenbank liegen –
     * jede Tabelle führt ihre `playlistId` mit, und jede Abfrage schränkt
     * darauf ein. Ein Wechsel lädt also nichts neu und wirft nichts weg; er
     * ändert nur, worauf alle Abfragen zeigen. Ob die neu gewählte Playlist
     * eine Auffrischung braucht, entscheidet danach der Hauptbildschirm
     * anhand ihres eigenen `lastSyncAt`.
     */
    suspend fun setActivePlaylist(id: Long) {
        playlistDao.setActive(id)
        rememberAllPlaylists()
    }

    /**
     * Schreibt den aktuellen Stand aller Playlists neben die Datenbank.
     *
     * Nach **jeder** Änderung, nicht nur beim Anlegen: Die Datenbank ist ein
     * Zwischenspeicher und wird bei einer Schemaänderung verworfen. Was hier
     * nicht steht, ist nach dem nächsten Update weg – und niemand tippt
     * gern Serveradresse und Zugangsdaten mit einer Fernbedienung neu ein.
     */
    private suspend fun rememberAllPlaylists() {
        val all = playlistDao.observeAll().first().map { it.toModel() }
        settingsStore.rememberPlaylists(all, playlistDao.getActive()?.id)
    }

    /**
     * Setzt die EPG-Quelle der aktiven Playlist.
     *
     * `lastEpgSyncAt` wird dabei zurückgesetzt: Der Hauptbildschirm prüft
     * beim nächsten Aufbau, ob ein EPG-Import fällig ist, und lädt dann von
     * selbst neu. Ein eigener Anstoß von hier aus wäre überflüssig und
     * liefe zudem außerhalb eines Bildschirms, der den Fortschritt anzeigen
     * könnte.
     */
    suspend fun updateEpgUrl(url: String) {
        val current = playlistDao.getActive()?.toModel() ?: return
        val updated = current.copy(epgUrl = url.trim(), lastEpgSyncAt = 0L)
        playlistDao.insert(updated.toEntity(isActive = true))
        rememberAllPlaylists()
    }

    /**
     * Entfernt eine Playlist samt ihrer zwischengespeicherten Inhalte.
     *
     * Bleibt danach noch eine übrig, wird sie zur aktiven – sonst stünde der
     * Zuschauer vor einer Einrichtungsseite, obwohl er noch Zugänge hat.
     */
    suspend fun deletePlaylist(id: Long) {
        val wasActive = playlistDao.getActive()?.id == id
        playlistDao.delete(id)
        if (wasActive) {
            playlistDao.observeAll().first().firstOrNull()?.let { playlistDao.setActive(it.id) }
        }
        rememberAllPlaylists()
    }

    /**
     * Stellt die eingerichtete Verbindung wieder her, falls die Datenbank
     * keine mehr kennt.
     *
     * Nötig, weil die Datenbank ein reiner Zwischenspeicher ist und bei
     * jeder Schemaänderung verworfen wird. Bis hierher verschwand damit auch
     * die Playlist selbst – nach einem Update stand der Nutzer wieder vor
     * der Ersteinrichtung und musste Server, Benutzername und Passwort neu
     * eintippen. Das ist auf einer Fernbedienung besonders ärgerlich.
     *
     * Muss **vor** dem ersten Auswerten von `observeActivePlaylist` laufen:
     * Der Navigationsgraph legt sein Startziel einmalig fest und korrigiert
     * es später nicht mehr (siehe `QwiksterNavHost`).
     */
    suspend fun restorePlaylistIfMissing() {
        if (playlistDao.getActive() != null) return
        val remembered = settingsStore.rememberedPlaylists()
        if (remembered.isEmpty()) return

        // Alle einspielen, danach die zuletzt gewählte aktivieren. Die IDs
        // vergibt die Datenbank neu – das ist unbedenklich, weil mit ihr auch
        // sämtliche zwischengespeicherten Inhalte weg sind, die darauf
        // verwiesen hätten.
        val ids = remembered.map { playlistDao.insert(it.toEntity(isActive = false)) }
        val activeIndex = settingsStore.rememberedActiveIndex().coerceIn(ids.indices)
        playlistDao.setActive(ids[activeIndex])
    }

    // -----------------------------------------------------------------------
    // Kategorien & Sender
    // -----------------------------------------------------------------------

    fun observeCategories(kind: StreamKind): Flow<List<Category>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            categoryDao.observeWithCounts(playlist.id, kind.name).map { rows ->
                rows
                    // Panels liefern gern leere Bouquets – die blenden wir aus.
                    .filter { it.channelCount > 0 || kind != StreamKind.LIVE }
                    .map { row ->
                        Category(
                            id = row.categoryId,
                            name = row.name,
                            kind = kind,
                            playlistId = row.playlistId,
                            sortOrder = row.sortOrder,
                            channelCount = row.channelCount,
                        )
                    }
            }
        }

    /** Liefert die Senderliste passend zum aktuellen [filter]. */
    fun observeChannels(filter: ChannelFilter): Flow<List<Channel>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            val rows = when (filter) {
                ChannelFilter.All -> channelDao.observeAll(playlist.id)
                ChannelFilter.Favorites -> channelDao.observeFavorites(playlist.id)
                ChannelFilter.Recent -> channelDao.observeRecent(playlist.id, RECENT_LIMIT)
                is ChannelFilter.Group -> channelDao.observeByCategory(playlist.id, filter.categoryId)
                is ChannelFilter.Search -> channelDao.search(playlist.id, filter.query)
            }
            rows.map { list -> list.map { it.toModel() } }
                .withoutHidden(StreamKind.LIVE) { it.categoryId }
        }

    // -----------------------------------------------------------------------
    // Ausgeblendete Kategorien
    // -----------------------------------------------------------------------

    /**
     * Wirft die Inhalte ausgeblendeter Kategorien aus einem Datenstrom.
     *
     * **Warum hier und nicht in den ViewModels:** Vorher filterte jeder
     * Bildschirm für sich, und wer es vergaß, hatte ein Leck. Genau das war
     * der Fall – die Suche, das EPG-Raster und vor allem der Zapper im
     * Player lasen ungefiltert. Beim Weiterschalten mit ◀ ▶ landete man
     * deshalb mitten in ausgeblendeten Sendern: nicht nur sichtbar, sondern
     * hörbar. An dieser einen Stelle kann das niemand mehr übersehen.
     *
     * **Und warum `combine` statt eines gemerkten Werts:** Die Einstellung
     * kommt aus dem DataStore, wird also erst kurz nach dem Start gelesen.
     * Ein vorgehaltener Standardwert ("noch nichts ausgeblendet") hätte
     * bedeutet, dass die erste Senderliste ungefiltert herausgeht – und
     * genau in diesem Augenblick baut der Hauptbildschirm seine Liste auf,
     * setzt den Fokus auf den ersten Sender und spielt ihn in der Vorschau
     * an. Das war der fremde Sender, der beim Neustart kurz zu hören war.
     * `combine` wartet auf beide Quellen: Es gibt keine erste Liste ohne
     * die Einstellung dazu.
     */
    private inline fun <T> Flow<List<T>>.withoutHidden(
        kind: StreamKind,
        crossinline categoryId: (T) -> String?,
    ): Flow<List<T>> = combine(
        settingsStore.settings.map { it.hiddenCategories(kind) }.distinctUntilChanged(),
    ) { list, hidden ->
        if (hidden.isEmpty()) list else list.filterNot { categoryId(it) in hidden }
    }

    suspend fun getChannel(playlistId: Long, streamId: String): Channel? =
        channelDao.getById(playlistId, streamId)?.toModel()

    /** Senderzahl für die Kategorie-Leiste, ohne die komplette Liste zu laden. */
    fun observeChannelCount(): Flow<Int> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            channelDao.observeCount(playlist.id)
        }

    /** Favoritenzahl für die Kategorie-Leiste, ohne die komplette Liste zu laden. */
    fun observeFavoriteCount(kind: StreamKind = StreamKind.LIVE): Flow<Int> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            userDataDao.observeFavoriteCount(playlist.id, kind.name)
        }

    fun observeMovies(categoryId: String?): Flow<List<Movie>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeMovies(playlist.id, categoryId).map { list -> list.map { it.toModel() } }
        }

    fun observeSeries(categoryId: String?): Flow<List<Series>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeSeries(playlist.id, categoryId).map { list -> list.map { it.toModel() } }
        }

    // -----------------------------------------------------------------------
    // Startansicht: nur so viele Titel, wie eine Reihe zeigt
    // -----------------------------------------------------------------------
    //
    // Die Beschränkung passiert in der Datenbank statt im Speicher – bei
    // Panels mit sechsstelligen Katalogen ist das der Unterschied zwischen
    // zwanzig gelesenen Zeilen und einer Viertelmillion.

    fun observeNewestMovies(limit: Int): Flow<List<Movie>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeNewestMovies(playlist.id, limit).map { list -> list.map { it.toModel() } }
        }

    fun observeCategoryMovies(categoryId: String, limit: Int): Flow<List<Movie>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeCategoryMovies(playlist.id, categoryId, limit)
                .map { list -> list.map { it.toModel() } }
        }

    fun observeNewestSeries(limit: Int): Flow<List<Series>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeNewestSeries(playlist.id, limit).map { list -> list.map { it.toModel() } }
        }

    fun observeCategorySeries(categoryId: String, limit: Int): Flow<List<Series>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeCategorySeries(playlist.id, categoryId, limit)
                .map { list -> list.map { it.toModel() } }
        }

    /** Zuletzt gesehene Filme samt Fortsetzpunkt. */
    fun observeRecentMovies(): Flow<List<RecentMovieRow>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest flowOf(emptyList())
            vodDao.observeRecentMovies(playlist.id, RECENT_HISTORY_SIZE)
        }

    /** Zuletzt gesehene Folgen samt Serie und Fortsetzpunkt. */
    fun observeRecentEpisodes(): Flow<List<RecentEpisodeRow>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest flowOf(emptyList())
            vodDao.observeRecentEpisodes(playlist.id, RECENT_HISTORY_SIZE)
        }

    /** Titelsuche über Filme – für die übergreifende Suche. */
    fun searchMovies(query: String): Flow<List<Movie>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null || query.isBlank()) return@flatMapLatest flowOf(emptyList())
            vodDao.searchMovies(playlist.id, query, SEARCH_LIMIT)
                .map { list -> list.map { it.toModel() } }
                .withoutHidden(StreamKind.VOD) { it.categoryId }
        }

    /** Titelsuche über Serien – für die übergreifende Suche. */
    fun searchSeries(query: String): Flow<List<Series>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null || query.isBlank()) return@flatMapLatest flowOf(emptyList())
            vodDao.searchSeries(playlist.id, query, SEARCH_LIMIT)
                .map { list -> list.map { it.toModel() } }
                .withoutHidden(StreamKind.SERIES) { it.categoryId }
        }

    suspend fun getMovie(streamId: String): Movie? {
        val playlist = playlistDao.getActive() ?: return null
        return vodDao.getMovie(playlist.id, streamId)?.toModel()
    }

    suspend fun getSeriesDetails(seriesId: String): Series? {
        val playlist = playlistDao.getActive() ?: return null
        return vodDao.getSeries(playlist.id, seriesId)?.toModel()
    }

    suspend fun getEpisode(episodeId: String): Episode? {
        val playlist = playlistDao.getActive() ?: return null
        return vodDao.getEpisode(playlist.id, episodeId)?.toModel()
    }

    fun observeEpisodes(seriesId: String): Flow<List<Episode>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            vodDao.observeEpisodes(playlist.id, seriesId).map { list -> list.map { it.toModel() } }
        }

    /**
     * Lädt die Episoden einer Serie vom Panel nach, falls der Cache noch leer
     * ist. `get_series_info` liefert bei großen Serien viele Daten – deshalb
     * wird nur einmalig nachgefragt, nicht bei jedem Öffnen der Detailseite.
     * M3U-Playlists kennen keine Serien und werden hier übersprungen.
     */
    suspend fun refreshSeriesEpisodes(seriesId: String) {
        val playlist = playlistDao.getActive() ?: return
        val alreadyCached = vodDao.observeEpisodes(playlist.id, seriesId).first().isNotEmpty()
        if (alreadyCached) return

        val model = playlist.toModel()
        if (model.type != PlaylistType.XTREAM) return

        runCatching {
            val response = xtreamApi.getSeriesInfo(model.credentials(), seriesId)
            val episodes = XtreamMapper.toEpisodes(seriesId, response, json)
            vodDao.insertEpisodes(episodes.map { it.toEntity(playlist.id) })
        }
    }

    // -----------------------------------------------------------------------
    // Favoriten & Verlauf
    // -----------------------------------------------------------------------

    /** Schaltet den Favoritenstatus um und meldet den neuen Zustand zurück. */
    suspend fun toggleFavorite(channel: Channel, kind: StreamKind = StreamKind.LIVE): Boolean {
        val isFavorite = userDataDao.isFavorite(channel.playlistId, channel.streamId, kind.name)
        if (isFavorite) {
            userDataDao.removeFavorite(channel.playlistId, channel.streamId, kind.name)
        } else {
            userDataDao.addFavorite(
                FavoriteEntity(
                    playlistId = channel.playlistId,
                    streamId = channel.streamId,
                    kind = kind.name,
                    addedAt = System.currentTimeMillis(),
                    // Neue Favoriten hinten anhängen: Senderplatz als Startsortierung.
                    sortOrder = channel.number,
                ),
            )
        }
        return !isFavorite
    }

    /**
     * Vermerkt einen Sender als "zuletzt gesehen".
     *
     * @param positionMs Wiedergabeposition für "Fortsetzen" – bei Live immer 0.
     */
    suspend fun markWatched(
        playlistId: Long,
        streamId: String,
        kind: StreamKind = StreamKind.LIVE,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
    ) {
        // Durchgeschaut? Dann raus aus "Weiterschauen".
        //
        // Ein Titel, der zu Ende gesehen wurde, gehört nicht in eine Liste,
        // die "hier bist du stehengeblieben" bedeutet – er stünde dort sonst
        // für immer und verdrängte, was der Zuschauer wirklich noch offen
        // hat. Und der gemerkte Fortsetzpunkt hilft auch nicht weiter: Wer
        // den Film erneut startet, will ihn von vorn, nicht ab Minute 118.
        //
        // Die letzten Prozente zählen bewusst schon als "zu Ende": Kaum
        // jemand sitzt den Abspann aus.
        if (isFinished(positionMs, durationMs)) {
            userDataDao.deleteRecent(playlistId, streamId, kind.name)
            return
        }

        userDataDao.upsertRecent(
            RecentEntity(
                playlistId = playlistId,
                streamId = streamId,
                kind = kind.name,
                watchedAt = System.currentTimeMillis(),
                positionMs = positionMs,
                durationMs = durationMs,
            ),
        )
        userDataDao.trimRecents(playlistId, kind.name, RECENT_HISTORY_SIZE)
    }

    /**
     * Ab wann ein Titel als zu Ende gesehen gilt.
     *
     * Ohne bekannte Laufzeit (Live TV, oder das Panel liefert keine) gibt es
     * nichts zu entscheiden – dann bleibt der Eintrag.
     */
    private fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= durationMs * FINISHED_FRACTION

    /** Nimmt einen Film von Hand aus "Weiterschauen". */
    suspend fun removeFromContinueWatching(streamId: String, kind: StreamKind) {
        val playlist = playlistDao.getActive() ?: return
        userDataDao.deleteRecent(playlist.id, streamId, kind.name)
    }

    /** Nimmt eine ganze Serie von Hand aus "Weiterschauen" – mit allen Folgen. */
    suspend fun removeSeriesFromContinueWatching(seriesId: String) {
        val playlist = playlistDao.getActive() ?: return
        userDataDao.deleteRecentSeries(playlist.id, seriesId)
    }

    suspend fun getResumePosition(playlistId: Long, streamId: String, kind: StreamKind): Long =
        userDataDao.getRecent(playlistId, streamId, kind.name)?.positionMs ?: 0L

    // -----------------------------------------------------------------------
    // Wiedergabe-URLs
    // -----------------------------------------------------------------------

    /**
     * Ermittelt die abspielbare URL eines Senders.
     *
     * Reihenfolge:
     * 1. `directUrl` – bei M3U immer gesetzt, bei Xtream nur wenn das Panel
     *    ausdrücklich eine eigene Quelle vorgibt.
     * 2. Zusammengebaute Xtream-URL.
     *
     * @param preferHls `true` erzwingt `.m3u8` statt `.ts`. HLS erlaubt
     *        adaptives Bitrate-Switching, `.ts` startet dafür schneller und
     *        ist auf schwacher Hardware (Fire TV Stick Lite) stabiler.
     */
    suspend fun resolveStreamUrl(channel: Channel, preferHls: Boolean = false): String? {
        channel.directUrl?.let { return it }

        val playlist = playlistDao.getById(channel.playlistId)?.toModel() ?: return null
        if (playlist.type != PlaylistType.XTREAM) return null

        return xtreamApi.buildStreamUrl(
            credentials = playlist.credentials(),
            kind = StreamKind.LIVE,
            streamId = channel.streamId,
            extension = if (preferHls) "m3u8" else "ts",
        )
    }

    suspend fun resolveMovieUrl(movie: Movie): String? {
        movie.directUrl?.let { return it }

        val playlist = playlistDao.getById(movie.playlistId)?.toModel() ?: return null
        if (playlist.type != PlaylistType.XTREAM) return null
        return xtreamApi.buildStreamUrl(
            credentials = playlist.credentials(),
            kind = StreamKind.VOD,
            streamId = movie.streamId,
            extension = movie.containerExtension,
        )
    }

    suspend fun resolveEpisodeUrl(playlistId: Long, episode: Episode): String? {
        // Bei M3U steht die Adresse bereits in der Datei; nur Xtream baut sie
        // zur Laufzeit aus den Zugangsdaten zusammen.
        episode.directUrl?.let { return it }

        val playlist = playlistDao.getById(playlistId)?.toModel() ?: return null
        if (playlist.type != PlaylistType.XTREAM) return null
        return xtreamApi.buildStreamUrl(
            credentials = playlist.credentials(),
            kind = StreamKind.SERIES,
            streamId = episode.episodeId,
            extension = episode.containerExtension,
        )
    }

    companion object {
        /** So viele Sender zeigt die Kategorie "Zuletzt gesehen". */
        private const val RECENT_LIMIT = 30

        /** So viele Einträge behält der Verlauf insgesamt. */
        private const val RECENT_HISTORY_SIZE = 50

        /**
         * Ab diesem Anteil der Laufzeit gilt ein Titel als zu Ende gesehen.
         *
         * 95 % statt 100 %: Abspann, Vorschau auf die nächste Folge und ein
         * paar Sekunden Schwarzbild am Ende sind bei fast jedem Titel dabei.
         * Wer bis dorthin gekommen ist, hat ihn gesehen – auf die letzten
         * Prozente zu warten hieße, dass praktisch nie etwas verschwindet.
         */
        private const val FINISHED_FRACTION = 0.95

        /**
         * Höchstzahl Treffer je Bereich in der Suche. Ein kurzer Begriff
         * trifft in einem großen Katalog tausende Einträge; angesehen wird
         * ohnehin nur der Anfang.
         */
        private const val SEARCH_LIMIT = 100
    }
}

/** Bequemer Zugriff auf die Xtream-Zugangsdaten einer Playlist. */
fun Playlist.credentials() = XtreamCredentials(
    baseUrl = serverUrl,
    username = username,
    password = password,
)
