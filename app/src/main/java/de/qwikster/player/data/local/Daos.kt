package de.qwikster.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
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

/**
 * Schlanke Projektion für "was läuft gerade" in der Senderliste
 * (siehe [EpgDao.observeCurrentPrograms]).
 *
 * Bewusst kein [EpgProgramEntity]: Dessen `description` ist das größte Feld
 * und wird in der Senderliste nie angezeigt. Bei 30.000 Sendern entscheidet
 * das über hunderte Megabyte.
 */
data class CurrentProgramRow(
    val epgChannelId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
)

/** Ein bereits gegen `get_vod_info` abgeglichenes Filmposter. */
data class MoviePosterEnrichment(
    val streamId: String,
    val posterUrl: String?,
    val plot: String?,
)

/** Zuletzt gesehener Film samt Fortsetzpunkt. */
data class RecentMovieRow(
    val streamId: String,
    val name: String,
    val posterUrl: String?,
    val watchedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Zuletzt gesehene Folge samt ihrer Serie.
 *
 * Der Verlauf kennt nur Folgen – welche Serie dazugehört, steht erst in
 * der Episodentabelle. Genau diese Verbindung braucht die Anzeige
 * "Zuletzt gesehen", damit der Zuschauer nicht selbst nachsehen muss, bei
 * welcher Staffel und Folge er stehengeblieben ist.
 */
data class RecentEpisodeRow(
    val seriesId: String,
    val seriesName: String,
    val posterUrl: String?,
    val episodeId: String,
    val season: Int,
    val episodeNumber: Int,
    val watchedAt: Long,
    val positionMs: Long,
    val durationMs: Long,
)

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
        ORDER BY lastModified DESC, name
        """,
    )
    fun observeSeries(playlistId: Long, categoryId: String?): Flow<List<SeriesEntity>>

    // -----------------------------------------------------------------------
    // Abfragen für die Startansicht
    // -----------------------------------------------------------------------
    //
    // Die Reihen der Startansicht zeigen je zwanzig Poster. Bis hierher holte
    // sich die Oberfläche dafür den **gesamten** Bestand und sortierte,
    // gruppierte und beschnitt ihn im Speicher. Bei einem Panel mit 178.000
    // Filmen sind das eine Viertelmillion frisch erzeugter Objekte je
    // Aktualisierung – auf einem Fire TV Stick der sichere Weg in lange
    // Speicherbereinigungen und irgendwann in den Abbruch wegen
    // Speichermangels.
    //
    // Die Beschränkung gehört in die Datenbank. `LIMIT` liest genau so viele
    // Zeilen, wie gebraucht werden; der Index auf `(playlistId, categoryId)`
    // trägt dabei die Einschränkung je Kategorie.

    @Query(
        """
        SELECT * FROM movies
        WHERE playlistId = :playlistId
        ORDER BY addedAt DESC, name
        LIMIT :limit
        """,
    )
    fun observeNewestMovies(playlistId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT * FROM movies
        WHERE playlistId = :playlistId AND categoryId = :categoryId
        ORDER BY addedAt DESC, name
        LIMIT :limit
        """,
    )
    fun observeCategoryMovies(playlistId: Long, categoryId: String, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE playlistId = :playlistId
        ORDER BY lastModified DESC, name
        LIMIT :limit
        """,
    )
    fun observeNewestSeries(playlistId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query(
        """
        SELECT * FROM series
        WHERE playlistId = :playlistId AND categoryId = :categoryId
        ORDER BY lastModified DESC, name
        LIMIT :limit
        """,
    )
    fun observeCategorySeries(playlistId: Long, categoryId: String, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY season, episodeNumber")
    fun observeEpisodes(playlistId: Long, seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND streamId = :streamId")
    suspend fun getMovie(playlistId: Long, streamId: String): MovieEntity?

    /**
     * Titelsuche über die Filme.
     *
     * `LIMIT` ist wichtig: Ein kurzer Suchbegriff trifft in einem großen
     * Katalog tausende Einträge, von denen niemand mehr als die ersten
     * ansieht.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%'
        ORDER BY name
        LIMIT :limit
        """,
    )
    fun searchMovies(playlistId: Long, query: String, limit: Int): Flow<List<MovieEntity>>

    /** Zuletzt gesehene Filme, neueste zuerst. */
    @Query(
        """
        SELECT m.streamId AS streamId, m.name AS name, m.posterUrl AS posterUrl,
               r.watchedAt AS watchedAt, r.positionMs AS positionMs, r.durationMs AS durationMs
        FROM recents r
        INNER JOIN movies m
               ON m.playlistId = r.playlistId AND m.streamId = r.streamId
        WHERE r.playlistId = :playlistId AND r.kind = 'VOD'
        ORDER BY r.watchedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecentMovies(playlistId: Long, limit: Int): Flow<List<RecentMovieRow>>

    /**
     * Zuletzt gesehene Folgen, neueste zuerst – mit ihrer Serie.
     *
     * Bewusst ohne Gruppierung je Serie: Das ließe sich in SQL nur mit einer
     * verschachtelten Unterabfrage lösen. Da der Verlauf ohnehin auf wenige
     * Dutzend Einträge begrenzt ist, fasst der Aufrufer die Folgen einer
     * Serie im Speicher zusammen – schlichter und leichter nachzuvollziehen.
     */
    @Query(
        """
        SELECT s.seriesId AS seriesId, s.name AS seriesName, s.posterUrl AS posterUrl,
               e.episodeId AS episodeId, e.season AS season, e.episodeNumber AS episodeNumber,
               r.watchedAt AS watchedAt, r.positionMs AS positionMs, r.durationMs AS durationMs
        FROM recents r
        INNER JOIN episodes e
               ON e.playlistId = r.playlistId AND e.episodeId = r.streamId
        INNER JOIN series s
               ON s.playlistId = e.playlistId AND s.seriesId = e.seriesId
        WHERE r.playlistId = :playlistId AND r.kind = 'SERIES'
        ORDER BY r.watchedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecentEpisodes(playlistId: Long, limit: Int): Flow<List<RecentEpisodeRow>>

    /** Titelsuche über die Serien. */
    @Query(
        """
        SELECT * FROM series
        WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%'
        ORDER BY name
        LIMIT :limit
        """,
    )
    fun searchSeries(playlistId: Long, query: String, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun getSeries(playlistId: Long, seriesId: String): SeriesEntity?

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun getEpisode(playlistId: Long, episodeId: String): EpisodeEntity?

    /**
     * "Zuerst gesehen"-Zeitpunkt je Film, für den anstehenden Neu-Import.
     *
     * M3U-Dateien liefern kein Datum, wann ein Titel hinzukam – der
     * komplette Bestand wird bei jedem Sync neu geschrieben (siehe
     * [replaceMovies]). Ohne diesen Zwischenschritt bekäme dabei jeder Film
     * denselben Zeitpunkt "jetzt" und "Neu hinzugefügt" würde nie den
     * tatsächlich neuen Titeln entsprechen. Der Aufrufer trägt den alten
     * Wert für schon bekannte Filme wieder ein; nur echte Neuzugänge
     * bekommen die aktuelle Zeit.
     */
    @MapInfo(keyColumn = "streamId", valueColumn = "addedAt")
    @Query("SELECT streamId, addedAt FROM movies WHERE playlistId = :playlistId")
    suspend fun getMovieAddedTimes(playlistId: Long): Map<String, Long>

    /** Dasselbe wie [getMovieAddedTimes], nur für Serien. */
    @MapInfo(keyColumn = "seriesId", valueColumn = "lastModified")
    @Query("SELECT seriesId, lastModified FROM series WHERE playlistId = :playlistId")
    suspend fun getSeriesModifiedTimes(playlistId: Long): Map<String, Long>

    /**
     * Filme, deren Poster bereits per `get_vod_info` durch ein hochwertiges
     * Cover ersetzt wurde (siehe [PlaylistSyncer.enrichMoviePosters]).
     *
     * `plot` ist der Marker dafür: Die Übersichts-Antwort des Panels
     * (`get_vod_streams`) liefert ihn nie, nur die Detailabfrage. Ein
     * gesetzter Plot heißt also zuverlässig "schon angereichert" – ohne
     * eigene Merker-Spalte. Wichtig bei jedem Resync: [replaceMovies]
     * schreibt die Tabelle komplett neu, ohne dieses Zwischenspeichern ginge
     * jedes bereits gefundene Cover beim nächsten "Senderliste
     * aktualisieren" wieder verloren.
     */
    @Query("SELECT streamId, posterUrl, plot FROM movies WHERE playlistId = :playlistId AND plot IS NOT NULL")
    suspend fun getEnrichedMoviePosters(playlistId: Long): List<MoviePosterEnrichment>

    /**
     * Bereits bekannte Laufzeiten je Film.
     *
     * Sie stammen aus der Detailabfrage (`get_vod_info`), nicht aus der
     * Übersicht – deshalb kennt der Import sie nur für Titel, die schon
     * einmal angereichert wurden. Genutzt wird das als Gegenbeweis beim
     * Aussortieren von Dauerkanälen: Was eine Laufzeit hat, ist ein Film.
     */
    @MapInfo(keyColumn = "streamId", valueColumn = "durationSecs")
    @Query("SELECT streamId, durationSecs FROM movies WHERE playlistId = :playlistId AND durationSecs > 0")
    suspend fun getMovieDurations(playlistId: Long): Map<String, Int>

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

    @Query("DELETE FROM episodes WHERE playlistId = :playlistId")
    suspend fun deleteEpisodes(playlistId: Long)

    @Transaction
    suspend fun replaceEpisodes(playlistId: Long, episodes: List<EpisodeEntity>) {
        deleteEpisodes(playlistId)
        episodes.chunked(500).forEach { insertEpisodes(it) }
    }

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
     * [de.qwikster.player.data.repository.EpgRepository]: Jede Sender-ID im
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

    /**
     * Was auf den Sendern der Playlist **gerade jetzt** läuft – eine Zeile
     * je Sender, und nur die vier Spalten, die die Senderliste anzeigt.
     *
     * Das ist die tragende Abfrage für den Hauptbildschirm, und die Form ist
     * bewusst so eng gewählt:
     *
     * - **Kein `IN (:channelIds)`.** Damit gibt es keine Grenze durch die
     *   999 SQL-Variablen von SQLite und kein Aufteilen in Blöcke. Der
     *   Aufrufer ordnet die Zeilen im Speicher den Sendern zu.
     * - **Nur der laufende Zeitpunkt statt eines Zwölf-Stunden-Fensters.**
     *   Ein Fenster liefert je Sender rund ein Dutzend Sendungen; hier ist es
     *   höchstens eine. Bei 30.000 Sendern sind das 30.000 statt 360.000
     *   Zeilen.
     * - **Ohne `description`.** Beschreibungstexte sind das mit Abstand
     *   größte Feld und werden in der Senderliste überhaupt nicht angezeigt.
     *
     * Zusammen ist das der Unterschied zwischen wenigen Megabyte und
     * mehreren hundert – letzteres beendet die App auf einem Fire TV Stick
     * mit `OutOfMemoryError`.
     *
     * [earliestStart] begrenzt zusätzlich die *gelesene* Datenmenge: Ohne
     * untere Schranke müsste SQLite trotz Index alle Sendungen der Playlist
     * ab dem ältesten Eintrag prüfen. Da keine Sendung länger als einen Tag
     * dauert, genügt ein Rückblick von 24 Stunden, und der Index
     * `(playlistId, startAt)` liest nur noch dieses schmale Band.
     */
    @Query(
        """
        SELECT epgChannelId, startAt, endAt, title FROM epg_programs
        WHERE playlistId = :playlistId
          AND startAt > :earliestStart AND startAt <= :now
          AND endAt > :now
        """,
    )
    fun observeCurrentPrograms(
        playlistId: Long,
        now: Long,
        earliestStart: Long,
    ): Flow<List<CurrentProgramRow>>

    /**
     * Laufende und folgende Sendung eines **einzelnen** Senders.
     *
     * Für die Info-Leiste des Players: Die zeigt zusätzlich "Danach", das
     * die schlanke [observeCurrentPrograms] bewusst nicht mitliefert. Weil
     * es nur um den gerade laufenden Sender geht, sind vier Zeilen genug.
     */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE playlistId = :playlistId AND epgChannelId = :channelId AND endAt > :now
        ORDER BY startAt
        LIMIT 4
        """,
    )
    fun observeAroundNow(playlistId: Long, channelId: String, now: Long): Flow<List<EpgProgramEntity>>

    /**
     * Die nächsten Sendungen eines einzelnen Senders (für das Info-Overlay).
     *
     * `playlistId` gehört in die Bedingung: EPG-Kennungen sind nicht
     * eindeutig, zwei Playlists tragen für denselben Sender problemlos
     * beide "rtl.de". Ohne die Einschränkung mischten sich die Sendungen
     * einer gar nicht aktiven Playlist in die Anzeige.
     */
    @Query(
        """
        SELECT * FROM epg_programs
        WHERE playlistId = :playlistId AND epgChannelId = :channelId AND endAt > :now
        ORDER BY startAt
        LIMIT :limit
        """,
    )
    suspend fun getUpcoming(
        playlistId: Long,
        channelId: String,
        now: Long,
        limit: Int,
    ): List<EpgProgramEntity>

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
