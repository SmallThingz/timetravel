package app.timetravel

import java.io.File
import java.io.RandomAccessFile

internal class WavAudioFileWriter(
    override val file: File,
    private val sampleRate: Int,
) : AudioFileWriter {
    private val output = RandomAccessFile(file, "rw")

    override var totalSampleBytesWritten: Int = 0
        private set

    init {
        writeHeader(dataSize = 0)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        output.write(bytes, offset, count)
        totalSampleBytesWritten += count
    }

    override fun close() {
        output.seek(0L)
        writeHeader(totalSampleBytesWritten)
        output.close()
    }

    private fun writeHeader(dataSize: Int) {
        val chunkSize = 36 + dataSize
        val byteRate = sampleRate * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8

        output.writeBytes("RIFF")
        output.writeIntLE(chunkSize)
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        output.writeIntLE(16)
        output.writeShortLE(1)
        output.writeShortLE(CHANNEL_COUNT)
        output.writeIntLE(sampleRate)
        output.writeIntLE(byteRate)
        output.writeShortLE(blockAlign)
        output.writeShortLE(BITS_PER_SAMPLE)
        output.writeBytes("data")
        output.writeIntLE(dataSize)
    }

    private companion object {
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16

        fun RandomAccessFile.writeIntLE(value: Int) {
            write(value and 0xFF)
            write(value shr 8 and 0xFF)
            write(value shr 16 and 0xFF)
            write(value shr 24 and 0xFF)
        }

        fun RandomAccessFile.writeShortLE(value: Int) {
            write(value and 0xFF)
            write(value shr 8 and 0xFF)
        }
    }
}
