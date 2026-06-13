package app.smallthingz.timetravel

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal class WavAudioFileWriter(
    context: Context,
    override val target: RecordingOutputTarget,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
) : AudioFileWriter {
    private val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
    private val outputStream = FileOutputStream(parcelFileDescriptor.fileDescriptor)
    private val channel: FileChannel = outputStream.channel
    private val headerBuffer = ByteBuffer.allocate(HEADER_SIZE)
    @Volatile
    override var totalSampleBytesWritten: Long = 0
        private set

    init {
        try {
            writeHeader(dataSize = 0)
        } catch (e: Exception) {
            outputStream.channel.close()
            runCatching { parcelFileDescriptor.close() }
            throw e
        }
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        var written = 0
        while (written < count) {
            val n = channel.write(ByteBuffer.wrap(bytes, offset + written, count - written))
            if (n <= 0) throw IOException("Failed to write WAV data")
            written += n
        }
        totalSampleBytesWritten += count.toLong()
    }

    override fun close() {
        try {
            writeHeader(totalSampleBytesWritten)
            channel.truncate(HEADER_SIZE + totalSampleBytesWritten)
        } finally {
            outputStream.channel.close()
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun writeHeader(dataSize: Long) {
        val chunkSize = 36L + dataSize
        val byteRate = sampleRate * channelCount * sampleFormat.bytesPerSample
        val blockAlign = channelCount * sampleFormat.bytesPerSample
        headerBuffer.clear()
        headerBuffer.order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.put(RIFF_BYTES)
        headerBuffer.putInt((chunkSize and 0xFFFF_FFFFL).toInt())
        headerBuffer.put(WAVE_BYTES)
        headerBuffer.put(FMT_BYTES)
        headerBuffer.putInt(SUBCHUNK1_SIZE)
        headerBuffer.putShort(sampleFormat.wavFormatTag)
        headerBuffer.putShort(channelCount.toShort())
        headerBuffer.putInt(sampleRate)
        headerBuffer.putInt(byteRate)
        headerBuffer.putShort(blockAlign.toShort())
        headerBuffer.putShort(sampleFormat.bitsPerSample.toShort())
        headerBuffer.put(DATA_BYTES)
        headerBuffer.putInt((dataSize and 0xFFFF_FFFFL).toInt())
        headerBuffer.flip()
        channel.position(0L)
        channel.write(headerBuffer)
    }

    private companion object {
        const val HEADER_SIZE = 44
        const val SUBCHUNK1_SIZE = 16
        private val RIFF_BYTES = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        private val WAVE_BYTES = byteArrayOf(0x57, 0x41, 0x56, 0x45)
        private val FMT_BYTES = byteArrayOf(0x66, 0x6D, 0x74, 0x20)
        private val DATA_BYTES = byteArrayOf(0x64, 0x61, 0x74, 0x61)
    }
}
