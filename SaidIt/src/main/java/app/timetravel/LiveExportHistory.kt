package app.timetravel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal class LiveExportHistory(
    private val context: Context,
) {
    private val cacheRoot = File(File(context.cacheDir, TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME), "live-export-history")
    private val segments = ArrayDeque<Segment>()
    private val pinnedFiles = LinkedHashMap<String, Int>()

    private var config: Config? = null
    private var retentionBytes = 0L
    private var segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
    private var currentWriter: AudioFileWriter? = null
    private var currentSegment: Segment? = null
    private var nextSegmentStartMillis: Long? = null

    @Synchronized
    fun updateConfiguration(
        codec: ExportCodec,
        sampleRate: Int,
        channelCount: Int,
        bitrateKbps: Int?,
        retentionBytes: Long,
    ) {
        val updatedConfig = Config(codec, sampleRate, channelCount, bitrateKbps)
        val configChanged = config != updatedConfig
        val retentionChanged = this.retentionBytes != retentionBytes
        config = updatedConfig
        this.retentionBytes = retentionBytes
        segmentDurationMillis = updatedConfig.suggestedSegmentDurationMillis(retentionBytes)
        if (configChanged) {
            resetLocked()
        } else if (retentionChanged) {
            pruneLocked()
        }
    }

    @Synchronized
    fun append(
        array: ByteArray,
        offset: Int,
        count: Int,
        endedAtMillis: Long,
    ) {
        if (count <= 0) {
            return
        }
        val currentConfig = config ?: return
        ensureWriterLocked(
            startedAtMillis = nextSegmentStartMillis ?: (endedAtMillis - currentConfig.bytesToDurationMillis(count.toLong())).coerceAtLeast(0L),
        )
        val writer = currentWriter ?: return
        writer.write(array, offset, count)
        currentSegment?.sampleBytes = writer.totalSampleBytesWritten.toLong()
        val current = currentSegment
        if (current != null && currentConfig.bytesToDurationMillis(current.sampleBytes) >= segmentDurationMillis) {
            nextSegmentStartMillis = current.startedAtMillis + currentConfig.bytesToDurationMillis(current.sampleBytes)
            closeCurrentSegmentLocked()
        }
    }

    @Synchronized
    fun pause() {
        closeCurrentSegmentLocked()
    }

    @Synchronized
    fun clear() {
        resetLocked()
    }

    @Synchronized
    fun snapshotForExport(
        requestedSampleBytes: Long,
        reopenForContinuedCapture: Boolean,
    ): Snapshot? {
        val currentConfig = config ?: return null
        if (currentWriter != null) {
            currentSegment?.let { segment ->
                nextSegmentStartMillis = segment.startedAtMillis + currentConfig.bytesToDurationMillis(segment.sampleBytes)
            }
            closeCurrentSegmentLocked()
        }

        if (segments.isEmpty()) {
            if (reopenForContinuedCapture) {
                ensureWriterLocked(System.currentTimeMillis())
            }
            return null
        }

        val totalSampleBytes = segments.sumOf { it.sampleBytes }
        if (totalSampleBytes <= 0L) {
            if (reopenForContinuedCapture) {
                ensureWriterLocked(System.currentTimeMillis())
            }
            return null
        }

        val remainingSkip = (totalSampleBytes - requestedSampleBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        var skip = remainingSkip
        var take = totalSampleBytes - skip
        val slices = ArrayList<SegmentSlice>(segments.size)
        for (segment in segments) {
            if (take <= 0L) {
                break
            }
            if (skip >= segment.sampleBytes) {
                skip -= segment.sampleBytes
                continue
            }
            val skipInSegment = skip
            val takeInSegment = minOf(segment.sampleBytes - skipInSegment, take)
            if (takeInSegment > 0L) {
                slices += SegmentSlice(segment, skipInSegment, takeInSegment)
            }
            skip = 0L
            take -= takeInSegment
        }

        if (slices.isEmpty()) {
            if (reopenForContinuedCapture) {
                ensureWriterLocked(System.currentTimeMillis())
            }
            return null
        }

        slices.forEach { slice ->
            val key = slice.segment.file.absolutePath
            pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
        }

        if (reopenForContinuedCapture) {
            ensureWriterLocked(nextSegmentStartMillis ?: System.currentTimeMillis())
        }

        return Snapshot(
            config = currentConfig,
            slices = slices,
            requestedSampleBytes = slices.sumOf { it.takeSampleBytes },
        )
    }

    @Synchronized
    fun releaseSnapshot(snapshot: Snapshot) {
        snapshot.slices.forEach { slice ->
            val key = slice.segment.file.absolutePath
            val count = pinnedFiles[key] ?: return@forEach
            if (count <= 1) {
                pinnedFiles.remove(key)
            } else {
                pinnedFiles[key] = count - 1
            }
        }
        pruneLocked()
    }

    @Throws(IOException::class)
    fun exportSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
    ) {
        if (snapshot.slices.size == 1) {
            val only = snapshot.slices.single()
            if (only.skipSampleBytes == 0L && only.takeSampleBytes == only.segment.sampleBytes) {
                copyWholeFile(only.segment.file, outputTarget)
                return
            }
        }

        when (snapshot.config.codec) {
            ExportCodec.WAV -> exportWaveSnapshot(snapshot, outputTarget)
            else -> exportEncodedSnapshot(snapshot, outputTarget)
        }
    }

    private fun exportWaveSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
    ) {
        WavAudioFileWriter(context, outputTarget, snapshot.config.sampleRate, snapshot.config.channelCount).use { writer ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            snapshot.slices.forEach { slice ->
                RandomAccessFile(slice.segment.file, "r").use { input ->
                    input.seek(WAV_HEADER_BYTES + slice.skipSampleBytes)
                    var remaining = slice.takeSampleBytes
                    while (remaining > 0L) {
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, requested)
                        if (read <= 0) {
                            throw IOException("Short read while exporting history")
                        }
                        writer.write(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                }
            }
        }
    }

    private fun exportEncodedSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
    ) {
        val currentConfig = snapshot.config
        val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        val byteBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
        var outputBaseTimeUs = 0L

        try {
            muxer = MediaMuxer(parcelFileDescriptor.fileDescriptor, requireNotNull(currentConfig.codec.muxerOutputFormat))
            snapshot.slices.forEach { slice ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(slice.segment.file.absolutePath)
                    val extractorTrackIndex = findAudioTrack(extractor)
                    if (extractorTrackIndex < 0) {
                        return@forEach
                    }
                    extractor.selectTrack(extractorTrackIndex)
                    if (trackIndex < 0) {
                        trackIndex = muxer.addTrack(extractor.getTrackFormat(extractorTrackIndex))
                        muxer.start()
                    }

                    val sliceStartUs = currentConfig.bytesToDurationUs(slice.skipSampleBytes)
                    val sliceDurationUs = currentConfig.bytesToDurationUs(slice.takeSampleBytes)
                    val sliceEndUs = sliceStartUs + sliceDurationUs
                    extractor.seekTo(sliceStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    var firstWrittenSampleTimeUs = Long.MIN_VALUE
                    while (true) {
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L) {
                            break
                        }
                        if (sampleTimeUs < sliceStartUs) {
                            if (!extractor.advance()) {
                                break
                            }
                            continue
                        }
                        if (sampleTimeUs >= sliceEndUs && firstWrittenSampleTimeUs != Long.MIN_VALUE) {
                            break
                        }

                        byteBuffer.clear()
                        val sampleSize = extractor.readSampleData(byteBuffer, 0)
                        if (sampleSize <= 0) {
                            break
                        }
                        if (firstWrittenSampleTimeUs == Long.MIN_VALUE) {
                            firstWrittenSampleTimeUs = sampleTimeUs
                        }
                        bufferInfo.set(
                            0,
                            sampleSize,
                            outputBaseTimeUs + (sampleTimeUs - firstWrittenSampleTimeUs).coerceAtLeast(0L),
                            extractor.sampleFlags,
                        )
                        muxer.writeSampleData(trackIndex, byteBuffer, bufferInfo)
                        if (!extractor.advance()) {
                            break
                        }
                    }
                    outputBaseTimeUs += sliceDurationUs
                } finally {
                    extractor.release()
                }
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun copyWholeFile(
        source: File,
        outputTarget: RecordingOutputTarget,
    ) {
        when (outputTarget.storageType) {
            RecordingStorageType.FILE -> {
                FileOutputStream(requireNotNull(outputTarget.file)).use { output ->
                    source.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                }
            }

            RecordingStorageType.DOCUMENT -> {
                context.contentResolver.openOutputStream(requireNotNull(outputTarget.uri), "w")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_BYTES) }
                } ?: throw IOException("Unable to open export output stream")
            }
        }
    }

    private fun ensureWriterLocked(startedAtMillis: Long) {
        if (currentWriter != null) {
            return
        }
        val currentConfig = config ?: return
        if (!cacheRoot.exists()) {
            cacheRoot.mkdirs()
        }
        val file = File(cacheRoot, "history-${startedAtMillis}-${System.nanoTime()}.${currentConfig.codec.extension}")
        val target = RecordingOutputTarget(
            id = file.absolutePath,
            displayName = file.name,
            mimeType = currentConfig.codec.outputMimeType,
            storageType = RecordingStorageType.FILE,
            directoryId = cacheRoot.absolutePath,
            startedAtMillis = startedAtMillis,
            file = file,
        )
        currentSegment = Segment(file = file, startedAtMillis = startedAtMillis)
        currentWriter =
            when (currentConfig.codec) {
                ExportCodec.WAV -> WavAudioFileWriter(context, target, currentConfig.sampleRate, currentConfig.channelCount)
                else -> EncodedAudioFileWriter(
                    context = context,
                    target = target,
                    codecConfig = currentConfig.codec,
                    sampleRate = currentConfig.sampleRate,
                    channelCount = currentConfig.channelCount,
                    bitrateKbps = currentConfig.bitrateKbps,
                )
            }
    }

    private fun closeCurrentSegmentLocked() {
        val writer = currentWriter ?: return
        val segment = currentSegment
        currentWriter = null
        currentSegment = null
        runCatching { writer.close() }
        if (segment == null) {
            return
        }
        segment.sampleBytes = writer.totalSampleBytesWritten.toLong()
        if (segment.sampleBytes > 0L && segment.file.isFile && segment.file.length() > 0L) {
            segments.addLast(segment)
        } else {
            segment.file.delete()
        }
        pruneLocked()
    }

    private fun resetLocked() {
        currentWriter?.let { runCatching { it.close() } }
        currentWriter = null
        currentSegment = null
        nextSegmentStartMillis = null
        segments.forEach { segment ->
            if (!pinnedFiles.containsKey(segment.file.absolutePath)) {
                segment.file.delete()
            }
        }
        segments.clear()
        if (cacheRoot.exists() && cacheRoot.isDirectory) {
            cacheRoot.listFiles()?.forEach { file ->
                if (!pinnedFiles.containsKey(file.absolutePath)) {
                    file.delete()
                }
            }
        }
    }

    private fun pruneLocked() {
        val currentConfig = config ?: return
        if (retentionBytes <= 0L) {
            return
        }
        var totalBytes = segments.sumOf { it.sampleBytes } + (currentWriter?.totalSampleBytesWritten?.toLong() ?: 0L)
        while (segments.size > 1 && totalBytes > retentionBytes) {
            val oldest = segments.first()
            if (pinnedFiles.containsKey(oldest.file.absolutePath)) {
                break
            }
            segments.removeFirst()
            totalBytes -= oldest.sampleBytes
            oldest.file.delete()
        }
        currentSegment?.let { segment ->
            if (segment.sampleBytes > 0L) {
                nextSegmentStartMillis = segment.startedAtMillis + currentConfig.bytesToDurationMillis(segment.sampleBytes)
            }
        }
    }

    internal data class Config(
        val codec: ExportCodec,
        val sampleRate: Int,
        val channelCount: Int,
        val bitrateKbps: Int?,
    ) {
        private val bytesPerSecond = (sampleRate.toLong() * channelCount.toLong() * 2L).coerceAtLeast(1L)

        fun bytesToDurationUs(sampleBytes: Long): Long {
            return sampleBytes * 1_000_000L / bytesPerSecond
        }

        fun bytesToDurationMillis(sampleBytes: Long): Long {
            return sampleBytes * 1000L / bytesPerSecond
        }

        fun suggestedSegmentDurationMillis(retentionBytes: Long): Long {
            val retentionMillis = bytesToDurationMillis(retentionBytes)
            val quarterWindow = retentionMillis / 4L
            return quarterWindow.coerceIn(MIN_SEGMENT_DURATION_MS, MAX_SEGMENT_DURATION_MS)
        }
    }

    internal data class Segment(
        val file: File,
        val startedAtMillis: Long,
        var sampleBytes: Long = 0L,
    )

    data class SegmentSlice(
        val segment: Segment,
        val skipSampleBytes: Long,
        val takeSampleBytes: Long,
    )

    data class Snapshot(
        val config: Config,
        val slices: List<SegmentSlice>,
        val requestedSampleBytes: Long,
    )

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val DEFAULT_SEGMENT_DURATION_MS = 30_000L
        const val MIN_SEGMENT_DURATION_MS = 10_000L
        const val MAX_SEGMENT_DURATION_MS = 120_000L

        fun findAudioTrack(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    return index
                }
            }
            return -1
        }
    }
}
