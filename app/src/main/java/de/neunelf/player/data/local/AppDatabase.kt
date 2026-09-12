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
        ChannelOverrideEntity::class,
    ],
    // v2: channel_overrides (Sender ausblenden/sortieren) ergänzt. Da die
    // Datenbank per fallbackToDestructiveMigration läuft, verlieren
    // bestehende Installationen beim Update einmalig Favoriten/Verlauf –
    // dieselbe bewusste Abwägung wie beim ursprünglichen Schema.
    version = 2,
    // Kein Schema-Export: die Datenbank ist ein reiner Cache mit
    // fallbackToDestructiveMigration, es werden nie Migrationen von Hand
    // geschrieben. Der Export brachte hier nur einen Konflikt, weil die
    // KSP-Tasks für Debug und Release parallel in dasselbe Verzeichnis
    // schreiben ("Empty schema file").
    exportSchema = false,
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
