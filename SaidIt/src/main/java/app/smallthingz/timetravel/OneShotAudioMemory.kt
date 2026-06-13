package app.smallthingz.timetravel

import java.io.IOException

internal class OneShotAudioMemory {
    private var data = ByteArray(0)
    private var filledBytes = 0

    @Synchronized
    fun allocate(sizeToEnsure: Long) {
        val targetSize = sizeToEnsure.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        if (data.size != targetSize) {
            val replacement = ByteArray(targetSize)
            val copy = minOf(filledBytes, targetSize)
            if (copy > 0) {
                System.arraycopy(data, 0, replacement, 0, copy)
            }
            data = replacement
            filledBytes = copy
        }
    }

    @Synchronized
    fun write(array: ByteArray, offset: Int, count: Int): Int {
        if (count <= 0 || filledBytes >= data.size) return 0
        val copy = minOf(count, data.size - filledBytes)
        System.arraycopy(array, offset, data, filledBytes, copy)
        filledBytes += copy
        return copy
    }

    @Synchronized
    fun clear() {
        filledBytes = 0
    }

    @Synchronized
    fun countFilled(): Long = filledBytes.toLong()

    @Synchronized
    fun snapshotBytes(): ByteArray = data.copyOf(filledBytes)

    @Synchronized
    @Throws(IOException::class)
    fun read(skipBytes: Long, maxBytes: Long, reader: AudioMemory.Consumer) {
        val skip = skipBytes.coerceAtLeast(0L).coerceAtMost(filledBytes.toLong()).toInt()
        val take = maxBytes.coerceAtLeast(0L).coerceAtMost(filledBytes.toLong() - skip).toInt()
        if (take > 0) {
            reader.consume(data, skip, take)
        }
    }
}
