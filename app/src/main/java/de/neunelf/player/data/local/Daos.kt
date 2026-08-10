package de.neunelf.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Alle Datenbankzugriffe der App.
 *
 * Konventionen:
 * - Lese-Abfragen für die UI geben `Flow` zurück, damit sich Bildschirme
 *   nach einem Sync automatisch aktualisieren.
 * - Schreibende Massen-Operationen laufen in `@Transaction`, sonst dauert
 *   ein Import mit 30.000 Sendern auf einem Fire TV Stick Minuten statt Sekunden.
 */

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET isActive = (id = :id)")
    suspend fun setActive(id: Long)

    @Query("UPDATE playlists SET lastSyncAt = :timestamp WHERE id = :id")
    suspend fun markSynced(id: Long, timestamp: Long)

    @Query("UPDATE playlists SET lastEpgSyncAt = :timestamp WHERE id = :id")
    suspend fun markEpgSynced(id: Long, timestamp: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    /** Fügt eine Playlist ein und macht sie sofort zur aktiven. */
    @Transaction
    suspend fun insertAndActivate(playlist: PlaylistEntity): Long {
        val id = insert(playlist)
        setActive(id)
        return id
    }
}

@Dao
interface CategoryDao {

    /**
     * Kategorien inklusive Senderanzahl. Leere Kategorien fliegen raus –
     * Panels liefern regelmäßig Bouquets ohne Inhalt.
     */
    @Query(
        """
        SELECT c.*, (
            SELECT COUNT(*) FROM channels ch
            WHERE ch.playlistId = c.playlistId AND ch.categoryId = c.categoryId
        ) AS channelCount
        FROM categories c
        WHERE c.playlistId = :playlistId AND c.kind = :kind
        ORDER BY c.sortOrder, c.name
        """,
    )
    fun observeWithCounts(playlistId: Long, kind: String): Flow<List<CategoryWithCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId AND kind = :kind")
    suspend fun deleteFor(playlistId: Long, kind: String)

    @Transaction
    suspend fun replaceAll(playlistId: Long, kind: String, categories: List<CategoryEntity>) {
        deleteFor(playlistId, kind)
        insertAll(categories)
    }
}

/** Kategorie plus berechnete Senderanzahl (siehe [CategoryDao.observeWithCounts]). */
data class CategoryWithCount(
    val playlistId: Long,
    val categoryId: String,
    val kind: String,
    val name: String,
    val sortOrder: Int,
    val channelCount: Int,
)

@Dao
interface ChannelDao {

    /**
     * Sender einer Kategorie inkl. Favoriten-Flag und Zeitpunkt des letzten
     * Aufrufs. Die beiden LEFT JOINs ersparen der UI ein separates Nachladen.
     */
    @Query(
        """
        SELECT ch.*,
               (f.streamId IS NOT NULL) AS isFavorite,
               IFNULL(r.watchedAt, 0) AS lastWatchedAt
        FROM channels ch
        LEFT JOIN favorites f
               ON f.playlistId = ch.playlistId AND f.streamId = ch.streamId AND f.kind = 'LIVE'
        LEFT JOIN recents r
               ON r.playlistId = ch.playlistId AND r.streamId = ch.streamId AND r.kind = 'LIVE'
        WHERE ch.playlistId = :playlistId AND ch.categoryId = :categoryId
        ORDER BY ch.number, ch.name
        """,
    )
    fun observeByCategory(playlistId: Long, categoryId: String): Flow<List<ChannelRow>>

    @Query(
        """
        SELECT ch.*,
               (f.streamId IS NOT NULL) AS isFavorite,
               IFNULL(r.watchedAt, 0) AS lastWatchedAt
        FROM channels ch
        LEFT JOIN favorites f
               ON f.playlistId = ch.playlistId AND f.streamId = ch.streamId AND f.kind = 'LIVE'
        LEFT JOIN recents r
               ON r.playlistId = ch.playlistId AND r.streamId = ch.streamId AND r.kind = 'LIVE'
        WHERE ch.playlistId = :playlistId
        ORDER BY ch.number, ch.name
        """,
    )
    fun observeAll(playlistId: Long): Flow<List<ChannelRow>>

