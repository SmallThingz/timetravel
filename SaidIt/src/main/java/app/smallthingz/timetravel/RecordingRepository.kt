package app.smallthingz.timetravel

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
                RecordingDatabase.getInstance(context).recordingDao().listAll()
            }
        }
    }

    suspend fun register(context: Context, recording: RecordingEntity): RecordingEntity {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                RecordingDatabase.getInstance(context).recordingDao().upsert(recording)
                recording
            }
        }
    }

    suspend fun delete(context: Context, recording: RecordingEntity): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val deleted = deleteRecordingAsset(context, recording)
                if (deleted) {
                    RecordingDatabase.getInstance(context).recordingDao().deleteById(recording.id)
                }
                deleted
            }
        }
    }

    suspend fun rename(
        context: Context,
        recording: RecordingEntity,
        requestedBaseName: String,
    ): RecordingEntity? {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val renamed = renameRecordingAsset(context, recording, requestedBaseName) ?: return@withLock null
                RecordingDatabase.getInstance(context).recordingDao().deleteById(recording.id)
                RecordingDatabase.getInstance(context).recordingDao().upsert(renamed)
                renamed
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

    suspend fun hasMovableRecordings(
        context: Context,
        targetDirectoryId: String = getConfiguredOutputDirectoryId(context),
    ): Boolean {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                syncConfiguredDirectory(context)
                pruneMissingLocked(context)
                RecordingDatabase.getInstance(context).recordingDao().listAll().any { it.directoryId != targetDirectoryId }
            }
        }
    }

    suspend fun moveAllToConfiguredDirectory(context: Context): MoveResult {
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val dao = RecordingDatabase.getInstance(context).recordingDao()
                val current = dao.listAll()
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
                    dao.deleteByIds(deletes)
                }
                if (updates.isNotEmpty()) {
                    dao.upsertAll(updates)
                }

                MoveResult(moved = moved, skipped = skipped, removedMissing = deletes.size - moved)
            }
        }
    }

    private suspend fun syncConfiguredDirectory(context: Context) {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val currentDirectoryId = getConfiguredOutputDirectoryId(context)
        val imported = listCurrentOutputDirectoryRecordings(context)
        val existing = dao.listByDirectory(currentDirectoryId)
        val importedIds = imported.mapTo(mutableSetOf()) { it.id }
        val staleIds = existing.asSequence()
            .map { it.id }
            .filter { it !in importedIds }
            .toList()

        if (imported.isNotEmpty()) {
            dao.upsertAll(imported)
        }
        if (staleIds.isNotEmpty()) {
            dao.deleteByIds(staleIds)
        }
    }

    private suspend fun pruneMissingLocked(context: Context): Int {
        val dao = RecordingDatabase.getInstance(context).recordingDao()
        val all = dao.listAll()
        val missingIds = all.asSequence()
            .filterNot { recordingExists(context, it) }
            .map { it.id }
            .toList()
        if (missingIds.isNotEmpty()) {
            dao.deleteByIds(missingIds)
        }
        return missingIds.size
    }

    data class MoveResult(
        val moved: Int = 0,
        val skipped: Int = 0,
        val removedMissing: Int = 0,
    )
}
