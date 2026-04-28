package app.smallthingz.timetravel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException

internal abstract class MediaCodecElementaryAudioFileWriter(
    context: Context,
    override val target: RecordingOutputTarget,
    codecConfig: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int?,
) : AudioFileWriter {
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    protected val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
    protected val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), OUTPUT_BUFFER_BYTES)
    private var closed = false

    private var writtenSampleBytes = 0L

    override val totalSampleBytesWritten: Long
        get() = writtenSampleBytes

    init {
        val format = buildEncoderFormat(codecConfig, sampleRate, channelCount, bitrateKbps)
        codec = MediaCodec.createEncoderByType(requireNotNull(codecConfig.encoderMimeType)).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        var remaining = count
        var readOffset = offset
        while (remaining > 0) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drainEncoder(endOfStream = false)
                continue
            }

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IOException("Encoder input buffer is null")
            inputBuffer.clear()
            val toWrite = minOf(remaining, inputBuffer.remaining())
            val presentationTimeUs = bytesToDurationUs(writtenSampleBytes)
            inputBuffer.put(bytes, readOffset, toWrite)
            codec.queueInputBuffer(inputIndex, 0, toWrite, presentationTimeUs, 0)
            writtenSampleBytes += toWrite
            readOffset += toWrite
            remaining -= toWrite
            drainEncoder(endOfStream = false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            signalEndOfInput()
            drainEncoder(endOfStream = true)
            outputStream.flush()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    protected open fun onOutputFormatChanged(outputFormat: MediaFormat) = Unit

    protected abstract fun writeEncodedAccessUnit(
        bytes: ByteArray,
        presentationTimeUs: Long,
    )

    private fun signalEndOfInput() {
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    bytesToDurationUs(writtenSampleBytes),
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                return
            }
            drainEncoder(endOfStream = false)
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        var endOfStreamPollCount = 0
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    endOfStreamPollCount++
                    if (endOfStreamPollCount >= MAX_END_OF_STREAM_POLL_COUNT) {
                        throw IOException("Timed out waiting for encoder end-of-stream output")
                    }
                }

                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    endOfStreamPollCount = 0
                    onOutputFormatChanged(codec.outputFormat)
                }

                else -> if (outputIndex >= 0) {
                    endOfStreamPollCount = 0
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                        ?: throw IOException("Encoder output buffer is null")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val encoded = ByteArray(bufferInfo.size)
                        outputBuffer.get(encoded)
                        writeEncodedAccessUnit(encoded, bufferInfo.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    private fun bytesToDurationUs(pcmBytes: Long): Long {
        val frames = pcmBytes / maxOf(channelCount() * 2, 1)
        return frames * 1_000_000L / sampleRate().coerceAtLeast(1)
    }

    protected abstract fun sampleRate(): Int

    protected abstract fun channelCount(): Int

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_END_OF_STREAM_POLL_COUNT = 200
        const val OUTPUT_BUFFER_BYTES = 256 * 1024
    }
}

internal class AdtsAudioFileWriter(
    context: Context,
    target: RecordingOutputTarget,
    private val codecConfig: ExportCodec,
    private val configuredSampleRate: Int,
    private val configuredChannelCount: Int,
    bitrateKbps: Int?,
) : MediaCodecElementaryAudioFileWriter(
    context = context,
    target = target,
    codecConfig = codecConfig,
    sampleRate = configuredSampleRate,
    channelCount = configuredChannelCount,
    bitrateKbps = bitrateKbps,
) {
    init {
        require(codecConfig == ExportCodec.AAC_LC) { "ADTS export supports AAC-LC only" }
    }

    override fun writeEncodedAccessUnit(
        bytes: ByteArray,
        presentationTimeUs: Long,
    ) {
        outputStream.write(buildAdtsHeader(configuredSampleRate, configuredChannelCount, bytes.size))
        outputStream.write(bytes)
    }

    override fun sampleRate(): Int = configuredSampleRate

    override fun channelCount(): Int = configuredChannelCount
}

internal class RawAmrAudioFileWriter(
    context: Context,
    target: RecordingOutputTarget,
    private val codecConfig: ExportCodec,
    private val configuredSampleRate: Int,
    private val configuredChannelCount: Int,
    bitrateKbps: Int?,
) : MediaCodecElementaryAudioFileWriter(
    context = context,
    target = target,
    codecConfig = codecConfig,
    sampleRate = configuredSampleRate,
    channelCount = configuredChannelCount,
    bitrateKbps = bitrateKbps,
) {
    init {
        require(codecConfig == ExportCodec.AMR_NB || codecConfig == ExportCodec.AMR_WB) { "Raw AMR export requires AMR codec" }
        outputStream.write(
            if (codecConfig == ExportCodec.AMR_WB) AMR_WB_MAGIC_HEADER else AMR_NB_MAGIC_HEADER,
        )
    }

    override fun writeEncodedAccessUnit(
        bytes: ByteArray,
        presentationTimeUs: Long,
    ) {
        outputStream.write(bytes)
    }

    override fun sampleRate(): Int = configuredSampleRate

    override fun channelCount(): Int = configuredChannelCount

    private companion object {
        val AMR_NB_MAGIC_HEADER = "#!AMR\n".toByteArray(Charsets.US_ASCII)
        val AMR_WB_MAGIC_HEADER = "#!AMR-WB\n".toByteArray(Charsets.US_ASCII)
    }
}

