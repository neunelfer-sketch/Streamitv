package de.qwikster.player.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Stand einer Aufnahme.
 *
 * [RUNNING] überlebt bewusst einen Absturz oder einen Stromausfall in der
 * Datenbank: Beim nächsten Start weiß die App dadurch, dass eine Aufnahme
 * nicht sauber beendet wurde, und kann den Eintrag aufräumen, statt ihn für
 * immer als "läuft" anzuzeigen.
 */
enum class RecordingState { RUNNING, DONE, FAILED }

/**
 * Eine Aufnahme einer laufenden Sendung.
 *
 * Anders als der übrige Inhalt der Datenbank ist das **keine** wiederholbare
 * Kopie vom Panel, sondern eine Datei, die nur hier existiert. Ein
 * Playlist-Refresh darf sie deshalb nie anfassen – genauso wie Favoriten und
 * Verlauf.
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val streamId: String,
    val channelName: String,
    /** Sendungstitel aus dem EPG, sonst der Sendername. */
    val title: String,
    val filePath: String,
    val startedAt: Long,
    /** Geplantes Ende; 0 = läuft bis der Zuschauer stoppt. */
    val plannedEndAt: Long,
    val endedAt: Long = 0L,
    val state: String = RecordingState.RUNNING.name,
    val sizeBytes: Long = 0L,
    val errorMessage: String? = null,
)

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE state = :state")
    suspend fun getByState(state: String = RecordingState.RUNNING.name): List<RecordingEntity>

    @Insert
    suspend fun insert(recording: RecordingEntity): Long

    @Update
    suspend fun update(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Räumt Aufnahmen auf, die beim letzten Lauf nicht sauber beendet wurden.
     *
     * Ohne das stünde eine Aufnahme, die ein Absturz oder ein Stromausfall
     * unterbrochen hat, für immer als "läuft" in der Liste – mit einem
     * Stopp-Knopf, der nichts mehr stoppen kann. Die bereits geschriebene
     * Datei bleibt erhalten und ist abspielbar; ein Transportstrom hat kein
     * Dateiende, das fehlen könnte.
     */
    @Query("UPDATE recordings SET state = :done, endedAt = :now WHERE state = :running")
    suspend fun closeDangling(
        now: Long,
        running: String = RecordingState.RUNNING.name,
        done: String = RecordingState.DONE.name,
    )
}
