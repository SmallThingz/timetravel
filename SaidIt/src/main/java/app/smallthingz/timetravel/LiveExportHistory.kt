package app.smallthingz.timetravel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

internal class LiveExportHistory(
    private val context: Context,
) {
    private val historyRoot = File(File(context.noBackupFilesDir, TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME), "live-export-history")
    private val legacyCacheRoot = File(File(context.cacheDir, TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME), "live-export-history")
    private val segments = ArrayDeque<Segment>()
    private val pinnedFiles = LinkedHashMap<String, Int>()

    private var config: Config? = null
    private var retentionBytes = 0L
    private var segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
    private var compactionTargetDurationMillis: Long? = null
    private var currentWriter: AudioFileWriter? = null
    private var currentSegment: Segment? = null
    private var nextSegmentStartMillis: Long? = null
    private var compactionInFlight = false

    @Synchronized
    fun updateConfiguration(
        format: ExportFormat,
        codec: ExportCodec,
        sampleRate: Int,
        channelCount: Int,
        bitrateKbps: Int?,
        retentionBytes: Long,
        segmentDurationMillis: Long,
        compactionTargetDurationMillis: Long?,
    ) {
        val updatedConfig = Config(format, codec, sampleRate, channelCount, bitrateKbps)
        val previousConfig = config
        val configChanged = previousConfig != updatedConfig
        val retentionChanged = this.retentionBytes != retentionBytes
        val segmentDurationChanged = this.segmentDurationMillis != segmentDurationMillis
        val compactionChanged = this.compactionTargetDurationMillis != compactionTargetDurationMillis
        config = updatedConfig
        this.retentionBytes = retentionBytes
        this.segmentDurationMillis = segmentDurationMillis.coerceIn(MIN_SEGMENT_DURATION_MS, MAX_SEGMENT_DURATION_MS)
        this.compactionTargetDurationMillis = compactionTargetDurationMillis?.coerceIn(MIN_COMPACTION_DURATION_MS, MAX_COMPACTION_DURATION_MS)
        if (previousConfig == null) {
            migrateLegacySegmentsLocked()
            restorePersistedSegmentsLocked(updatedConfig)
            pruneLocked()
        } else if (configChanged) {
            resetLocked()
        } else if (retentionChanged || segmentDurationChanged || compactionChanged) {
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
    fun checkpoint() {
        closeCurrentSegmentLocked()
    }

    @Synchronized
    fun closePreservingHistory() {
        closeCurrentSegmentLocked()
        currentWriter = null
        currentSegment = null
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
        val totalSampleBytes = segments.sumOf { it.sampleBytes } + (currentWriter?.totalSampleBytesWritten?.toLong() ?: 0L)
        val skipBytes = (totalSampleBytes - requestedSampleBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        return snapshotForRange(skipBytes, requestedSampleBytes.coerceAtLeast(0L), reopenForContinuedCapture)
    }

    @Synchronized
    fun snapshotForRange(
        skipSampleBytes: Long,
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

        var skip = skipSampleBytes.coerceIn(0L, totalSampleBytes)
        var take = minOf(requestedSampleBytes.coerceAtLeast(0L), totalSampleBytes - skip)
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

    fun compactIfNeeded(): Boolean {
        val task = synchronized(this) { claimCompactionTaskLocked() } ?: return false
        var mergedFile: File? = null
        return try {
            exportSnapshot(task.snapshot, task.outputTarget)
            mergedFile = requireNotNull(task.outputTarget.file)
            true
        } catch (e: Exception) {
            Log.w(TAG, "History compaction failed", e)
            false
        } finally {
            synchronized(this) {
                finishCompactionLocked(task, mergedFile)
            }
        }
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

        when {
            snapshot.config.format.isPcmContainer -> exportWaveSnapshot(snapshot, outputTarget)
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
            muxer = MediaMuxer(parcelFileDescriptor.fileDescriptor, requireNotNull(currentConfig.format.muxerOutputFormat))
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
                        if (sampleTimeUs >= sliceEndUs) {
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
                    if (firstWrittenSampleTimeUs != Long.MIN_VALUE) {
                        outputBaseTimeUs += sliceDurationUs
                    }
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
        if (!historyRoot.exists()) {
            historyRoot.mkdirs()
        }
        val file = File(historyRoot, "history-${startedAtMillis}-${System.nanoTime()}.${currentConfig.format.extension}")
        val target = RecordingOutputTarget(
            id = file.absolutePath,
            displayName = file.name,
            mimeType = currentConfig.format.outputMimeType,
            storageType = RecordingStorageType.FILE,
            directoryId = historyRoot.absolutePath,
            startedAtMillis = startedAtMillis,
            file = file,
        )
        currentSegment = Segment(file = file, startedAtMillis = startedAtMillis)
        currentWriter =
            when {
                currentConfig.format.isPcmContainer -> WavAudioFileWriter(context, target, currentConfig.sampleRate, currentConfig.channelCount)
                else -> EncodedAudioFileWriter(
                    context = context,
                    target = target,
                    outputFormat = currentConfig.format,
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
        finalizeSegmentFileLocked(segment)
        if (segment.sampleBytes > 0L && segment.file.isFile && segment.file.length() > 0L) {
            segments.addLast(segment)
        } else {
            segment.file.delete()
        }
        pruneLocked()
    }

    private fun claimCompactionTaskLocked(): CompactionTask? {
        if (compactionInFlight) {
            return null
        }
        val currentConfig = config ?: return null
        val targetDurationMillis = compactionTargetDurationMillis ?: return null
        if (targetDurationMillis <= segmentDurationMillis) {
            return null
        }

        val compactableBytes = currentConfig.durationMillisToSampleBytes(targetDurationMillis)
        if (compactableBytes <= 0L) {
            return null
        }

        val currentSegments = segments.toList()
        var startIndex = -1
        var totalSampleBytes = 0L
        val selectedSegments = ArrayList<Segment>()

        for ((index, segment) in currentSegments.withIndex()) {
            val key = segment.file.absolutePath
            val alreadyPinned = pinnedFiles.containsKey(key)
            val alreadyLargeEnough = segment.sampleBytes >= compactableBytes

            if (alreadyPinned || alreadyLargeEnough) {
                if (selectedSegments.isNotEmpty()) {
                    break
                }
                continue
            }

            if (startIndex < 0) {
                startIndex = index
            }
            selectedSegments += segment
            totalSampleBytes += segment.sampleBytes
            if (totalSampleBytes >= compactableBytes) {
                break
            }
        }

        if (selectedSegments.size < MIN_COMPACTION_SEGMENTS || startIndex < 0 || totalSampleBytes <= 0L) {
            return null
        }

        val slices = selectedSegments.map { segment ->
            val key = segment.file.absolutePath
            pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
            SegmentSlice(segment, 0L, segment.sampleBytes)
        }

        if (!historyRoot.exists()) {
            historyRoot.mkdirs()
        }

        val tempFile = File(historyRoot, "compact-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}.${currentConfig.format.extension}.tmp")
        val outputTarget = RecordingOutputTarget(
            id = tempFile.absolutePath,
            displayName = tempFile.name,
            mimeType = currentConfig.format.outputMimeType,
            storageType = RecordingStorageType.FILE,
            directoryId = historyRoot.absolutePath,
            startedAtMillis = selectedSegments.first().startedAtMillis,
            file = tempFile,
        )
        compactionInFlight = true
        return CompactionTask(
            startIndex = startIndex,
            sourcePaths = selectedSegments.map { it.file.absolutePath },
            startedAtMillis = selectedSegments.first().startedAtMillis,
            totalSampleBytes = totalSampleBytes,
            snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
            outputTarget = outputTarget,
        )
    }

    private fun finishCompactionLocked(
        task: CompactionTask,
        mergedFile: File?,
    ) {
        compactionInFlight = false
        task.snapshot.slices.forEach { slice ->
            val key = slice.segment.file.absolutePath
            val count = pinnedFiles[key] ?: return@forEach
            if (count <= 1) {
                pinnedFiles.remove(key)
            } else {
                pinnedFiles[key] = count - 1
            }
        }

        val mergedSegment =
            mergedFile
                ?.takeIf { it.isFile && it.length() > 0L && config == task.snapshot.config }
                ?.let { file ->
                    Segment(
                        file = file,
                        startedAtMillis = task.startedAtMillis,
                        sampleBytes = task.totalSampleBytes,
                    )
                }

        val replaced = if (mergedSegment != null) {
            replaceSegmentsLocked(task, mergedSegment)
        } else {
            false
        }

        if (!replaced) {
            mergedFile?.delete()
        }

        val livePaths = segments.mapTo(HashSet()) { it.file.absolutePath }
        task.sourcePaths.forEach { path ->
            if (path !in livePaths && !pinnedFiles.containsKey(path)) {
                File(path).delete()
            }
        }
        pruneLocked()
    }

    private fun replaceSegmentsLocked(
        task: CompactionTask,
        mergedSegment: Segment,
    ): Boolean {
        val currentSegments = segments.toList()
        val endIndexExclusive = task.startIndex + task.sourcePaths.size
        if (task.startIndex < 0 || endIndexExclusive > currentSegments.size) {
            return false
        }
        val matches = task.sourcePaths.indices.all { offset ->
            currentSegments[task.startIndex + offset].file.absolutePath == task.sourcePaths[offset]
        }
        if (!matches) {
            return false
        }

        finalizeSegmentFileLocked(mergedSegment)
        val replacement = ArrayDeque<Segment>(currentSegments.size - task.sourcePaths.size + 1)
        currentSegments.subList(0, task.startIndex).forEach(replacement::addLast)
        replacement.addLast(mergedSegment)
        currentSegments.subList(endIndexExclusive, currentSegments.size).forEach(replacement::addLast)
        segments.clear()
        segments.addAll(replacement)
        return true
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
        deleteUnpinnedFilesIn(historyRoot)
        deleteUnpinnedFilesIn(legacyCacheRoot)
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

    private fun restorePersistedSegmentsLocked(currentConfig: Config) {
        if (!historyRoot.exists()) {
            return
        }
        val restoredSegments = historyRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.mapNotNull { file -> inspectPersistedSegment(file, currentConfig) }
            ?.sortedWith(compareBy<Segment>({ it.startedAtMillis }).thenByDescending { it.sampleBytes })
            ?.toList()
            .orEmpty()
        val normalizedSegments = discardFullyOverlappedSegments(restoredSegments, currentConfig)

        segments.clear()
        segments.addAll(normalizedSegments)
        nextSegmentStartMillis = normalizedSegments.lastOrNull()?.let { segment ->
            segment.startedAtMillis + currentConfig.bytesToDurationMillis(segment.sampleBytes)
        }

        val keep = normalizedSegments.mapTo(HashSet()) { it.file.absolutePath }
        historyRoot.listFiles()?.forEach { file ->
            if (file.isFile && (file.absolutePath !in keep || file.name.endsWith(TEMP_FILE_SUFFIX))) {
                file.delete()
            }
        }
    }

    private fun discardFullyOverlappedSegments(
        restoredSegments: List<Segment>,
        currentConfig: Config,
    ): List<Segment> {
        if (restoredSegments.size < 2) {
            return restoredSegments
        }
        val kept = ArrayList<Segment>(restoredSegments.size)
        var keptEndMillis = Long.MIN_VALUE
        restoredSegments.forEach { segment ->
            val segmentEndMillis = segment.startedAtMillis + currentConfig.bytesToDurationMillis(segment.sampleBytes)
            if (kept.isNotEmpty() && segmentEndMillis <= keptEndMillis) {
                return@forEach
            }
            kept += segment
            keptEndMillis = maxOf(keptEndMillis, segmentEndMillis)
        }
        return kept.sortedBy { it.startedAtMillis }
    }

    private fun inspectPersistedSegment(
        file: File,
        currentConfig: Config,
    ): Segment? {
        val extension = file.extension.lowercase()
        if (extension != currentConfig.format.extension.lowercase()) {
            return null
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) {
                return null
            }
            val format = extractor.getTrackFormat(trackIndex)
            if (!matchesConfig(format, currentConfig)) {
                return null
            }

            val startedAtMillis = parseStartedAtMillis(file) ?: file.lastModified()
            val sampleBytes =
                parseSampleBytes(file)
                    ?: if (currentConfig.format.isPcmContainer) {
                        (file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)
                    } else {
                        durationUsFor(file, format)?.let { currentConfig.durationUsToSampleBytes(it) }
                    }
                    ?: return null
            if (sampleBytes <= 0L) {
                return null
            }
            return Segment(file = file, startedAtMillis = startedAtMillis, sampleBytes = sampleBytes)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun matchesConfig(
        format: MediaFormat,
        currentConfig: Config,
    ): Boolean {
        val sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (sampleRate != null && sampleRate != currentConfig.sampleRate) {
            return false
        }
        if (channelCount != null && channelCount != currentConfig.channelCount) {
            return false
        }
        val expectedMime = currentConfig.codec.encoderMimeType
        return expectedMime == null || mime == null || mime == expectedMime
    }

    private fun durationUsFor(
        file: File,
        format: MediaFormat,
    ): Long? {
        format.getLongOrNull(MediaFormat.KEY_DURATION)?.let { return it }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun finalizeSegmentFileLocked(segment: Segment) {
        if (segment.sampleBytes <= 0L || !segment.file.isFile) {
            return
        }
        val currentConfig = config ?: return
        val targetName = buildSegmentFileName(segment.startedAtMillis, segment.sampleBytes, currentConfig.format.extension)
        if (segment.file.name == targetName) {
            return
        }
        val renamed = File(historyRoot, targetName)
        if (renamed.exists()) {
            renamed.delete()
        }
        if (segment.file.renameTo(renamed)) {
            segment.file = renamed
        }
    }

    private fun migrateLegacySegmentsLocked() {
        if (!legacyCacheRoot.exists() || !legacyCacheRoot.isDirectory) {
            return
        }
        if (!historyRoot.exists()) {
            historyRoot.mkdirs()
        }
        legacyCacheRoot.listFiles()?.forEach { file ->
            if (!file.isFile) {
                return@forEach
            }
            val migrated = File(historyRoot, file.name)
            if (migrated.exists()) {
                file.delete()
                return@forEach
            }
            if (!file.renameTo(migrated)) {
                runCatching {
                    file.inputStream().use { input ->
                        migrated.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    file.delete()
                }.onFailure {
                    migrated.delete()
                }
            }
        }
    }

    private fun deleteUnpinnedFilesIn(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            return
        }
        directory.listFiles()?.forEach { file ->
            if (!pinnedFiles.containsKey(file.absolutePath)) {
                file.delete()
            }
        }
    }

    private fun buildSegmentFileName(
        startedAtMillis: Long,
        sampleBytes: Long,
        extension: String,
    ): String {
        return "history-$startedAtMillis-pcm-$sampleBytes.$extension"
    }

    private fun parseStartedAtMillis(file: File): Long? {
        return METADATA_NAME_REGEX.matchEntire(file.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: LEGACY_NAME_REGEX.matchEntire(file.name)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    private fun parseSampleBytes(file: File): Long? {
        return METADATA_NAME_REGEX.matchEntire(file.name)?.groupValues?.getOrNull(2)?.toLongOrNull()
    }

    internal data class Config(
        val format: ExportFormat,
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

        fun durationUsToSampleBytes(durationUs: Long): Long {
            return durationUs * bytesPerSecond / 1_000_000L
        }

        fun durationMillisToSampleBytes(durationMillis: Long): Long {
            return durationMillis * bytesPerSecond / 1000L
        }

    }

    internal data class Segment(
        var file: File,
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

    private data class CompactionTask(
        val startIndex: Int,
        val sourcePaths: List<String>,
        val startedAtMillis: Long,
        val totalSampleBytes: Long,
        val snapshot: Snapshot,
        val outputTarget: RecordingOutputTarget,
    )

    data class DebugSnapshot(
        val segmentCount: Int,
        val totalSampleBytes: Long,
        val currentSegmentSampleBytes: Long,
        val nextSegmentStartMillis: Long?,
        val segmentFiles: List<String>,
    )

    @Synchronized
    fun debugSnapshot(): DebugSnapshot {
        return DebugSnapshot(
            segmentCount = segments.size,
            totalSampleBytes = segments.sumOf { it.sampleBytes } + (currentWriter?.totalSampleBytesWritten?.toLong() ?: 0L),
            currentSegmentSampleBytes = currentWriter?.totalSampleBytesWritten?.toLong() ?: 0L,
            nextSegmentStartMillis = nextSegmentStartMillis,
            segmentFiles = segments.map { it.file.name },
        )
    }

    private companion object {
        const val TAG = "LiveExportHistory"
        const val WAV_HEADER_BYTES = 44L
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val DEFAULT_SEGMENT_DURATION_MS = 2_000L
        const val MIN_SEGMENT_DURATION_MS = 2_000L
        const val MAX_SEGMENT_DURATION_MS = 2_000L
        const val MIN_COMPACTION_DURATION_MS = 30_000L
        const val MAX_COMPACTION_DURATION_MS = 300_000L
        const val MIN_COMPACTION_SEGMENTS = 2
        const val TEMP_FILE_SUFFIX = ".tmp"
        val METADATA_NAME_REGEX = Regex("""history-(\d+)-pcm-(\d+)\.[^.]+$""")
        val LEGACY_NAME_REGEX = Regex("""history-(\d+)-\d+\.[^.]+$""")

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

private fun MediaFormat.getIntegerOrNull(key: String): Int? {
    return if (containsKey(key)) getInteger(key) else null
}

private fun MediaFormat.getLongOrNull(key: String): Long? {
    return if (containsKey(key)) getLong(key) else null
}
