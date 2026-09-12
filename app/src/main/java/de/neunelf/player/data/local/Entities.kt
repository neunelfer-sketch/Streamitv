package de.neunelf.player.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.neunelf.player.data.model.Category
import de.neunelf.player.data.model.Channel
import de.neunelf.player.data.model.EpgProgram
import de.neunelf.player.data.model.Episode
import de.neunelf.player.data.model.Movie
import de.neunelf.player.data.model.Playlist
import de.neunelf.player.data.model.PlaylistType
import de.neunelf.player.data.model.Series
import de.neunelf.player.data.model.StreamKind

/**
 * Room-Entities und ihre Umwandlung in die Domänenmodelle.
 *
 * Wichtige Designentscheidung: **Favoriten und Verlauf liegen in eigenen
 * Tabellen**, nicht als Spalte in `channels`. Beim Aktualisieren einer
 * Playlist wird die Sendertabelle komplett ersetzt – lägen die Favoriten
 * dort, wären sie nach jedem Refresh weg.
 */

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val m3uUrl: String,
    val epgUrl: String,
    val lastSyncAt: Long,
    val lastEpgSyncAt: Long,
    /** Genau eine Playlist ist aktiv; sie bestimmt, was die UI anzeigt. */
    val isActive: Boolean = false,
) {
    fun toModel() = Playlist(
        id = id,
        name = name,
        type = runCatching { PlaylistType.valueOf(type) }.getOrDefault(PlaylistType.M3U),
        serverUrl = serverUrl,
        username = username,
        password = password,
        m3uUrl = m3uUrl,
        epgUrl = epgUrl,
        lastSyncAt = lastSyncAt,
        lastEpgSyncAt = lastEpgSyncAt,
    )
}

fun Playlist.toEntity(isActive: Boolean = true) = PlaylistEntity(
    id = id,
    name = name,
    type = type.name,
    serverUrl = serverUrl,
    username = username,
    password = password,
    m3uUrl = m3uUrl,
    epgUrl = epgUrl,
    lastSyncAt = lastSyncAt,
    lastEpgSyncAt = lastEpgSyncAt,
    isActive = isActive,
)

@Entity(
    tableName = "categories",
    primaryKeys = ["playlistId", "categoryId", "kind"],
    indices = [Index("playlistId", "kind")],
)
data class CategoryEntity(
    val playlistId: Long,
    val categoryId: String,
    val kind: String,
    val name: String,
    val sortOrder: Int,
) {
    fun toModel(channelCount: Int = 0) = Category(
        id = categoryId,
        name = name,
        kind = runCatching { StreamKind.valueOf(kind) }.getOrDefault(StreamKind.LIVE),
        playlistId = playlistId,
        sortOrder = sortOrder,
        channelCount = channelCount,
    )
}

fun Category.toEntity() = CategoryEntity(
    playlistId = playlistId,
    categoryId = id,
    kind = kind.name,
    name = name,
    sortOrder = sortOrder,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["playlistId", "streamId"],
    indices = [
        Index("playlistId", "categoryId"),
        Index("epgChannelId"),
        // Für die Suche über alle Sender.
        Index("playlistId", "name"),
    ],
)
data class ChannelEntity(
    val playlistId: Long,
    val streamId: String,
    val name: String,
    val logoUrl: String?,
    val categoryId: String?,
    val epgChannelId: String?,
    val number: Int,
    val directUrl: String?,
    val containerExtension: String,
    val archiveDays: Int,
) {
    fun toModel(isFavorite: Boolean = false, lastWatchedAt: Long = 0L) = Channel(
        streamId = streamId,
        playlistId = playlistId,
        name = name,
        logoUrl = logoUrl,
        categoryId = categoryId,
        epgChannelId = epgChannelId,
        number = number,
        directUrl = directUrl,
        containerExtension = containerExtension,
        isFavorite = isFavorite,
        lastWatchedAt = lastWatchedAt,
        archiveDays = archiveDays,
    )
}

