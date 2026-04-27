package app.timetravel

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

internal class AacAudioFileWriter(
    context: Context,
    override val target: RecordingOutputTarget,
    private val sampleRate: Int,
) : AudioFileWriter {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val parcelFileDescriptor: ParcelFileDescriptor
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false
    private var closed = false

    override var totalSampleBytesWritten: Int = 0
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, aacBitrateForSampleRate(sampleRate))
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        parcelFileDescriptor = openWritableParcelFileDescriptor(context, target)
        muxer = MediaMuxer(parcelFileDescriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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

            val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw IOException("AAC encoder input buffer is null")
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
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw IOException("AAC muxer format changed twice")
                    }
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                        ?: throw IOException("AAC encoder output buffer is null")
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0) {
                        if (!muxerStarted) {
                            throw IOException("AAC muxer not started")
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

    private fun bytesToDurationUs(pcmBytes: Int): Long {
        val frames = pcmBytes / BYTES_PER_FRAME
        return frames * 1_000_000L / sampleRate
    }
    private companion object {
        const val TIMEOUT_US = 10_000L
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_FRAME = CHANNEL_COUNT * 2
    }
}
