package app.timetravel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RecordingRepository {
    private val mutex = Mutex()

    suspend fun refresh(context: Context): List<RecordingEntity> {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                syncConfiguredDirectory(context)
                pruneMissingLocked(context)
                dao(context).listAll()
            }
        }
    }

    suspend fun register(context: Context, recording: RecordingEntity): RecordingEntity {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                dao(context).upsert(recording)
                recording
            }
        }
    }

    suspend fun delete(context: Context, recording: RecordingEntity): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val deleted = deleteRecordingAsset(context, recording)
                if (deleted) {
                    dao(context).deleteById(recording.id)
                }
                deleted
            }
        }
    }

    suspend fun pruneMissing(context: Context): Int {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                pruneMissingLocked(context)
            }
        }
    }

    suspend fun moveAllToConfiguredDirectory(context: Context): MoveResult {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = dao(context).listAll()
                if (current.isEmpty()) {
                    return@withLock MoveResult()
                }

                val targetDirectoryId = getConfiguredOutputDirectoryId(context)
                val updates = mutableListOf<RecordingEntity>()
                val deletes = mutableListOf<String>()
                var moved = 0
                var skipped = 0

                current.forEach { recording ->
                    if (!recordingExists(context, recording)) {
                        deletes += recording.id
                        return@forEach
                    }

                    if (recording.directoryId == targetDirectoryId) {
                        skipped++
                        return@forEach
                    }

                    val movedRecording = copyRecordingToConfiguredDirectory(context, recording)
                    if (movedRecording != null) {
                        deleteRecordingAsset(context, recording)
                        updates += movedRecording
                        deletes += recording.id
                        moved++
                    }
                }

                if (deletes.isNotEmpty()) {
                    dao(context).deleteByIds(deletes)
                }
                if (updates.isNotEmpty()) {
                    dao(context).upsertAll(updates)
                }

                MoveResult(moved = moved, skipped = skipped, removedMissing = deletes.size - moved)
            }
        }
    }

    private suspend fun syncConfiguredDirectory(context: Context) {
        val currentDirectoryId = getConfiguredOutputDirectoryId(context)
        val imported = listCurrentOutputDirectoryRecordings(context)
        val existing = dao(context).listByDirectory(currentDirectoryId)
        val importedIds = imported.mapTo(mutableSetOf()) { it.id }
        val staleIds = existing.asSequence()
            .map { it.id }
            .filter { it !in importedIds }
            .toList()

        if (imported.isNotEmpty()) {
            dao(context).upsertAll(imported)
        }
        if (staleIds.isNotEmpty()) {
            dao(context).deleteByIds(staleIds)
        }
    }

    private suspend fun pruneMissingLocked(context: Context): Int {
        val all = dao(context).listAll()
        val missingIds = all.asSequence()
            .filterNot { recordingExists(context, it) }
            .map { it.id }
            .toList()
        if (missingIds.isNotEmpty()) {
            dao(context).deleteByIds(missingIds)
        }
        return missingIds.size
    }

    private fun dao(context: Context): RecordingDao {
        return RecordingDatabase.getInstance(context).recordingDao()
    }

    data class MoveResult(
        val moved: Int = 0,
        val skipped: Int = 0,
        val removedMissing: Int = 0,
    )
}
