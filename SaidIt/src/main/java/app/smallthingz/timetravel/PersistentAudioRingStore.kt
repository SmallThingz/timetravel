package app.smallthingz.timetravel

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal class PersistentAudioRingStore(
    context: Context,
) : Closeable {
    private val directory = File(context.noBackupFilesDir, TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME).also { directory ->
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IllegalStateException("Unable to create buffer cache directory: ${directory.absolutePath}")
        }
    }
    private val metaFile = File(directory, "buffer.meta")
    private val dataFile = File(directory, "buffer.pcm")

    private var metaAccess: RandomAccessFile? = null
    private var metaChannel: FileChannel? = null
    private var metaMap: MappedByteBuffer? = null

    private var dataAccess: RandomAccessFile? = null
    private var dataChannel: FileChannel? = null
    private var dataMap: MappedByteBuffer? = null

    private var mappedCapacityBytes = -1
    private var mappedSampleRate = -1
    private var mappedChannelCount = -1
    private var lastForcedAtUptimeMillis = 0L

    data class RestoreSummary(
        val restoredBytes: Int,
        val lastWriteAtMillis: Long,
    )

    data class Snapshot(
        val capacityBytes: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val filledBytes: Int,
        val lastWriteAtMillis: Long,
    )

    @Synchronized
    fun peekSnapshot(): Snapshot? {
        ensureMetaMapped()
        val meta = metaMap ?: return null
        val capacityBytes = meta.readCapacityBytes()
        val sampleRate = meta.readSampleRate()
        val channelCount = meta.readChannelCount()
        val filledBytes = meta.readFilledBytes()
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0 || filledBytes <= 0 || filledBytes > capacityBytes) {
            return null
        }
        return Snapshot(
            capacityBytes = capacityBytes,
            sampleRate = sampleRate,
            channelCount = channelCount,
            filledBytes = filledBytes,
            lastWriteAtMillis = meta.readLastWriteAtMillis(),
        )
    }

    @Synchronized
    fun restoreInto(
        audioMemory: AudioMemory,
        capacityBytes: Long,
        sampleRate: Int,
        channelCount: Int,
    ): RestoreSummary {
        if (capacityBytes <= 0L || sampleRate <= 0 || channelCount <= 0) {
            clear()
            return RestoreSummary(0, 0L)
        }

        ensureMapped(capacityBytes.toInt(), sampleRate, channelCount)
        val meta = metaMap ?: return RestoreSummary(0, 0L)
        val data = dataMap ?: return RestoreSummary(0, 0L)
        val filledBytes = meta.readFilledBytes().coerceIn(0, mappedCapacityBytes)
        if (filledBytes <= 0) {
            return RestoreSummary(0, 0L)
        }

        val writePosition = meta.readWritePosition()
        val lastWriteAtMillis = meta.readLastWriteAtMillis()
        val startPosition = if (filledBytes >= mappedCapacityBytes) writePosition else 0
        val scratch = ByteArray(minOf(IO_CHUNK_SIZE, filledBytes))
        var restored = 0
        var remaining = filledBytes
        var readPosition = startPosition
        val dataView = data.duplicate()

        while (remaining > 0) {
            val chunkSize = minOf(remaining, scratch.size, mappedCapacityBytes - readPosition)
            dataView.position(readPosition)
            dataView.get(scratch, 0, chunkSize)
            audioMemory.write(scratch, 0, chunkSize)
            restored += chunkSize
            remaining -= chunkSize
            readPosition += chunkSize
            if (readPosition >= mappedCapacityBytes) {
                readPosition = 0
            }
        }

        return RestoreSummary(restored, lastWriteAtMillis)
    }

    @Synchronized
    fun read(
        skipBytes: Long,
        maxBytes: Long,
        reader: AudioMemory.Consumer,
    ) {
        val snapshot = peekSnapshot() ?: return
        if (maxBytes <= 0L) {
            return
        }
        ensureMapped(snapshot.capacityBytes, snapshot.sampleRate, snapshot.channelCount)
        val data = dataMap ?: return
        var remainingSkip = skipBytes.coerceAtLeast(0L)
        var remainingTake = minOf(maxBytes, snapshot.filledBytes.toLong() - remainingSkip).coerceAtLeast(0L)
        if (remainingTake <= 0L) {
            return
        }

        val scratch = ByteArray(minOf(IO_CHUNK_SIZE.toLong(), remainingTake).toInt())
        var readPosition = if (snapshot.filledBytes >= snapshot.capacityBytes) {
            metaMap?.readWritePosition() ?: 0
        } else {
            0
        }
        val dataView = data.duplicate()
        var unread = snapshot.filledBytes

        while (unread > 0 && remainingTake > 0L) {
            val chunkSize = minOf(unread, scratch.size, snapshot.capacityBytes - readPosition)
            dataView.position(readPosition)
            dataView.get(scratch, 0, chunkSize)

            if (remainingSkip < chunkSize) {
                val chunkOffset = remainingSkip.toInt()
                val take = minOf((chunkSize - chunkOffset).toLong(), remainingTake).toInt()
                reader.consume(scratch, chunkOffset, take)
                remainingTake -= take.toLong()
                remainingSkip = 0L
            } else {
                remainingSkip -= chunkSize.toLong()
            }

            unread -= chunkSize
            readPosition += chunkSize
            if (readPosition >= snapshot.capacityBytes) {
                readPosition = 0
            }
        }
    }

    @Synchronized
    fun append(
        array: ByteArray,
        offset: Int,
        count: Int,
        capacityBytes: Long,
        sampleRate: Int,
        channelCount: Int,
    ) {
        if (count <= 0 || capacityBytes <= 0L) return
        ensureMapped(capacityBytes.toInt(), sampleRate, channelCount)
        val meta = metaMap ?: return
        val data = dataMap ?: return

        var writePosition = meta.readWritePosition()
        var remaining = count
        var readOffset = offset
        val dataView = data.duplicate()

        while (remaining > 0) {
            val chunkSize = minOf(remaining, mappedCapacityBytes - writePosition)
            dataView.position(writePosition)
            dataView.put(array, readOffset, chunkSize)
            readOffset += chunkSize
            remaining -= chunkSize
            writePosition += chunkSize
            if (writePosition >= mappedCapacityBytes) {
                writePosition = 0
            }
        }

        val newFilled = minOf(mappedCapacityBytes, meta.readFilledBytes() + count)
        meta.writeWritePosition(writePosition)
        meta.writeFilledBytes(newFilled)
        meta.writeLastWriteAtMillis(System.currentTimeMillis())
        val now = SystemClock.uptimeMillis()
        if (now - lastForcedAtUptimeMillis >= FORCE_INTERVAL_MS) {
            force()
        }
    }

    @Synchronized
    fun replaceWith(
        audioMemory: AudioMemory,
        sampleRate: Int,
        channelCount: Int,
    ) {
        val capacityBytes = audioMemory.allocatedMemorySize
        if (capacityBytes <= 0L || sampleRate <= 0 || channelCount <= 0) {
            clear()
            return
        }

        ensureMapped(capacityBytes.toInt(), sampleRate, channelCount, keepExisting = false)
        val meta = metaMap ?: return
        meta.writeWritePosition(0)
        meta.writeFilledBytes(0)
        meta.writeLastWriteAtMillis(0L)

        audioMemory.read(0) { array, offset, count ->
            append(array, offset, count, capacityBytes, sampleRate, channelCount)
            count
        }
        force()
    }

    @Synchronized
    fun hasData(): Boolean = peekSnapshot() != null

    @Synchronized
    fun countFilledBytes(): Long = peekSnapshot()?.filledBytes?.toLong() ?: 0L

    @Synchronized
    fun configuredCapacityBytes(): Long = peekSnapshot()?.capacityBytes?.toLong() ?: 0L

    @Synchronized
    fun checkpoint() {
        force()
    }

    @Synchronized
    fun clear() {
        ensureMetaMapped()
        val meta = metaMap
        if (meta != null) {
            meta.writeWritePosition(0)
            meta.writeFilledBytes(0)
            meta.writeLastWriteAtMillis(0L)
            force()
        }
    }

    @Synchronized
    override fun close() {
        force()
        metaMap = null
        dataMap = null
        metaChannel?.close()
        dataChannel?.close()
        metaAccess?.close()
        dataAccess?.close()
        metaChannel = null
        dataChannel = null
        metaAccess = null
        dataAccess = null
        mappedCapacityBytes = -1
        mappedSampleRate = -1
        mappedChannelCount = -1
    }

    @Synchronized
    private fun ensureMapped(
        capacityBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        keepExisting: Boolean = true,
    ) {
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0) {
            clear()
            return
        }

        ensureMetaMapped()

        val meta = metaMap ?: return
        val needsRemap =
            dataMap == null ||
                mappedCapacityBytes != capacityBytes ||
                mappedSampleRate != sampleRate ||
                mappedChannelCount != channelCount ||
                meta.readCapacityBytes() != capacityBytes ||
                meta.readSampleRate() != sampleRate ||
                meta.readChannelCount() != channelCount

        if (!needsRemap) {
            return
        }

        if (!keepExisting) {
            remap(capacityBytes, sampleRate, channelCount, clearContents = true)
            return
        }

        val sameFormat = meta.readSampleRate() == sampleRate && meta.readChannelCount() == channelCount
        val sameCapacity = meta.readCapacityBytes() == capacityBytes
        if (sameFormat && sameCapacity) {
            remap(capacityBytes, sampleRate, channelCount, clearContents = false)
            return
        }

        remap(capacityBytes, sampleRate, channelCount, clearContents = true)
    }

    private fun remap(
        capacityBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        clearContents: Boolean,
    ) {
        dataMap = null
        dataChannel?.close()
        dataAccess?.close()
        dataChannel = null
        dataAccess = null

        dataAccess = RandomAccessFile(dataFile, "rwd").also { it.setLength(capacityBytes.toLong()) }
        dataChannel = requireNotNull(dataAccess).channel
        dataMap = requireNotNull(dataChannel).map(FileChannel.MapMode.READ_WRITE, 0, capacityBytes.toLong())

        mappedCapacityBytes = capacityBytes
        mappedSampleRate = sampleRate
        mappedChannelCount = channelCount

        val meta = requireNotNull(metaMap)
        meta.writeMagic()
        meta.writeVersion()
        meta.writeCapacityBytes(capacityBytes)
        meta.writeSampleRate(sampleRate)
        meta.writeChannelCount(channelCount)
        if (clearContents) {
            meta.writeWritePosition(0)
            meta.writeFilledBytes(0)
            meta.writeLastWriteAtMillis(0L)
        }
        force()
    }

    private fun ensureMetaMapped() {
        if (metaMap != null) return
        metaAccess = RandomAccessFile(metaFile, "rwd").also { it.setLength(META_FILE_BYTES.toLong()) }
        metaChannel = requireNotNull(metaAccess).channel
        metaMap = requireNotNull(metaChannel).map(FileChannel.MapMode.READ_WRITE, 0, META_FILE_BYTES.toLong())
        if (metaMap?.readMagic() != MAGIC || metaMap?.readVersion() != VERSION) {
            metaMap?.writeMagic()
            metaMap?.writeVersion()
            metaMap?.writeCapacityBytes(0)
            metaMap?.writeSampleRate(0)
            metaMap?.writeChannelCount(0)
            metaMap?.writeWritePosition(0)
            metaMap?.writeFilledBytes(0)
            metaMap?.writeLastWriteAtMillis(0L)
            force()
        }
    }

    private fun force() {
        metaMap?.force()
        dataMap?.force()
        lastForcedAtUptimeMillis = SystemClock.uptimeMillis()
    }

    private fun MappedByteBuffer.readMagic(): Int = getInt(OFFSET_MAGIC)
    private fun MappedByteBuffer.writeMagic() {
        putInt(OFFSET_MAGIC, MAGIC)
    }

    private fun MappedByteBuffer.readVersion(): Int = getInt(OFFSET_VERSION)
    private fun MappedByteBuffer.writeVersion() {
        putInt(OFFSET_VERSION, VERSION)
    }

    private fun MappedByteBuffer.readCapacityBytes(): Int = getInt(OFFSET_CAPACITY_BYTES)
    private fun MappedByteBuffer.writeCapacityBytes(value: Int) {
        putInt(OFFSET_CAPACITY_BYTES, value)
    }

    private fun MappedByteBuffer.readSampleRate(): Int = getInt(OFFSET_SAMPLE_RATE)
    private fun MappedByteBuffer.writeSampleRate(value: Int) {
        putInt(OFFSET_SAMPLE_RATE, value)
    }

    private fun MappedByteBuffer.readChannelCount(): Int = getInt(OFFSET_CHANNEL_COUNT)
    private fun MappedByteBuffer.writeChannelCount(value: Int) {
        putInt(OFFSET_CHANNEL_COUNT, value)
    }

    private fun MappedByteBuffer.readWritePosition(): Int = getInt(OFFSET_WRITE_POSITION)
    private fun MappedByteBuffer.writeWritePosition(value: Int) {
        putInt(OFFSET_WRITE_POSITION, value)
    }

    private fun MappedByteBuffer.readFilledBytes(): Int = getInt(OFFSET_FILLED_BYTES)
    private fun MappedByteBuffer.writeFilledBytes(value: Int) {
        putInt(OFFSET_FILLED_BYTES, value)
    }

    private fun MappedByteBuffer.readLastWriteAtMillis(): Long = getLong(OFFSET_LAST_WRITE_AT_MILLIS)
    private fun MappedByteBuffer.writeLastWriteAtMillis(value: Long) {
        putLong(OFFSET_LAST_WRITE_AT_MILLIS, value)
    }

    private companion object {
        // Fixed mmap metadata header shared by the tiny meta file and the large PCM ring file.
        // Values stay intentionally primitive/offset-based so restore can recover after process death
        // without needing any schema object allocation or parsing.
        const val META_FILE_BYTES = 512
        const val IO_CHUNK_SIZE = 64 * 1024
        // This cache is best-effort resilience, not the canonical live history store.
        // Batching force calls cuts background CPU and I/O substantially.
        const val FORCE_INTERVAL_MS = 2_000L

        const val MAGIC = 0x54544246
        const val VERSION = 1

        const val OFFSET_MAGIC = 0
        const val OFFSET_VERSION = 4
        const val OFFSET_CAPACITY_BYTES = 8
        const val OFFSET_SAMPLE_RATE = 12
        const val OFFSET_CHANNEL_COUNT = 16
        const val OFFSET_WRITE_POSITION = 20
        const val OFFSET_FILLED_BYTES = 24
        const val OFFSET_LAST_WRITE_AT_MILLIS = 32
    }
}
