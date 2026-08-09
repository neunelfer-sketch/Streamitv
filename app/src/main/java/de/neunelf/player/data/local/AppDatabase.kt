package de.neunelf.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Lokaler Cache der App.
 *
 * Alles hier ist reproduzierbar (kann jederzeit neu vom Panel geladen werden)
 * – mit **einer Ausnahme**: `favorites` und `recents` sind Nutzerdaten und
 * dürfen bei einem Playlist-Refresh nicht angefasst werden.
 */
@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        RecentEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun vodDao(): VodDao
    abstract fun epgDao(): EpgDao
    abstract fun userDataDao(): UserDataDao

    companion object {
        const val NAME = "neunelf_player.db"
    }
}
