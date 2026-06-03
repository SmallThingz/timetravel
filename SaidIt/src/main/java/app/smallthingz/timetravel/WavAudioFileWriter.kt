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
    private var writeBuffer: ByteBuffer? = null

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
        val buffer = if (writeBuffer?.array() === bytes) writeBuffer!! else {
            ByteBuffer.wrap(bytes).also { writeBuffer = it }
        }
        buffer.position(offset)
        buffer.limit(offset + count)
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
        headerBuffer.put(RIFF_BYTES)
        headerBuffer.putInt(chunkSize.toInt())
        headerBuffer.put(WAVE_BYTES)
        headerBuffer.put(FMT_BYTES)
        headerBuffer.putInt(SUBCHUNK1_SIZE)
        headerBuffer.putShort(AUDIO_FORMAT_PCM)
        headerBuffer.putShort(channelCount.toShort())
        headerBuffer.putInt(sampleRate)
        headerBuffer.putInt(byteRate)
        headerBuffer.putShort(blockAlign.toShort())
        headerBuffer.putShort(BITS_PER_SAMPLE.toShort())
        headerBuffer.put(DATA_BYTES)
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
        const val SUBCHUNK1_SIZE = 16
        const val AUDIO_FORMAT_PCM: Short = 1
        private val RIFF_BYTES = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        private val WAVE_BYTES = byteArrayOf(0x57, 0x41, 0x56, 0x45)
        private val FMT_BYTES = byteArrayOf(0x66, 0x6D, 0x74, 0x20)
        private val DATA_BYTES = byteArrayOf(0x64, 0x61, 0x74, 0x61)
    }
}