internal class TsAudioFileWriter(
    context: Context,
    target: RecordingOutputTarget,
    private val codecConfig: ExportCodec,
    private val configuredSampleRate: Int,
    private val configuredChannelCount: Int,
    bitrateKbps: Int?,
) : MediaCodecElementaryAudioFileWriter(
    context = context,
    target = target,
    codecConfig = codecConfig,
    sampleRate = configuredSampleRate,
    channelCount = configuredChannelCount,
    bitrateKbps = bitrateKbps,
) {
    private val packetizer = MpegTsAacPacketizer(outputStream, configuredSampleRate, configuredChannelCount)

    init {
        require(codecConfig == ExportCodec.AAC_LC) { "MPEG-2 TS export supports AAC-LC only" }
    }

    override fun writeEncodedAccessUnit(
        bytes: ByteArray,
        presentationTimeUs: Long,
    ) {
        packetizer.writeAccessUnit(bytes, presentationTimeUs)
    }

    override fun sampleRate(): Int = configuredSampleRate

    override fun channelCount(): Int = configuredChannelCount
}

internal fun buildAdtsHeader(
    sampleRate: Int,
    channelCount: Int,
    payloadSize: Int,
): ByteArray {
    return ByteArray(ADTS_HEADER_SIZE).also { header ->
        fillAdtsHeader(header, sampleRate, channelCount, payloadSize)
    }
}

internal fun fillAdtsHeader(
    target: ByteArray,
    sampleRate: Int,
    channelCount: Int,
    payloadSize: Int,
) {
    require(target.size >= ADTS_HEADER_SIZE) { "ADTS header target too small" }
    val sampleRateIndex = AAC_SAMPLE_RATE_INDICES[sampleRate]
        ?: throw IOException("Unsupported ADTS sample rate: $sampleRate")
    val frameLength = payloadSize + ADTS_HEADER_SIZE
    target[0] = 0xFF.toByte()
    target[1] = 0xF1.toByte()
    target[2] = (((AAC_LC_PROFILE - 1) shl 6) or (sampleRateIndex shl 2) or ((channelCount shr 2) and 0x1)).toByte()
    target[3] = (((channelCount and 0x3) shl 6) or ((frameLength shr 11) and 0x3)).toByte()
    target[4] = ((frameLength shr 3) and 0xFF).toByte()
    target[5] = (((frameLength and 0x7) shl 5) or 0x1F).toByte()
    target[6] = 0xFC.toByte()
}

