package app.timetravel

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal class WavAudioFileWriter(
    context: Context,
    override val target: RecordingOutputTarget,
    private val sampleRate: Int,
) : AudioFileWriter {
    private val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
    private val outputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)
    private val channel: FileChannel = outputStream.channel

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
        channel.write(ByteBuffer.wrap(bytes, offset, count))
        totalSampleBytesWritten += count
    }

    override fun close() {
        writeHeader(totalSampleBytesWritten)
        channel.truncate(HEADER_SIZE + totalSampleBytesWritten.toLong())
        outputStream.close()
        parcelFileDescriptor.close()
    }

    private fun writeHeader(dataSize: Int) {
        val chunkSize = 36 + dataSize
        val byteRate = sampleRate * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(CHANNEL_COUNT.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
            flip()
        }
        channel.position(0L)
        while (header.hasRemaining()) {
            channel.write(header)
        }
    }

    private companion object {
        const val CHANNEL_COUNT = 1
        const val BITS_PER_SAMPLE = 16
        const val HEADER_SIZE = 44
    }
}
