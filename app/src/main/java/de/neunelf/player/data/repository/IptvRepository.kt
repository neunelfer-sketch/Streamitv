package de.neunelf.player.data.repository

import de.neunelf.player.core.TimeFormat
import de.neunelf.player.data.local.CategoryDao
import de.neunelf.player.data.local.ChannelDao
import de.neunelf.player.data.local.ChannelOverrideEntity
import de.neunelf.player.data.local.FavoriteEntity
import de.neunelf.player.data.local.PlaylistDao
import de.neunelf.player.data.local.RecentEntity
import de.neunelf.player.data.local.UserDataDao
import de.neunelf.player.data.local.VodDao
import de.neunelf.player.data.local.toEntity
import de.neunelf.player.data.model.Category
import de.neunelf.player.data.model.Channel
import de.neunelf.player.data.model.EpgProgram
import de.neunelf.player.data.model.Episode
import de.neunelf.player.data.model.ManagedChannel
import de.neunelf.player.data.model.Movie
import de.neunelf.player.data.model.Playlist
import de.neunelf.player.data.model.PlaylistType
import de.neunelf.player.data.model.Series
import de.neunelf.player.data.model.StreamKind
import de.neunelf.player.data.remote.xtream.XtreamApi
import de.neunelf.player.data.remote.xtream.XtreamCredentials
import de.neunelf.player.data.remote.xtream.XtreamMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    suspend fun savePlaylist(playlist: Playlist): Long =
        playlistDao.insertAndActivate(playlist.toEntity(isActive = true))

    suspend fun setActivePlaylist(id: Long) = playlistDao.setActive(id)

    suspend fun deletePlaylist(id: Long) = playlistDao.delete(id)

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
        }

    suspend fun getChannel(playlistId: Long, streamId: String): Channel? =
        channelDao.getById(playlistId, streamId)?.toModel()

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

    suspend fun getMovie(playlistId: Long, streamId: String): Movie? =
        vodDao.getMovie(playlistId, streamId)?.toModel()

    suspend fun getSeries(playlistId: Long, seriesId: String): Series? =
        vodDao.getSeriesById(playlistId, seriesId)?.toModel()

    fun observeEpisodes(playlistId: Long, seriesId: String): Flow<List<Episode>> =
        vodDao.observeEpisodes(playlistId, seriesId).map { list -> list.map { it.toModel() } }

    /**
     * Lädt Plot/Jahr/Laufzeit eines Films nach (`get_vod_info`), falls sie
     * noch nicht im Cache stehen. Panels liefern diese Details nicht schon
     * beim VOD-Listing, weil das sonst jeden Import vervielfachen würde.
     */
    suspend fun enrichMovieIfNeeded(movie: Movie): Movie {
        if (movie.plot != null) return movie
        val playlist = playlistDao.getById(movie.playlistId)?.toModel() ?: return movie
        if (playlist.type != PlaylistType.XTREAM) return movie
        val response = xtreamApi.getVodInfo(playlist.credentials(), movie.streamId)
        val enriched = XtreamMapper.enrichMovie(movie, response)
        vodDao.updateMovie(enriched.toEntity())
        return enriched
    }

    /** Lädt Staffeln/Episoden einer Serie nach (`get_series_info`), sofern noch nicht im Cache. */
    suspend fun ensureEpisodesLoaded(series: Series) {
        if (vodDao.countEpisodes(series.playlistId, series.seriesId) > 0) return
        val playlist = playlistDao.getById(series.playlistId)?.toModel() ?: return
        if (playlist.type != PlaylistType.XTREAM) return
        val response = xtreamApi.getSeriesInfo(playlist.credentials(), series.seriesId)
        val episodes = XtreamMapper.toEpisodes(series.seriesId, response, json)
        vodDao.insertEpisodes(episodes.map { it.toEntity(series.playlistId) })
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

    /**
     * Catch-up-URL für eine vergangene Sendung. `null`, wenn der Sender kein
     * Archiv anbietet oder die Playlist keine Xtream-Quelle ist (M3U kennt
     * kein Timeshift-Schema).
     */
    suspend fun resolveCatchupUrl(channel: Channel, program: EpgProgram): String? {
        if (!channel.hasArchive) return null
        val playlist = playlistDao.getById(channel.playlistId)?.toModel() ?: return null
        if (playlist.type != PlaylistType.XTREAM) return null

        val durationMinutes = ((program.endAt - program.startAt) / 60_000L).toInt().coerceAtLeast(1)
        return xtreamApi.buildTimeshiftUrl(
            credentials = playlist.credentials(),
            streamId = channel.streamId,
            startFormatted = TimeFormat.xtreamTimeshiftStart(program.startAt),
            durationMinutes = durationMinutes,
        )
    }

    suspend fun resolveMovieUrl(movie: Movie): String? {
        val playlist = playlistDao.getById(movie.playlistId)?.toModel() ?: return null
        if (playlist.type != PlaylistType.XTREAM) return null
        return xtreamApi.buildStreamUrl(
            credentials = playlist.credentials(),
            kind = StreamKind.VOD,
            streamId = movie.streamId,
            extension = movie.containerExtension,
        )
    }

    suspend fun resolveEpisodeUrl(playlistId: Long, episodeId: String, extension: String): String? {
        val playlist = playlistDao.getById(playlistId)?.toModel() ?: return null
        if (playlist.type != PlaylistType.XTREAM) return null
        return xtreamApi.buildStreamUrl(
            credentials = playlist.credentials(),
            kind = StreamKind.SERIES,
            streamId = episodeId,
            extension = extension,
        )
    }

    // -----------------------------------------------------------------------
    // Kanalverwaltung (Einstellungen): Sender sortieren/ausblenden
    // -----------------------------------------------------------------------

    /** Alle Sender einer Kategorie inkl. ausgeblendeter – für die Verwaltungsansicht. */
    fun observeManagedChannels(categoryId: String?): Flow<List<ManagedChannel>> =
        playlistDao.observeActive().flatMapLatest { playlist ->
            if (playlist == null) return@flatMapLatest emptyFlow()
            channelDao.observeManageable(playlist.id, categoryId).map { rows ->
                rows.map { ManagedChannel(it.streamId, it.name, it.logoUrl, it.number, it.isHidden) }
            }
        }

    suspend fun setChannelHidden(streamId: String, hidden: Boolean) {
        val playlist = playlistDao.getActive() ?: return
        val current = channelDao.getOverride(playlist.id, streamId)
        channelDao.upsertOverride(
            ChannelOverrideEntity(playlist.id, streamId, isHidden = hidden, customOrder = current?.customOrder),
        )
    }

    /**
     * Verschiebt einen Sender um eine Position (`direction` = -1 hoch, +1 runter)
     * innerhalb derselben Kategorie. Nummeriert dabei die ganze Kategorie neu
     * durch – so bleiben Positionen eindeutig, auch nachdem das Panel seine
     * eigene `number`-Zählung ändert.
     */
    suspend fun moveChannel(categoryId: String?, streamId: String, direction: Int) {
        val playlist = playlistDao.getActive() ?: return
        val list = channelDao.observeManageable(playlist.id, categoryId).first()
        val index = list.indexOfFirst { it.streamId == streamId }
        val targetIndex = index + direction
        if (index < 0 || targetIndex !in list.indices) return

        list.forEachIndexed { i, row ->
            val newOrder = when (i) {
                index -> targetIndex
                targetIndex -> index
                else -> i
            }
            channelDao.upsertOverride(
                ChannelOverrideEntity(playlist.id, row.streamId, isHidden = row.isHidden, customOrder = newOrder),
            )
        }
    }

    companion object {
        /** So viele Sender zeigt die Kategorie "Zuletzt gesehen". */
        private const val RECENT_LIMIT = 30

        /** So viele Einträge behält der Verlauf insgesamt. */
        private const val RECENT_HISTORY_SIZE = 50
    }
}

/** Bequemer Zugriff auf die Xtream-Zugangsdaten einer Playlist. */
fun Playlist.credentials() = XtreamCredentials(
    baseUrl = serverUrl,
    username = username,
    password = password,
)
