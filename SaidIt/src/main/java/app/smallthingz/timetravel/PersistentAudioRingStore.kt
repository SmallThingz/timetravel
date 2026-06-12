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
    private val metaFile = File(directory, TimeTravelConfig.BUFFER_META_FILE_NAME)
    private val dataFile = File(directory, TimeTravelConfig.BUFFER_PCM_FILE_NAME)

    private var metaAccess: RandomAccessFile? = null
    private var metaChannel: FileChannel? = null
    private var metaMap: MappedByteBuffer? = null

    private var dataAccess: RandomAccessFile? = null
    private var dataChannel: FileChannel? = null
    private var dataMap: MappedByteBuffer? = null

    private var mappedCapacityBytes = -1
    private var mappedSampleRate = -1
    private var mappedChannelCount = -1
    private var mappedBytesPerSample = -1
    private var lastForcedAtUptimeMillis = 0L
    private val ioScratch = ByteArray(IO_CHUNK_SIZE)

    data class RestoreSummary(
        val restoredBytes: Int,
        val lastWriteAtMillis: Long,
    )

    data class Snapshot(
        val capacityBytes: Int,
        val sampleRate: Int,
        val channelCount: Int,
        val bytesPerSample: Int,
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
        val bytesPerSample = meta.readBytesPerSample().takeIf { it > 0 } ?: 2
        val filledBytes = meta.readFilledBytes()
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0 || filledBytes <= 0 || filledBytes > capacityBytes) {
            return null
        }
        return Snapshot(
            capacityBytes = capacityBytes,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bytesPerSample = bytesPerSample,
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
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ): RestoreSummary {
        if (capacityBytes <= 0L || sampleRate <= 0 || channelCount <= 0) {
            clear()
            return RestoreSummary(0, 0L)
        }

        ensureMapped(capacityBytes.toInt(), sampleRate, channelCount, sampleFormat.bytesPerSample)
        val meta = metaMap ?: return RestoreSummary(0, 0L)
        val filledBytes = meta.readFilledBytes().coerceIn(0, mappedCapacityBytes)
        if (filledBytes <= 0) {
            return RestoreSummary(0, 0L)
        }

        val writePosition = meta.readWritePosition()
        val lastWriteAtMillis = meta.readLastWriteAtMillis()
        val startPosition = ringStartPosition(writePosition, filledBytes, mappedCapacityBytes)
        val scratch = ioScratch
        var restored = 0
        var remaining = filledBytes
        var readPosition = startPosition
        val dv = dataMap ?: return RestoreSummary(0, 0L)

        while (remaining > 0) {
            val chunkSize = minOf(remaining, scratch.size, mappedCapacityBytes - readPosition)
            dv.position(readPosition)
            dv.get(scratch, 0, chunkSize)
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
        ensureMetaMapped()
        val meta = metaMap ?: return
        if (maxBytes <= 0L) {
            return
        }
        val capacityBytes = meta.readCapacityBytes()
        val sampleRate = meta.readSampleRate()
        val channelCount = meta.readChannelCount()
        val filledBytes = meta.readFilledBytes()
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0 || filledBytes <= 0 || filledBytes > capacityBytes) {
            return
        }
        val bytesPerSample = meta.readBytesPerSample().takeIf { it > 0 } ?: 2
        ensureMapped(capacityBytes, sampleRate, channelCount, bytesPerSample)
        val dv = dataMap ?: return
        var remainingSkip = skipBytes.coerceAtLeast(0L)
        var remainingTake = minOf(maxBytes, filledBytes.toLong() - remainingSkip).coerceAtLeast(0L)
        if (remainingTake <= 0L) {
            return
        }

        var readPosition = ringStartPosition(meta.readWritePosition(), filledBytes, capacityBytes)
        var unread = filledBytes

        while (unread > 0 && remainingTake > 0L) {
            val chunkSize = minOf(unread, ioScratch.size, capacityBytes - readPosition)
            dv.position(readPosition)
            dv.get(ioScratch, 0, chunkSize)

            if (remainingSkip < chunkSize) {
                val chunkOffset = remainingSkip.toInt()
                val take = minOf((chunkSize - chunkOffset).toLong(), remainingTake).toInt()
                reader.consume(ioScratch, chunkOffset, take)
                remainingTake -= take.toLong()
                remainingSkip = 0L
            } else {
                remainingSkip -= chunkSize.toLong()
            }

            unread -= chunkSize
            readPosition += chunkSize
            if (readPosition >= capacityBytes) {
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
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ) {
        if (count <= 0 || capacityBytes <= 0L) return
        ensureMapped(capacityBytes.toInt(), sampleRate, channelCount, sampleFormat.bytesPerSample)
        val meta = metaMap ?: return
        val dv = dataMap ?: return
        var writePosition = meta.readWritePosition()
        var remaining = count
        var readOffset = offset

        while (remaining > 0) {
            val chunkSize = minOf(remaining, mappedCapacityBytes - writePosition)
            dv.position(writePosition)
            dv.put(array, readOffset, chunkSize)
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
        sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    ) {
        val capacityBytes = audioMemory.allocatedMemorySize
        if (capacityBytes <= 0L || sampleRate <= 0 || channelCount <= 0) {
            clear()
            return
        }

        ensureMapped(capacityBytes.toInt(), sampleRate, channelCount, sampleFormat.bytesPerSample, keepExisting = false)
        val meta = metaMap ?: return
        meta.writeWritePosition(0)
        meta.writeFilledBytes(0)
        meta.writeLastWriteAtMillis(0L)

        audioMemory.read(0) { array, offset, count ->
            append(array, offset, count, capacityBytes, sampleRate, channelCount, sampleFormat)
            count
        }
        force()
    }

    @Synchronized
    fun hasData(): Boolean {
        ensureMetaMapped()
        val meta = metaMap ?: return false
        val capacityBytes = meta.readCapacityBytes()
        val sampleRate = meta.readSampleRate()
        val channelCount = meta.readChannelCount()
        val filledBytes = meta.readFilledBytes()
        return capacityBytes > 0 && sampleRate > 0 && channelCount > 0 && filledBytes > 0 && filledBytes <= capacityBytes
    }

    @Synchronized
    fun countFilledBytes(): Long {
        ensureMetaMapped()
        val meta = metaMap ?: return 0L
        val capacityBytes = meta.readCapacityBytes()
        val sampleRate = meta.readSampleRate()
        val channelCount = meta.readChannelCount()
        val filledBytes = meta.readFilledBytes()
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0 || filledBytes <= 0 || filledBytes > capacityBytes) {
            return 0L
        }
        return filledBytes.toLong()
    }

    @Synchronized
    fun configuredCapacityBytes(): Long {
        ensureMetaMapped()
        val meta = metaMap ?: return 0L
        val capacityBytes = meta.readCapacityBytes()
        val sampleRate = meta.readSampleRate()
        val channelCount = meta.readChannelCount()
        val filledBytes = meta.readFilledBytes()
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0 || filledBytes <= 0 || filledBytes > capacityBytes) {
            return 0L
        }
        return capacityBytes.toLong()
    }

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
        runCatching { metaChannel?.close() }
        runCatching { dataChannel?.close() }
        runCatching { metaAccess?.close() }
        runCatching { dataAccess?.close() }
        metaChannel = null
        dataChannel = null
        metaAccess = null
        dataAccess = null
        mappedCapacityBytes = -1
        mappedSampleRate = -1
        mappedChannelCount = -1
        mappedBytesPerSample = -1
    }

    @Synchronized
    private fun ensureMapped(
        capacityBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
        keepExisting: Boolean = true,
    ) {
        if (capacityBytes <= 0 || sampleRate <= 0 || channelCount <= 0 || bytesPerSample <= 0) {
            clear()
            return
        }

        ensureMetaMapped()
        val alignedCapacityBytes = alignToPageSize(capacityBytes)

        val meta = metaMap ?: return
        val needsRemap =
            dataMap == null ||
                mappedCapacityBytes != alignedCapacityBytes ||
                mappedSampleRate != sampleRate ||
                mappedChannelCount != channelCount ||
                mappedBytesPerSample != bytesPerSample ||
                meta.readCapacityBytes() != alignedCapacityBytes ||
                meta.readSampleRate() != sampleRate ||
                meta.readChannelCount() != channelCount ||
                meta.readBytesPerSample() != bytesPerSample

        if (!needsRemap) {
            return
        }

        if (!keepExisting) {
            remap(alignedCapacityBytes, sampleRate, channelCount, bytesPerSample, clearContents = true)
            return
        }

        val sameFormat = meta.readSampleRate() == sampleRate && meta.readChannelCount() == channelCount && meta.readBytesPerSample() == bytesPerSample
        val sameCapacity = meta.readCapacityBytes() == alignedCapacityBytes
        if (sameFormat && sameCapacity) {
            remap(alignedCapacityBytes, sampleRate, channelCount, bytesPerSample, clearContents = false)
            return
        }

        resizePreservingNewest(alignedCapacityBytes, sampleRate, channelCount, bytesPerSample, sameFormat)
    }

    private fun resizePreservingNewest(
        capacityBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
        sameFormat: Boolean,
    ) {
        val existingMeta = metaMap
        val oldCapacityBytes = mappedCapacityBytes
        val oldFilledBytes = existingMeta?.readFilledBytes()?.coerceIn(0, oldCapacityBytes) ?: 0
        val oldWritePosition = existingMeta?.readWritePosition()?.coerceIn(0, oldCapacityBytes) ?: 0

        if (sameFormat && capacityBytes > oldCapacityBytes && canGrowWithoutCopy(oldCapacityBytes, oldFilledBytes, oldWritePosition)) {
            remap(capacityBytes, sampleRate, channelCount, bytesPerSample, clearContents = false)
            if (oldFilledBytes == oldCapacityBytes && oldWritePosition == 0) {
                metaMap?.writeWritePosition(oldCapacityBytes)
            }
            return
        }

        if (sameFormat && capacityBytes > oldCapacityBytes && oldFilledBytes == oldCapacityBytes && oldWritePosition > 0) {
            growWrappedRingWithSmallestCopy(capacityBytes, sampleRate, channelCount, bytesPerSample, oldCapacityBytes, oldWritePosition)
            return
        }

        val saved = if (sameFormat) readNewestBytes(minOf(metaMap?.readFilledBytes() ?: 0, capacityBytes)) else ByteArray(0)
        remap(capacityBytes, sampleRate, channelCount, bytesPerSample, clearContents = true)
        if (saved.isEmpty()) return

        val meta = metaMap ?: return
        val dv = dataMap ?: return
        var remaining = saved.size
        var readOffset = 0
        var writePosition = 0
        while (remaining > 0) {
            val chunkSize = minOf(remaining, mappedCapacityBytes - writePosition)
            dv.position(writePosition)
            dv.put(saved, readOffset, chunkSize)
            readOffset += chunkSize
            remaining -= chunkSize
            writePosition += chunkSize
            if (writePosition >= mappedCapacityBytes) writePosition = 0
        }
        meta.writeWritePosition(writePosition)
        meta.writeFilledBytes(saved.size)
        meta.writeLastWriteAtMillis(System.currentTimeMillis())
        force()
    }

    private fun canGrowWithoutCopy(
        oldCapacityBytes: Int,
        oldFilledBytes: Int,
        oldWritePosition: Int,
    ): Boolean {
        if (oldCapacityBytes <= 0 || oldFilledBytes <= 0) return true
        // If the ring has not wrapped, logical order is already [0, filled).
        if (oldFilledBytes < oldCapacityBytes) return true
        // If a full ring wrapped exactly to zero, logical order is still [0, oldCapacity).
        return oldWritePosition == 0
    }

    private fun growWrappedRingWithSmallestCopy(
        newCapacityBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
        oldCapacityBytes: Int,
        oldWritePosition: Int,
    ) {
        val oldStartPosition = oldWritePosition
        val headLength = oldCapacityBytes - oldStartPosition
        val tailLength = oldWritePosition
        val growthBytes = newCapacityBytes - oldCapacityBytes
        remap(newCapacityBytes, sampleRate, channelCount, bytesPerSample, clearContents = false)
        val meta = metaMap ?: return

        if (headLength <= tailLength) {
            copyWithinMappedFile(
                sourceOffset = oldStartPosition,
                targetOffset = oldStartPosition + growthBytes,
                count = headLength,
            )
            meta.writeWritePosition(oldWritePosition)
        } else {
            val afterOldEnd = minOf(tailLength, growthBytes)
            copyWithinMappedFile(sourceOffset = 0, targetOffset = oldCapacityBytes, count = afterOldEnd)
            val remainingTail = tailLength - afterOldEnd
            if (remainingTail > 0) {
                copyWithinMappedFile(sourceOffset = afterOldEnd, targetOffset = 0, count = remainingTail)
            }
            meta.writeWritePosition(tailLength - afterOldEnd)
        }
        meta.writeFilledBytes(oldCapacityBytes)
        meta.writeLastWriteAtMillis(System.currentTimeMillis())
        force()
    }

    private fun copyWithinMappedFile(
        sourceOffset: Int,
        targetOffset: Int,
        count: Int,
    ) {
        val dv = dataMap ?: return
        if (count <= 0 || sourceOffset == targetOffset) return
        val scratch = ioScratch
        if (targetOffset > sourceOffset && targetOffset < sourceOffset + count) {
            var remaining = count
            while (remaining > 0) {
                val chunkSize = minOf(remaining, scratch.size)
                val chunkSource = sourceOffset + remaining - chunkSize
                val chunkTarget = targetOffset + remaining - chunkSize
                dv.position(chunkSource)
                dv.get(scratch, 0, chunkSize)
                dv.position(chunkTarget)
                dv.put(scratch, 0, chunkSize)
                remaining -= chunkSize
            }
        } else {
            var copied = 0
            while (copied < count) {
                val chunkSize = minOf(count - copied, scratch.size)
                dv.position(sourceOffset + copied)
                dv.get(scratch, 0, chunkSize)
                dv.position(targetOffset + copied)
                dv.put(scratch, 0, chunkSize)
                copied += chunkSize
            }
        }
    }

    private fun readNewestBytes(count: Int): ByteArray {
        val meta = metaMap ?: return ByteArray(0)
        val dv = dataMap ?: return ByteArray(0)
        val filledBytes = meta.readFilledBytes().coerceIn(0, mappedCapacityBytes)
        val take = count.coerceIn(0, filledBytes)
        if (take <= 0) return ByteArray(0)
        val result = ByteArray(take)
        var readPosition = ringStartPosition(meta.readWritePosition(), take, mappedCapacityBytes)
        var remaining = take
        var writeOffset = 0
        while (remaining > 0) {
            val chunkSize = minOf(remaining, ioScratch.size, mappedCapacityBytes - readPosition)
            dv.position(readPosition)
            dv.get(ioScratch, 0, chunkSize)
            System.arraycopy(ioScratch, 0, result, writeOffset, chunkSize)
            writeOffset += chunkSize
            remaining -= chunkSize
            readPosition += chunkSize
            if (readPosition >= mappedCapacityBytes) readPosition = 0
        }
        return result
    }

    private fun ringStartPosition(
        writePosition: Int,
        filledBytes: Int,
        capacityBytes: Int,
    ): Int {
        if (capacityBytes <= 0 || filledBytes <= 0) return 0
        val normalizedWrite = writePosition.floorMod(capacityBytes)
        return (normalizedWrite - filledBytes).floorMod(capacityBytes)
    }

    private fun Int.floorMod(modulus: Int): Int {
        val value = this % modulus
        return if (value < 0) value + modulus else value
    }

    private fun remap(
        capacityBytes: Int,
        sampleRate: Int,
        channelCount: Int,
        bytesPerSample: Int,
        clearContents: Boolean,
    ) {
        dataMap = null
        runCatching { dataChannel?.close() }
        runCatching { dataAccess?.close() }
        dataChannel = null
        dataAccess = null

        dataAccess = RandomAccessFile(dataFile, "rwd").also { it.setLength(capacityBytes.toLong()) }
        dataChannel = requireNotNull(dataAccess).channel
        dataMap = requireNotNull(dataChannel).map(FileChannel.MapMode.READ_WRITE, 0, capacityBytes.toLong())

        mappedCapacityBytes = capacityBytes
        mappedSampleRate = sampleRate
        mappedChannelCount = channelCount
        mappedBytesPerSample = bytesPerSample

        val meta = requireNotNull(metaMap)
        meta.writeMagic()
        meta.writeVersion()
        meta.writeCapacityBytes(capacityBytes)
        meta.writeSampleRate(sampleRate)
        meta.writeChannelCount(channelCount)
        meta.writeBytesPerSample(bytesPerSample)
        if (clearContents) {
            meta.writeWritePosition(0)
            meta.writeFilledBytes(0)
            meta.writeLastWriteAtMillis(0L)
        }
        force()
    }

    private fun ensureMetaMapped() {
        if (metaMap != null) return
        val access = RandomAccessFile(metaFile, "rwd")
        try {
            access.setLength(META_FILE_BYTES.toLong())
            val channel = access.channel
            val mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, META_FILE_BYTES.toLong())
            metaAccess = access
            metaChannel = channel
            metaMap = mapped
        } catch (e: Exception) {
            runCatching { access.close() }
            throw e
        }
        if (metaMap?.readMagic() != MAGIC || metaMap?.readVersion() != VERSION) {
            metaMap?.writeMagic()
            metaMap?.writeVersion()
            metaMap?.writeCapacityBytes(0)
            metaMap?.writeSampleRate(0)
            metaMap?.writeChannelCount(0)
            metaMap?.writeBytesPerSample(0)
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

    private fun MappedByteBuffer.readBytesPerSample(): Int = getInt(OFFSET_BYTES_PER_SAMPLE)
    private fun MappedByteBuffer.writeBytesPerSample(value: Int) {
        putInt(OFFSET_BYTES_PER_SAMPLE, value)
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
        const val OFFSET_BYTES_PER_SAMPLE = 40

        fun alignToPageSize(bytes: Int): Int {
            val pageSize = 4096
            if (bytes <= pageSize) return pageSize
            val remainder = bytes % pageSize
            return if (remainder == 0) bytes else bytes + pageSize - remainder
        }
    }
}
