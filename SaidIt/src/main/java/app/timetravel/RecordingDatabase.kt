package app.timetravel

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val mimeType: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val sizeBytes: Long,
    val codecSummary: String,
    val storageType: String,
    val directoryId: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY startedAtMillis DESC, createdAtMillis DESC")
    suspend fun listAll(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE directoryId = :directoryId")
    suspend fun listByDirectory(directoryId: String): List<RecordingEntity>

    @Upsert
    suspend fun upsert(recording: RecordingEntity)

    @Upsert
    suspend fun upsertAll(recordings: List<RecordingEntity>)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Database(
    entities = [RecordingEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RecordingDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var instance: RecordingDatabase? = null

        fun getInstance(context: Context): RecordingDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RecordingDatabase::class.java,
                    "timetravel-recordings.db",
                ).build().also { instance = it }
            }
        }
    }
}
