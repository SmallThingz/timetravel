package app.smallthingz.timetravel

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
    private val channelCount: Int,
) : AudioFileWriter {
    private val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
    private val outputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)
    private val channel: FileChannel = outputStream.channel
    private val headerBuffer = ByteBuffer.allocate(HEADER_SIZE)

    override var totalSampleBytesWritten: Long = 0
        private set

    init {
        writeHeader(dataSize = 0)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        val buffer = ByteBuffer.wrap(bytes, offset, count)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
        totalSampleBytesWritten += count.toLong()
    }

    override fun close() {
        writeHeader(totalSampleBytesWritten)
        channel.truncate(HEADER_SIZE + totalSampleBytesWritten)
        outputStream.close()
        parcelFileDescriptor.close()
    }

    private fun writeHeader(dataSize: Long) {
        val chunkSize = 36L + dataSize
        val byteRate = sampleRate * channelCount * BITS_PER_SAMPLE / 8
        val blockAlign = channelCount * BITS_PER_SAMPLE / 8
        headerBuffer.clear()
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        headerBuffer.putInt(chunkSize.toInt())
        headerBuffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        headerBuffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        headerBuffer.putInt(16)
        headerBuffer.putShort(1)
        headerBuffer.putShort(channelCount.toShort())
        headerBuffer.putInt(sampleRate)
        headerBuffer.putInt(byteRate)
        headerBuffer.putShort(blockAlign.toShort())
        headerBuffer.putShort(BITS_PER_SAMPLE.toShort())
        headerBuffer.put("data".toByteArray(Charsets.US_ASCII))
        headerBuffer.putInt(dataSize.toInt())
        headerBuffer.flip()
        channel.position(0L)
        while (headerBuffer.hasRemaining()) {
            channel.write(headerBuffer)
        }
    }

    private companion object {
        const val BITS_PER_SAMPLE = 16
        const val HEADER_SIZE = 44
    }
}
