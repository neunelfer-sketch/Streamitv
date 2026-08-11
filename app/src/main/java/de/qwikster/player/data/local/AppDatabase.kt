package de.qwikster.player.data.local

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
    // Bei **jeder** Änderung an einer Entity hochzählen. Room legt einen
    // Fingerabdruck des Schemas in der Datei ab und vergleicht ihn beim
    // Öffnen. Bleibt die Nummer gleich, während sich das Schema ändert,
    // stürzt die App bei einem Update sofort beim Start ab
    // ("Room cannot verify the data integrity") – fallbackToDestructiveMigration
    // rettet das nicht, denn das greift nur bei einem Versionswechsel ohne
    // passende Migration. Genau der Fall hier: erst mit der neuen Nummer
    // wirft Room die alte Datei weg und legt sie neu an.
    //
    // 2: `directUrl` in `movies` (Film-URLs aus M3U-Playlists)
    // 3: Index `(playlistId, startAt)` auf `epg_programs` – trägt die
    //    Abfrage "was läuft gerade" bei sehr großen Playlists
    // 4: `directUrl` in `episodes` (Folgen-URLs aus M3U-Playlists)
    version = 4,
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
        const val NAME = "qwikster.db"
    }
}