    /** Favoriten in der vom Nutzer gewählten Reihenfolge. */
    @Query(
        """
        SELECT ch.*, 1 AS isFavorite, IFNULL(r.watchedAt, 0) AS lastWatchedAt
        FROM channels ch
        INNER JOIN favorites f
               ON f.playlistId = ch.playlistId AND f.streamId = ch.streamId AND f.kind = 'LIVE'
        LEFT JOIN recents r
               ON r.playlistId = ch.playlistId AND r.streamId = ch.streamId AND r.kind = 'LIVE'
        WHERE ch.playlistId = :playlistId
        ORDER BY f.sortOrder, f.addedAt
        """,
    )
    fun observeFavorites(playlistId: Long): Flow<List<ChannelRow>>

    /** Zuletzt gesehene Sender, neueste zuerst. */
    @Query(
        """
        SELECT ch.*,
               (f.streamId IS NOT NULL) AS isFavorite,
               r.watchedAt AS lastWatchedAt
        FROM channels ch
        INNER JOIN recents r
               ON r.playlistId = ch.playlistId AND r.streamId = ch.streamId AND r.kind = 'LIVE'
        LEFT JOIN favorites f
               ON f.playlistId = ch.playlistId AND f.streamId = ch.streamId AND f.kind = 'LIVE'
        WHERE ch.playlistId = :playlistId
        ORDER BY r.watchedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(playlistId: Long, limit: Int): Flow<List<ChannelRow>>

    @Query(
        """
        SELECT ch.*,
               (f.streamId IS NOT NULL) AS isFavorite,
               IFNULL(r.watchedAt, 0) AS lastWatchedAt
        FROM channels ch
        LEFT JOIN favorites f
               ON f.playlistId = ch.playlistId AND f.streamId = ch.streamId AND f.kind = 'LIVE'
        LEFT JOIN recents r
               ON r.playlistId = ch.playlistId AND r.streamId = ch.streamId AND r.kind = 'LIVE'
        WHERE ch.playlistId = :playlistId AND ch.name LIKE '%' || :query || '%'
        ORDER BY ch.number, ch.name
        LIMIT 200
        """,
    )
    fun search(playlistId: Long, query: String): Flow<List<ChannelRow>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND streamId = :streamId")
    suspend fun getById(playlistId: Long, streamId: String): ChannelEntity?

    /**
     * Reine Zählung für die Kategorie-Leiste ("Alle Sender"). Bewusst getrennt
     * von [observeAll]: bei 8.000+ Sendern nur für eine Zahl die komplette
     * Senderliste samt Favoriten-/Verlaufs-JOIN zu laden, wäre unnötiger
     * Speicher- und CPU-Aufwand bei jedem Update.
     */
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    fun observeCount(playlistId: Long): Flow<Int>

    /** Alle EPG-IDs der Playlist – Filter für den XMLTV-Import. */
    @Query("SELECT DISTINCT epgChannelId FROM channels WHERE playlistId = :playlistId AND epgChannelId IS NOT NULL")
    suspend fun getEpgChannelIds(playlistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)

    @Transaction
    suspend fun replaceAll(playlistId: Long, channels: List<ChannelEntity>) {
        deleteFor(playlistId)
        // In Blöcken einfügen: SQLite hat ein Limit von 999 Bind-Variablen
        // pro Statement, und kleinere Batches halten den Speicher flach.
        channels.chunked(500).forEach { insertAll(it) }
    }
}

@Dao
interface VodDao {

    @Query(
        """
        SELECT * FROM movies
        WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY addedAt DESC, name
        """,
    )
    fun observeMovies(playlistId: Long, categoryId: String?): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE playlistId = :playlistId AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY name
        """,
    )
    fun observeSeries(playlistId: Long, categoryId: String?): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeEpisodes(playlistId: Long, seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND streamId = :streamId")
    suspend fun getMovie(playlistId: Long, streamId: String): MovieEntity?

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun getSeries(playlistId: Long, seriesId: String): SeriesEntity?

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun getEpisode(playlistId: Long, episodeId: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<MovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: List<SeriesEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateMovie(movie: MovieEntity)

    @Query("DELETE FROM movies WHERE playlistId = :playlistId")
    suspend fun deleteMovies(playlistId: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    suspend fun deleteSeries(playlistId: Long)

    @Transaction
    suspend fun replaceMovies(playlistId: Long, movies: List<MovieEntity>) {
        deleteMovies(playlistId)
        movies.chunked(500).forEach { insertMovies(it) }
    }

    @Transaction
    suspend fun replaceSeries(playlistId: Long, series: List<SeriesEntity>) {
        deleteSeries(playlistId)
        series.chunked(500).forEach { insertSeries(it) }
    }
}

@Dao
interface EpgDao {

    /**
     * Sendungen der angegebenen Sender, die sich mit dem sichtbaren
     * Zeitfenster überschneiden.
     *
     * Die Überlappungsbedingung ist `start < fensterEnde && ende > fensterStart`
     * – so werden auch Sendungen erfasst, die vor dem Fenster begonnen haben.
     *
     * **Nie direkt aufrufen**, sondern über
     * [de.neunelf.player.data.repository.EpgRepository]: Jede Sender-ID im
     * `IN` belegt eine SQL-Variable, und SQLite erlaubt nur 999 pro Statement.
     * Bei Playlists mit mehreren tausend Sendern scheitert ein ungeteilter
     * Aufruf mit `too many SQL variables`; das Repository zerlegt die Liste
     * deshalb in Blöcke.
     */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE playlistId = :playlistId
          AND epgChannelId IN (:channelIds)
          AND startAt < :windowEnd AND endAt > :windowStart
        ORDER BY epgChannelId, startAt
        """,
    )
    fun observeWindowChunk(
        playlistId: Long,
        channelIds: List<String>,
        windowStart: Long,
        windowEnd: Long,
    ): Flow<List<EpgProgramEntity>>

    /** Die nächsten Sendungen eines einzelnen Senders (für das Info-Overlay). */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE epgChannelId = :channelId AND endAt > :now
        ORDER BY startAt
        LIMIT :limit
        """,
    )
    suspend fun getUpcoming(channelId: String, now: Long, limit: Int): List<EpgProgramEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(programs: List<EpgProgramEntity>)

    /**
     * Blockierende Variante für den XMLTV-Import: der Parser ruft seinen
     * Callback synchron auf, dort ist kein `suspend` möglich. Der Aufrufer
     * ist bereits auf einem IO-Thread.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllBlocking(programs: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)

    /** Räumt Sendungen weg, die länger als [before] vorbei sind. */
    @Query("DELETE FROM epg_programs WHERE endAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM epg_programs WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int
}

@Dao
interface UserDataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId AND streamId = :streamId AND kind = :kind")
    suspend fun removeFavorite(playlistId: Long, streamId: String, kind: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE playlistId = :playlistId AND streamId = :streamId AND kind = :kind)")
    suspend fun isFavorite(playlistId: Long, streamId: String, kind: String): Boolean

    @Query("SELECT COUNT(*) FROM favorites WHERE playlistId = :playlistId AND kind = :kind")
    fun observeFavoriteCount(playlistId: Long, kind: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(recent: RecentEntity)

    @Query("SELECT * FROM recents WHERE playlistId = :playlistId AND streamId = :streamId AND kind = :kind")
    suspend fun getRecent(playlistId: Long, streamId: String, kind: String): RecentEntity?

    /** Hält den Verlauf klein: alles außerhalb der neuesten [keep] Einträge fliegt raus. */
    @Query(
        """
        DELETE FROM recents
        WHERE playlistId = :playlistId AND kind = :kind AND streamId NOT IN (
            SELECT streamId FROM recents
            WHERE playlistId = :playlistId AND kind = :kind
            ORDER BY watchedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun trimRecents(playlistId: Long, kind: String, keep: Int)
}