fun Channel.toEntity() = ChannelEntity(
    playlistId = playlistId,
    streamId = streamId,
    name = name,
    logoUrl = logoUrl,
    categoryId = categoryId,
    epgChannelId = epgChannelId,
    number = number,
    directUrl = directUrl,
    containerExtension = containerExtension,
    archiveDays = archiveDays,
)

/**
 * Projektion für Listenabfragen: Sender + abgeleitete Flags aus den
 * Favoriten-/Verlaufstabellen. Room füllt die Zusatzspalten aus dem JOIN.
 */
data class ChannelRow(
    @ColumnInfo(name = "playlistId") val playlistId: Long,
    @ColumnInfo(name = "streamId") val streamId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "logoUrl") val logoUrl: String?,
    @ColumnInfo(name = "categoryId") val categoryId: String?,
    @ColumnInfo(name = "epgChannelId") val epgChannelId: String?,
    @ColumnInfo(name = "number") val number: Int,
    @ColumnInfo(name = "directUrl") val directUrl: String?,
    @ColumnInfo(name = "containerExtension") val containerExtension: String,
    @ColumnInfo(name = "archiveDays") val archiveDays: Int,
    @ColumnInfo(name = "isFavorite") val isFavorite: Boolean,
    @ColumnInfo(name = "lastWatchedAt") val lastWatchedAt: Long,
) {
    fun toModel() = Channel(
        streamId = streamId,
        playlistId = playlistId,
        name = name,
        logoUrl = logoUrl,
        categoryId = categoryId,
        epgChannelId = epgChannelId,
        number = number,
        directUrl = directUrl,
        containerExtension = containerExtension,
        isFavorite = isFavorite,
        lastWatchedAt = lastWatchedAt,
        archiveDays = archiveDays,
    )
}

@Entity(
    tableName = "movies",
    primaryKeys = ["playlistId", "streamId"],
    indices = [Index("playlistId", "categoryId")],
)
data class MovieEntity(
    val playlistId: Long,
    val streamId: String,
    val name: String,
    val posterUrl: String?,
    val categoryId: String?,
    val containerExtension: String,
    val rating: Double,
    val year: String?,
    val plot: String?,
    val durationSecs: Int,
    val addedAt: Long,
) {
    fun toModel() = Movie(
        streamId = streamId,
        playlistId = playlistId,
        name = name,
        posterUrl = posterUrl,
        categoryId = categoryId,
        containerExtension = containerExtension,
        rating = rating,
        year = year,
        plot = plot,
        durationSecs = durationSecs,
        addedAt = addedAt,
    )
}

fun Movie.toEntity() = MovieEntity(
    playlistId = playlistId,
    streamId = streamId,
    name = name,
    posterUrl = posterUrl,
    categoryId = categoryId,
    containerExtension = containerExtension,
    rating = rating,
    year = year,
    plot = plot,
    durationSecs = durationSecs,
    addedAt = addedAt,
)

@Entity(
    tableName = "series",
    primaryKeys = ["playlistId", "seriesId"],
    indices = [Index("playlistId", "categoryId")],
)
data class SeriesEntity(
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val posterUrl: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: Double,
    val year: String?,
    val lastModified: Long,
) {
    fun toModel() = Series(
        seriesId = seriesId,
        playlistId = playlistId,
        name = name,
        posterUrl = posterUrl,
        categoryId = categoryId,
        plot = plot,
        rating = rating,
        year = year,
        lastModified = lastModified,
    )
}

fun Series.toEntity() = SeriesEntity(
    playlistId = playlistId,
    seriesId = seriesId,
    name = name,
    posterUrl = posterUrl,
    categoryId = categoryId,
    plot = plot,
    rating = rating,
    year = year,
    lastModified = lastModified,
)

@Entity(
    tableName = "episodes",
    primaryKeys = ["playlistId", "episodeId"],
    indices = [Index("playlistId", "seriesId")],
)
data class EpisodeEntity(
    val playlistId: Long,
    val episodeId: String,
    val seriesId: String,
    val season: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String,
    val plot: String?,
    val durationSecs: Int,
    val thumbnailUrl: String?,
) {
    fun toModel() = Episode(
        episodeId = episodeId,
        seriesId = seriesId,
        season = season,
        episodeNumber = episodeNumber,
        title = title,
        containerExtension = containerExtension,
        plot = plot,
        durationSecs = durationSecs,
        thumbnailUrl = thumbnailUrl,
    )
}

