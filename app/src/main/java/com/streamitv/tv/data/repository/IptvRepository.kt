package com.streamitv.tv.data.repository

import com.streamitv.tv.data.local.CategoryDao
import com.streamitv.tv.data.local.ChannelDao
import com.streamitv.tv.data.local.FavoriteEntity
import com.streamitv.tv.data.local.PlaylistDao
import com.streamitv.tv.data.local.RecentEntity
import com.streamitv.tv.data.local.UserDataDao
import com.streamitv.tv.data.local.VodDao
import com.streamitv.tv.data.local.toEntity
import com.streamitv.tv.data.model.Category
import com.streamitv.tv.data.model.Channel
import com.streamitv.tv.data.model.Movie
import com.streamitv.tv.data.model.Playlist
import com.streamitv.tv.data.model.PlaylistType
import com.streamitv.tv.data.model.Series
import com.streamitv.tv.data.model.StreamKind
import com.streamitv.tv.data.remote.xtream.XtreamApi
import com.streamitv.tv.data.remote.xtream.XtreamCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
