package app.smallthingz.timetravel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.IOException

internal class EncodedAudioFileWriter(
    context: Context,
    override val target: RecordingOutputTarget,
    private val outputFormat: ExportFormat,
    private val codecConfig: ExportCodec,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitrateKbps: Int?,
) : AudioFileWriter {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val parcelFileDescriptor: ParcelFileDescriptor
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var closed = false

    override var totalSampleBytesWritten: Long = 0
        private set

    init {
        val format = buildEncoderFormat(codecConfig, sampleRate, channelCount, bitrateKbps)
        codec = MediaCodec.createEncoderByType(requireNotNull(codecConfig.encoderMimeType)).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        parcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
        muxer = MediaMuxer(parcelFileDescriptor.fileDescriptor, requireNotNull(outputFormat.muxerOutputFormat))
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
            val presentationTimeUs = bytesToDurationUs(totalSampleBytesWritten)
            inputBuffer.put(bytes, readOffset, toWrite)
            codec.queueInputBuffer(inputIndex, 0, toWrite, presentationTimeUs, 0)
            totalSampleBytesWritten += toWrite
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
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            muxer.release()
            parcelFileDescriptor.close()
        }
    }

    private fun signalEndOfInput() {
        while (true) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    bytesToDurationUs(totalSampleBytesWritten),
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
                    if (muxerStarted) {
                        throw IOException("Encoder muxer format changed twice")
                    }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    endOfStreamPollCount = 0
                }

                else -> if (outputIndex >= 0) {
                    endOfStreamPollCount = 0
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                        ?: throw IOException("Encoder output buffer is null")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) {
                            throw IOException("Muxer not started")
                        }
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
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
        val frames = pcmBytes / maxOf(channelCount * 2, 1)
        return frames * 1_000_000L / sampleRate.coerceAtLeast(1)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_END_OF_STREAM_POLL_COUNT = 200
    }
}