fun Episode.toEntity(playlistId: Long) = EpisodeEntity(
    playlistId = playlistId,
    episodeId = episodeId,
    seriesId = seriesId,
    season = season,
    episodeNumber = episodeNumber,
    title = title,
    containerExtension = containerExtension,
    plot = plot,
    durationSecs = durationSecs,
    thumbnailUrl = thumbnailUrl,
)

/**
 * EPG-Sendungen.
 *
 * Der Index auf `(epgChannelId, startAt)` ist der wichtigste der ganzen
 * Datenbank: das EPG-Raster fragt bei jedem Scroll ein Zeitfenster für
 * ~20 sichtbare Sender ab.
 */
@Entity(
    tableName = "epg_programs",
    indices = [
        // Trägt die Rasterabfrage: "alle Sendungen dieser Sender in diesem
        // Zeitfenster". Führende Spalte ist die EPG-ID, weil danach gefiltert
        // wird – der Zeitraum grenzt anschließend nur noch ein.
        Index("epgChannelId", "startAt"),
        Index("endAt"),
        // Verhindert Doppeleinträge, wenn zusätzlich zum XMLTV-Import noch
        // das Kurz-EPG eines Senders nachgeladen wird. Zusammen mit
        // `OnConflictStrategy.IGNORE` wirkt das als Deduplizierung.
        // `playlistId` gehört dazu, damit zwei Playlists mit identischen
        // EPG-IDs (z. B. beide "rtl.de") sich nicht gegenseitig verdrängen.
        Index("playlistId", "epgChannelId", "startAt", unique = true),
    ],
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    val epgChannelId: String,
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val description: String?,
    val category: String?,
    val iconUrl: String?,
    val season: Int?,
    val episode: Int?,
) {
    fun toModel() = EpgProgram(
        id = id,
        epgChannelId = epgChannelId,
        startAt = startAt,
        endAt = endAt,
        title = title,
        description = description,
        category = category,
        iconUrl = iconUrl,
        season = season,
        episode = episode,
    )
}

fun EpgProgram.toEntity(playlistId: Long) = EpgProgramEntity(
    id = 0L,
    playlistId = playlistId,
    epgChannelId = epgChannelId,
    startAt = startAt,
    endAt = endAt,
    title = title,
    description = description,
    category = category,
    iconUrl = iconUrl,
    season = season,
    episode = episode,
)

/**
 * Nutzer-Anpassung eines Senders: ausgeblendet und/oder eigene Reihenfolge.
 *
 * Eigene Tabelle aus demselben Grund wie Favoriten/Verlauf: die Sendertabelle
 * wird bei jedem Playlist-Refresh komplett ersetzt, diese Anpassungen sollen
 * das überleben.
 */
@Entity(tableName = "channel_overrides", primaryKeys = ["playlistId", "streamId"])
data class ChannelOverrideEntity(
    val playlistId: Long,
    val streamId: String,
    val isHidden: Boolean = false,
    /** `null` = Panel-Reihenfolge (`channels.number`) gilt weiterhin. */
    val customOrder: Int? = null,
)

/** Favorit – überlebt bewusst das Neuladen der Playlist. */
@Entity(tableName = "favorites", primaryKeys = ["playlistId", "streamId", "kind"])
data class FavoriteEntity(
    val playlistId: Long,
    val streamId: String,
    val kind: String,
    val addedAt: Long,
    /** Eigene Sortierung der Favoritenliste (Drag & Drop in den Einstellungen). */
    val sortOrder: Int = 0,
)

/** "Zuletzt gesehen" inkl. Wiedergabeposition für VOD-Fortsetzen. */
@Entity(tableName = "recents", primaryKeys = ["playlistId", "streamId", "kind"])
data class RecentEntity(
    val playlistId: Long,
    val streamId: String,
    val kind: String,
    val watchedAt: Long,
    /** Nur für VOD/Serien relevant; bei Live immer 0. */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)
