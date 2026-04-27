package app.timetravel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RecordingEntity(
    val id: String,
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

interface RecordingDao {
    suspend fun listAll(): List<RecordingEntity>

    suspend fun listByDirectory(directoryId: String): List<RecordingEntity>

    suspend fun upsert(recording: RecordingEntity)

    suspend fun upsertAll(recordings: List<RecordingEntity>)

    suspend fun deleteById(id: String)

    suspend fun deleteByIds(ids: List<String>)
}

class RecordingDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val dao = DaoImpl()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RECORDINGS (
                $COLUMN_ID TEXT PRIMARY KEY NOT NULL,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT NOT NULL,
                $COLUMN_STARTED_AT_MILLIS INTEGER NOT NULL,
                $COLUMN_DURATION_MILLIS INTEGER NOT NULL,
                $COLUMN_SIZE_BYTES INTEGER NOT NULL,
                $COLUMN_CODEC_SUMMARY TEXT NOT NULL,
                $COLUMN_STORAGE_TYPE TEXT NOT NULL,
                $COLUMN_DIRECTORY_ID TEXT NOT NULL,
                $COLUMN_CREATED_AT_MILLIS INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = Unit

    fun recordingDao(): RecordingDao = dao

    private inner class DaoImpl : RecordingDao {
        override suspend fun listAll(): List<RecordingEntity> {
            return readableDatabase.query(
                TABLE_RECORDINGS,
                null,
                null,
                null,
                null,
                null,
                "$COLUMN_STARTED_AT_MILLIS DESC, $COLUMN_CREATED_AT_MILLIS DESC",
            ).use(::readRecordings)
        }

        override suspend fun listByDirectory(directoryId: String): List<RecordingEntity> {
            return readableDatabase.query(
                TABLE_RECORDINGS,
                null,
                "$COLUMN_DIRECTORY_ID = ?",
                arrayOf(directoryId),
                null,
                null,
                "$COLUMN_STARTED_AT_MILLIS DESC, $COLUMN_CREATED_AT_MILLIS DESC",
            ).use(::readRecordings)
        }

        override suspend fun upsert(recording: RecordingEntity) {
            writableDatabase.insertWithOnConflict(
                TABLE_RECORDINGS,
                null,
                recording.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }

        override suspend fun upsertAll(recordings: List<RecordingEntity>) {
            if (recordings.isEmpty()) return
            writableDatabase.runInTransaction {
                recordings.forEach { recording ->
                    insertWithOnConflict(
                        TABLE_RECORDINGS,
                        null,
                        recording.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
            }
        }

        override suspend fun deleteById(id: String) {
            writableDatabase.delete(TABLE_RECORDINGS, "$COLUMN_ID = ?", arrayOf(id))
        }

        override suspend fun deleteByIds(ids: List<String>) {
            if (ids.isEmpty()) return
            val placeholders = ids.joinToString(",") { "?" }
            writableDatabase.delete(TABLE_RECORDINGS, "$COLUMN_ID IN ($placeholders)", ids.toTypedArray())
        }
    }

    companion object {
        private const val DATABASE_NAME = "timetravel-recordings.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RECORDINGS = "recordings"
        private const val COLUMN_ID = "id"
        private const val COLUMN_DISPLAY_NAME = "displayName"
        private const val COLUMN_MIME_TYPE = "mimeType"
        private const val COLUMN_STARTED_AT_MILLIS = "startedAtMillis"
        private const val COLUMN_DURATION_MILLIS = "durationMillis"
        private const val COLUMN_SIZE_BYTES = "sizeBytes"
        private const val COLUMN_CODEC_SUMMARY = "codecSummary"
        private const val COLUMN_STORAGE_TYPE = "storageType"
        private const val COLUMN_DIRECTORY_ID = "directoryId"
        private const val COLUMN_CREATED_AT_MILLIS = "createdAtMillis"

        @Volatile
        private var instance: RecordingDatabase? = null

        fun getInstance(context: Context): RecordingDatabase {
            return instance ?: synchronized(this) {
                instance ?: RecordingDatabase(context).also { instance = it }
            }
        }
    }
}

private fun RecordingEntity.toContentValues(): ContentValues {
    return ContentValues().apply {
        put("id", id)
        put("displayName", displayName)
        put("mimeType", mimeType)
        put("startedAtMillis", startedAtMillis)
        put("durationMillis", durationMillis)
        put("sizeBytes", sizeBytes)
        put("codecSummary", codecSummary)
        put("storageType", storageType)
        put("directoryId", directoryId)
        put("createdAtMillis", createdAtMillis)
    }
}

private fun SQLiteDatabase.runInTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}

private fun readRecordings(cursor: android.database.Cursor): List<RecordingEntity> {
    val idIndex = cursor.getColumnIndexOrThrow("id")
    val displayNameIndex = cursor.getColumnIndexOrThrow("displayName")
    val mimeTypeIndex = cursor.getColumnIndexOrThrow("mimeType")
    val startedAtMillisIndex = cursor.getColumnIndexOrThrow("startedAtMillis")
    val durationMillisIndex = cursor.getColumnIndexOrThrow("durationMillis")
    val sizeBytesIndex = cursor.getColumnIndexOrThrow("sizeBytes")
    val codecSummaryIndex = cursor.getColumnIndexOrThrow("codecSummary")
    val storageTypeIndex = cursor.getColumnIndexOrThrow("storageType")
    val directoryIdIndex = cursor.getColumnIndexOrThrow("directoryId")
    val createdAtMillisIndex = cursor.getColumnIndexOrThrow("createdAtMillis")
    return buildList {
        while (cursor.moveToNext()) {
            add(
                RecordingEntity(
                    id = cursor.getString(idIndex),
                    displayName = cursor.getString(displayNameIndex),
                    mimeType = cursor.getString(mimeTypeIndex),
                    startedAtMillis = cursor.getLong(startedAtMillisIndex),
                    durationMillis = cursor.getLong(durationMillisIndex),
                    sizeBytes = cursor.getLong(sizeBytesIndex),
                    codecSummary = cursor.getString(codecSummaryIndex),
                    storageType = cursor.getString(storageTypeIndex),
                    directoryId = cursor.getString(directoryIdIndex),
                    createdAtMillis = cursor.getLong(createdAtMillisIndex),
                ),
            )
        }
    }
}