internal class MpegTsAacPacketizer(
    private val outputStream: BufferedOutputStream,
    private val sampleRate: Int,
    private val channelCount: Int,
) {
    private var patCounter = 0
    private var pmtCounter = 0
    private var audioCounter = 0
    private var tablesWritten = false
    private val adtsHeaderScratch = ByteArray(ADTS_HEADER_SIZE)

    fun writeAccessUnit(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
    ) {
        if (!tablesWritten) {
            writePsiPacket(PAT_PID, buildPatSection(), true)
            writePsiPacket(PMT_PID, buildPmtSection(), false)
            tablesWritten = true
        }

        fillAdtsHeader(adtsHeaderScratch, sampleRate, channelCount, size)
        val pts = encodePts(presentationTimeUs.coerceAtLeast(0L))
        val pesHeader = ByteArray(14).apply {
            this[0] = 0x00
            this[1] = 0x00
            this[2] = 0x01
            this[3] = 0xC0.toByte()
            val pesPacketLength = (ADTS_HEADER_SIZE + size + 8).coerceAtMost(0xFFFF)
            this[4] = ((pesPacketLength shr 8) and 0xFF).toByte()
            this[5] = (pesPacketLength and 0xFF).toByte()
            this[6] = 0x80.toByte()
            this[7] = 0x80.toByte()
            this[8] = 0x05
            System.arraycopy(pts, 0, this, 9, pts.size)
        }
        packetizePayload(AUDIO_PID, pesHeader, 0, pesHeader.size)
        packetizePayload(AUDIO_PID, adtsHeaderScratch, 0, adtsHeaderScratch.size)
        packetizePayload(AUDIO_PID, bytes, offset, size)
    }

    fun writeAccessUnit(
        bytes: ByteArray,
        presentationTimeUs: Long,
    ) {
        writeAccessUnit(bytes, 0, bytes.size, presentationTimeUs)
    }

    private fun writePsiPacket(
        pid: Int,
        section: ByteArray,
        pat: Boolean,
    ) {
        val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
        packet[0] = 0x47
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        val continuity = if (pat) patCounter++ and 0x0F else pmtCounter++ and 0x0F
        packet[3] = (0x10 or continuity).toByte()
        packet[4] = 0x00
        System.arraycopy(section, 0, packet, 5, section.size)
        outputStream.write(packet)
    }

    private fun packetizePayload(
        pid: Int,
        payload: ByteArray,
        payloadOffsetStart: Int = 0,
        payloadSize: Int = payload.size,
    ) {
        var offset = payloadOffsetStart
        val endOffset = payloadOffsetStart + payloadSize
        var firstPacket = true
        while (offset < endOffset) {
            val remaining = endOffset - offset
            val packet = ByteArray(TS_PACKET_SIZE) { 0xFF.toByte() }
            packet[0] = 0x47
            packet[1] = ((((if (firstPacket) 0x40 else 0x00) or ((pid shr 8) and 0x1F))) and 0xFF).toByte()
            packet[2] = (pid and 0xFF).toByte()
            val continuity = audioCounter++ and 0x0F

            val useAdaptation = remaining < TS_PAYLOAD_SIZE
            packet[3] = ((if (useAdaptation) 0x30 else 0x10) or continuity).toByte()

            var payloadOffset = 4
            if (useAdaptation) {
                val adaptationLength = TS_PAYLOAD_SIZE - remaining - 1
                packet[payloadOffset] = adaptationLength.toByte()
                payloadOffset += 1
                if (adaptationLength > 0) {
                    packet[payloadOffset] = 0x00
                    payloadOffset += 1
                    for (index in 1 until adaptationLength) {
                        packet[payloadOffset++] = 0xFF.toByte()
                    }
                }
            }

            val chunkSize = minOf(payload.size - offset, TS_PACKET_SIZE - payloadOffset)
            System.arraycopy(payload, offset, packet, payloadOffset, chunkSize)
            outputStream.write(packet)
            offset += chunkSize
            firstPacket = false
        }
    }

    private fun buildPatSection(): ByteArray {
        val section = byteArrayOf(
            0x00,
            0xB0.toByte(), 0x0D,
            0x00, 0x01,
            0xC1.toByte(),
            0x00,
            0x00,
            0x00, 0x01,
            (0xE0 or ((PMT_PID shr 8) and 0x1F)).toByte(),
            (PMT_PID and 0xFF).toByte(),
        )
        return appendMpegCrc(section)
    }

    private fun buildPmtSection(): ByteArray {
        val section = byteArrayOf(
            0x02,
            0xB0.toByte(), 0x12,
            0x00, 0x01,
            0xC1.toByte(),
            0x00,
            0x00,
            (0xE0 or ((AUDIO_PID shr 8) and 0x1F)).toByte(),
            (AUDIO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
            0x0F,
            (0xE0 or ((AUDIO_PID shr 8) and 0x1F)).toByte(),
            (AUDIO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
        )
        return appendMpegCrc(section)
    }

    private fun encodePts(ptsUs: Long): ByteArray {
        val pts90k = ptsUs * 90L / 1000L
        return byteArrayOf(
            (((pts90k shr 29) and 0x0E) or 0x21).toByte(),
            ((pts90k shr 22) and 0xFF).toByte(),
            ((((pts90k shr 14) and 0xFE) or 0x01)).toByte(),
            ((pts90k shr 7) and 0xFF).toByte(),
            ((((pts90k shl 1) and 0xFE) or 0x01)).toByte(),
        )
    }

    private fun appendMpegCrc(section: ByteArray): ByteArray {
        val crc = mpegCrc32(section)
        return section + byteArrayOf(
            ((crc shr 24) and 0xFF).toByte(),
            ((crc shr 16) and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
            (crc and 0xFF).toByte(),
        )
    }

    private fun mpegCrc32(bytes: ByteArray): Int {
        var crc = -1
        bytes.forEach { value ->
            crc = crc xor ((value.toInt() and 0xFF) shl 24)
            repeat(8) {
                crc =
                    if ((crc and Int.MIN_VALUE) != 0) {
                        (crc shl 1) xor 0x04C11DB7
                    } else {
                        crc shl 1
                    }
            }
        }
        return crc
    }

    private companion object {
        const val TS_PACKET_SIZE = 188
        const val TS_PAYLOAD_SIZE = 184
        const val PAT_PID = 0x0000
        const val PMT_PID = 0x0100
        const val AUDIO_PID = 0x0101
    }
}

private const val ADTS_HEADER_SIZE = 7
private const val AAC_LC_PROFILE = 2
private val AAC_SAMPLE_RATE_INDICES =
    mapOf(
        96_000 to 0,
        88_200 to 1,
        64_000 to 2,
        48_000 to 3,
        44_100 to 4,
        32_000 to 5,
        24_000 to 6,
        22_050 to 7,
        16_000 to 8,
        12_000 to 9,
        11_025 to 10,
        8_000 to 11,
        7_350 to 12,
    )
