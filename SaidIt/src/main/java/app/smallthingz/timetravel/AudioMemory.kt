package app.smallthingz.timetravel

import android.os.SystemClock
import java.io.IOException
import java.util.ArrayDeque

internal class AudioMemory {
    private val filled = ArrayDeque<ByteArray>()
    private val free = ArrayDeque<ByteArray>()

    private var fillingStartUptimeMillis = 0L
    private var filling = false
    private var currentWasFilled = false
    private var current: ByteArray? = null
    private var offset = 0

    @Synchronized
    fun allocate(sizeToEnsure: Long) {
        var currentSize = allocatedMemorySize
        while (currentSize < sizeToEnsure) {
            currentSize += CHUNK_SIZE
            free.addLast(ByteArray(CHUNK_SIZE))
        }
        while (free.isNotEmpty() && currentSize - CHUNK_SIZE >= sizeToEnsure) {
            currentSize -= CHUNK_SIZE
            free.removeLast()
        }
        while (filled.isNotEmpty() && currentSize - CHUNK_SIZE >= sizeToEnsure) {
            currentSize -= CHUNK_SIZE
            filled.removeFirst()
        }
        if (current != null && currentSize - CHUNK_SIZE >= sizeToEnsure) {
            current = null
            offset = 0
            currentWasFilled = false
        }
    }

    @get:Synchronized
    val allocatedMemorySize: Long
        get() = (free.size + filled.size + if (current == null) 0 else 1).toLong() * CHUNK_SIZE

    fun interface Consumer {
        @Throws(IOException::class)
        fun consume(
            array: ByteArray,
            offset: Int,
            count: Int,
        ): Int
    }

    @Throws(IOException::class)
    fun read(
        skipBytes: Long,
        reader: Consumer,
    ) {
        read(skipBytes, Long.MAX_VALUE, reader)
    }

    @Throws(IOException::class)
    fun read(
        skipBytes: Long,
        maxBytes: Long,
        reader: Consumer,
    ) {
        synchronized(this) {
            var remainingSkipBytes = skipBytes
            var remainingTakeBytes = maxBytes.coerceAtLeast(0L)
            val currentBuffer = current
            if (!filling && currentBuffer != null && currentWasFilled) {
                val length = (currentBuffer.size - offset).toLong()
                if (remainingSkipBytes < length && remainingTakeBytes > 0L) {
                    val readOffset = offset + remainingSkipBytes.toInt()
                    val take = minOf(length - remainingSkipBytes, remainingTakeBytes).toInt()
                    reader.consume(currentBuffer, readOffset, take)
                    remainingSkipBytes = 0
                    remainingTakeBytes -= take.toLong()
                } else {
                    remainingSkipBytes -= length
                }
            }
            filled.forEach { array ->
                if (remainingTakeBytes <= 0L) {
                    return@forEach
                }
                val length = array.size.toLong()
                if (remainingSkipBytes < length) {
                    val readOffset = remainingSkipBytes.toInt()
                    val take = minOf(length - remainingSkipBytes, remainingTakeBytes).toInt()
                    reader.consume(array, readOffset, take)
                    remainingSkipBytes = 0
                    remainingTakeBytes -= take.toLong()
                } else {
                    remainingSkipBytes -= length
                }
            }
            val activeBuffer = current
            if (activeBuffer != null && offset > 0 && remainingTakeBytes > 0L) {
                val length = offset.toLong()
                if (remainingSkipBytes < length) {
                    val readOffset = remainingSkipBytes.toInt()
                    val take = minOf(length - remainingSkipBytes, remainingTakeBytes).toInt()
                    reader.consume(activeBuffer, readOffset, take)
                    remainingSkipBytes = 0
                    remainingTakeBytes -= take.toLong()
                } else {
                    remainingSkipBytes -= length
                }
            }
        }
    }

    fun countFilled(): Long {
        var sum = 0L
        synchronized(this) {
            val currentBuffer = current
            if (!filling && currentBuffer != null && currentWasFilled) {
                sum += (currentBuffer.size - offset).toLong()
            }
            filled.forEach { sum += it.size.toLong() }
            if (currentBuffer != null && offset > 0) {
                sum += offset.toLong()
            }
        }
        return sum
    }

    @Synchronized
    fun clear() {
        current?.let { free.addLast(it) }
        current = null
        offset = 0
        currentWasFilled = false
        filling = false
        filled.forEach { free.addLast(it) }
        filled.clear()
    }

    @Synchronized
    fun write(
        array: ByteArray,
        offset: Int,
        count: Int,
    ) {
        if (count <= 0) return

        var readOffset = offset
        var remaining = count
        while (remaining > 0) {
            if (current == null) {
                if (free.isEmpty()) {
                    if (filled.isEmpty()) {
                        return
                    }
                    currentWasFilled = true
                    current = filled.removeFirst()
                } else {
                    currentWasFilled = false
                    current = free.removeFirst()
                }
                this.offset = 0
            }

            val currentBuffer = requireNotNull(current)
            val copyCount = minOf(remaining, currentBuffer.size - this.offset)
            System.arraycopy(array, readOffset, currentBuffer, this.offset, copyCount)
            readOffset += copyCount
            remaining -= copyCount
            this.offset += copyCount

            if (this.offset >= currentBuffer.size) {
                filled.addLast(currentBuffer)
                current = null
                this.offset = 0
            }
        }
        filling = false
    }

    @Throws(IOException::class)
    fun fill(filler: Consumer) {
        synchronized(this) {
            if (current == null) {
                if (free.isEmpty()) {
                    if (filled.isEmpty()) {
                        return
                    }
                    currentWasFilled = true
                    current = filled.removeFirst()
                } else {
                    currentWasFilled = false
                    current = free.removeFirst()
                }
                offset = 0
            }
            filling = true
            fillingStartUptimeMillis = SystemClock.uptimeMillis()
        }

        val currentBuffer = requireNotNull(current)
        val read = filler.consume(currentBuffer, offset, currentBuffer.size - offset).coerceAtLeast(0)

        synchronized(this) {
            if (offset + read >= currentBuffer.size) {
                filled.addLast(currentBuffer)
                current = null
                offset = 0
            } else {
                offset += read
            }
            filling = false
        }
    }

    @Synchronized
    fun getStats(fillRate: Int): Stats {
        val total = (filled.size.toLong() + free.size.toLong() + if (current == null) 0L else 1L) * CHUNK_SIZE.toLong()
        val filledBytes = filled.size.toLong() * CHUNK_SIZE.toLong() + when {
            current == null -> 0L
            currentWasFilled -> CHUNK_SIZE.toLong()
            else -> offset
        }.toLong()
        val estimation = if (filling) {
            ((SystemClock.uptimeMillis() - fillingStartUptimeMillis) * fillRate.toLong() / 1000L)
        } else {
            0L
        }
        return Stats(
            filled = filledBytes,
            total = total,
            estimation = estimation,
            overwriting = currentWasFilled,
        )
    }

    data class Stats(
        val filled: Long,
        val total: Long,
        val estimation: Long,
        val overwriting: Boolean,
    )

    private companion object {
        const val CHUNK_SIZE = 480_000
    }
}
