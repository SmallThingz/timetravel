package app.timetravel

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
        skipBytes: Int,
        reader: Consumer,
    ) {
        synchronized(this) {
            var remainingSkipBytes = skipBytes
            val currentBuffer = current
            if (!filling && currentBuffer != null && currentWasFilled) {
                remainingSkipBytes -= skipAndFeed(remainingSkipBytes, currentBuffer, offset, currentBuffer.size - offset, reader)
            }
            filled.forEach { array ->
                remainingSkipBytes -= skipAndFeed(remainingSkipBytes, array, 0, array.size, reader)
            }
            val activeBuffer = current
            if (activeBuffer != null && offset > 0) {
                skipAndFeed(remainingSkipBytes, activeBuffer, 0, offset, reader)
            }
        }
    }

    fun countFilled(): Int {
        var sum = 0
        synchronized(this) {
            val currentBuffer = current
            if (!filling && currentBuffer != null && currentWasFilled) {
                sum += currentBuffer.size - offset
            }
            filled.forEach { sum += it.size }
            if (currentBuffer != null && offset > 0) {
                sum += offset
            }
        }
        return sum
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
        val read = filler.consume(currentBuffer, offset, currentBuffer.size - offset)

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
        val total = (filled.size + free.size + if (current == null) 0 else 1) * CHUNK_SIZE
        val filledBytes = filled.size * CHUNK_SIZE + when {
            current == null -> 0
            currentWasFilled -> CHUNK_SIZE
            else -> offset
        }
        val estimation = if (filling) {
            ((SystemClock.uptimeMillis() - fillingStartUptimeMillis) * fillRate / 1000).toInt()
        } else {
            0
        }
        return Stats(
            filled = filledBytes,
            total = total,
            estimation = estimation,
            overwriting = currentWasFilled,
        )
    }

    @Throws(IOException::class)
    private fun skipAndFeed(
        bytesToSkip: Int,
        array: ByteArray,
        offset: Int,
        length: Int,
        consumer: Consumer,
    ): Int {
        return when {
            bytesToSkip >= length -> length
            bytesToSkip > 0 -> {
                consumer.consume(array, offset + bytesToSkip, length - bytesToSkip)
                bytesToSkip
            }
            else -> {
                consumer.consume(array, offset, length)
                0
            }
        }
    }

    data class Stats(
        val filled: Int,
        val total: Int,
        val estimation: Int,
        val overwriting: Boolean,
    )

    private companion object {
        const val CHUNK_SIZE = 480_000
    }
}
