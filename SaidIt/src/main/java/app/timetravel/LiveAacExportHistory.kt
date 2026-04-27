package app.timetravel

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque

internal class LiveAacExportHistory(
    internal val sampleRate: Int,
    internal val channelCount: Int,
) : Closeable {
    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()
    private val frames = ArrayDeque<EncodedAacFrame>()
    private var codecSpecificData: ByteArray? = null
    private var maxDurationUs: Long = 0L
    private val accessUnitDurationUs: Long =
        (AAC_SAMPLES_PER_ACCESS_UNIT * 1_000_000L) / sampleRate.coerceAtLeast(1).toLong()
    private var totalPcmBytesWritten = 0
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, aacBitrateForSampleRate(sampleRate, channelCount))
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    fun setMaxDurationUs(durationUs: Long) {
        maxDurationUs = durationUs.coerceAtLeast(0L)
        trimToRetention()
    }

    fun appendPcm(
        bytes: ByteArray,
        offset: Int,
        count: Int,
    ) {
        if (closed || count <= 0) return

        var remaining = count
        var readOffset = offset
        while (remaining > 0) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex < 0) {
                drainEncoder()
                continue
            }

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IOException("Live AAC input buffer missing")
            inputBuffer.clear()
            val toWrite = minOf(remaining, inputBuffer.remaining())
            val presentationTimeUs = bytesToDurationUs(totalPcmBytesWritten)
            inputBuffer.put(bytes, readOffset, toWrite)
            codec.queueInputBuffer(inputIndex, 0, toWrite, presentationTimeUs, 0)
            totalPcmBytesWritten += toWrite
            readOffset += toWrite
            remaining -= toWrite
            drainEncoder()
        }
    }

    fun snapshotLastDuration(durationUs: Long): AacExportSnapshot? {
        if (closed) return null
        val csd = codecSpecificData ?: return null
        val availableFrames = frames.size
        if (availableFrames == 0) return null

        val requestedFrames = ((durationUs.coerceAtLeast(0L) + accessUnitDurationUs - 1L) / accessUnitDurationUs)
            .coerceAtLeast(1L)
            .coerceAtMost(availableFrames.toLong())
            .toInt()

        val selected = frames.toList().takeLast(requestedFrames)
        return AacExportSnapshot(
            sampleRate = sampleRate,
            channelCount = channelCount,
            codecSpecificData = csd.copyOf(),
            frames = selected,
            durationUs = selected.size.toLong() * accessUnitDurationUs,
        )
    }

    fun clear() {
        frames.clear()
        totalPcmBytesWritten = 0
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        codec.release()
        frames.clear()
        codecSpecificData = null
    }

    private fun drainEncoder() {
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val buffer = codec.outputFormat.getByteBuffer("csd-0")
                        ?: throw IOException("Live AAC codec config missing")
                    val data = ByteArray(buffer.remaining())
                    buffer.duplicate().get(data)
                    codecSpecificData = data
                }

                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex) ?: throw IOException("Live AAC output buffer missing")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        frames.addLast(
                            EncodedAacFrame(
                                data = data,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                flags = bufferInfo.flags,
                            ),
                        )
                        trimToRetention()
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
    }

    private fun trimToRetention() {
        if (maxDurationUs <= 0L) {
            frames.clear()
            return
        }
        while (frames.isNotEmpty() && frames.size.toLong() * accessUnitDurationUs > maxDurationUs) {
            frames.removeFirst()
        }
    }

    private fun bytesToDurationUs(pcmBytes: Int): Long {
        val frames = pcmBytes / maxOf(channelCount * 2, 1)
        return frames * 1_000_000L / sampleRate.coerceAtLeast(1)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024L
    }
}

internal data class EncodedAacFrame(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val flags: Int,
)

internal data class AacExportSnapshot(
    val sampleRate: Int,
    val channelCount: Int,
    val codecSpecificData: ByteArray,
    val frames: List<EncodedAacFrame>,
    val durationUs: Long,
)

internal object AacSnapshotExporter {
    @Throws(IOException::class)
    fun export(
        context: android.content.Context,
        snapshot: AacExportSnapshot,
        target: RecordingOutputTarget,
    ) {
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
        val muxer = MediaMuxer(parcelFileDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                snapshot.sampleRate,
                snapshot.channelCount,
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setByteBuffer("csd-0", ByteBuffer.wrap(snapshot.codecSpecificData))
            }
            val trackIndex = muxer.addTrack(format)
            muxer.start()

            val startPtsUs = snapshot.frames.firstOrNull()?.presentationTimeUs ?: 0L
            val bufferInfo = MediaCodec.BufferInfo()
            snapshot.frames.forEach { frame ->
                val data = ByteBuffer.wrap(frame.data)
                bufferInfo.set(
                    0,
                    frame.data.size,
                    (frame.presentationTimeUs - startPtsUs).coerceAtLeast(0L),
                    frame.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME.inv(),
                )
                muxer.writeSampleData(trackIndex, data, bufferInfo)
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            parcelFileDescriptor.close()
        }
    }
}
