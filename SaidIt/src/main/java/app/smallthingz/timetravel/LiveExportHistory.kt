package app.smallthingz.timetravel

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class ParsedAdtsHeader(
    val profile: Int,
    val sampleRate: Int,
    val channelCount: Int,
)

internal fun parseAdtsHeader(
    header: ByteArray,
    size: Int = header.size,
): ParsedAdtsHeader? {
    if (size < 7) return null
    val byte0 = header[0].toInt() and 0xFF
    val byte1 = header[1].toInt() and 0xFF
    val byte2 = header[2].toInt() and 0xFF
    val byte3 = header[3].toInt() and 0xFF
    if (byte0 != 0xFF || (byte1 and 0xF0) != 0xF0) return null
    val profile = ((byte2 shr 6) and 0x03) + 1
    val sampleRate = ADTS_SAMPLE_RATES_BY_INDEX[(byte2 shr 2) and 0x0F] ?: return null
    val channelCount = ((byte2 and 0x01) shl 2) or ((byte3 shr 6) and 0x03)
    if (channelCount <= 0) return null
    return ParsedAdtsHeader(profile = profile, sampleRate = sampleRate, channelCount = channelCount)
}

internal fun detectRawAmrCodec(
    header: ByteArray,
    size: Int = header.size,
): ExportCodec? {
    if (size <= 0) return null
    val value = String(header, 0, size, Charsets.US_ASCII)
    return when {
        value.startsWith("#!AMR-WB\n") -> ExportCodec.AMR_WB
        value.startsWith("#!AMR\n") -> ExportCodec.AMR_NB
        else -> null
    }
}

private const val ADTS_LC_PROFILE = 2
private val ADTS_SAMPLE_RATES_BY_INDEX =
    mapOf(
        0 to 96_000,
        1 to 88_200,
        2 to 64_000,
        3 to 48_000,
        4 to 44_100,
        5 to 32_000,
        6 to 24_000,
        7 to 22_050,
        8 to 16_000,
        9 to 12_000,
        10 to 11_025,
        11 to 8_000,
        12 to 7_350,
    )

internal class LiveExportHistory(
    private val context: Context,
) {
    private val historyRoot = File(File(context.noBackupFilesDir, TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME), "live-export-history")
    private val legacyCacheRoot = File(File(context.cacheDir, TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME), "live-export-history")
    private val segments = ArrayDeque<Segment>()
    private val pinnedFiles = LinkedHashMap<String, Int>()
    private val debugOperations = LinkedHashMap<String, DebugOperation>()
    private val recentDebugOperations = ArrayDeque<RecentDebugOperation>()

    private var config: Config? = null
    private var retentionBytes = 0L
    private var segmentDurationMillis = DEFAULT_SEGMENT_DURATION_MS
    private var compactionTargetSampleBytes: Long? = null
    private var eagerAutoMergeEnabled = true
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
        compactionTargetSampleBytes: Long?,
        eagerAutoMergeEnabled: Boolean,
    ) {
        val updatedConfig = Config(format, codec, sampleRate, channelCount, bitrateKbps)
        val previousConfig = config
        val configChanged = previousConfig != updatedConfig
        if (previousConfig != null && configChanged) {
            closeCurrentSegmentLocked()
        }
        val retentionChanged = this.retentionBytes != retentionBytes
        val segmentDurationChanged = this.segmentDurationMillis != segmentDurationMillis
        val compactionChanged = this.compactionTargetSampleBytes != compactionTargetSampleBytes
        config = updatedConfig
        this.retentionBytes = retentionBytes
        this.segmentDurationMillis = segmentDurationMillis.coerceIn(MIN_SEGMENT_DURATION_MS, MAX_SEGMENT_DURATION_MS)
        this.compactionTargetSampleBytes = compactionTargetSampleBytes?.coerceAtLeast(1L)
        this.eagerAutoMergeEnabled = eagerAutoMergeEnabled
        if (previousConfig == null) {
            migrateLegacySegmentsLocked()
            restorePersistedSegmentsLocked(updatedConfig)
            pruneLocked()
        } else if (configChanged || retentionChanged || segmentDurationChanged || compactionChanged) {
            pruneLocked()
        }
    }

    @Synchronized
    fun append(
        array: ByteArray,
        offset: Int,
        count: Int,
        endedAtMillis: Long,
    ): Boolean {
        if (count <= 0) {
            return false
        }
        val currentConfig = config ?: return false
        ensureWriterLocked(
            startedAtMillis = nextSegmentStartMillis ?: (endedAtMillis - currentConfig.bytesToDurationMillis(count.toLong())).coerceAtLeast(0L),
        )
        val writer = currentWriter ?: return false
        writer.write(array, offset, count)
        currentSegment?.sampleBytes = writer.totalSampleBytesWritten.toLong()
        val current = currentSegment
        if (current != null && currentConfig.bytesToDurationMillis(current.sampleBytes) >= segmentDurationMillis) {
            nextSegmentStartMillis = current.startedAtMillis + currentConfig.bytesToDurationMillis(current.sampleBytes)
            closeCurrentSegmentLocked()
            return true
        }
        return false
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
    fun countRetainedSampleBytes(): Long {
        return segments.sumOf { it.sampleBytes } + (currentWriter?.totalSampleBytesWritten?.toLong() ?: 0L)
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
    fun replaceWithImportedSegments(
        expectedConfig: Config,
        importedSegments: List<ImportedSegment>,
    ): Boolean {
        if (config != expectedConfig || currentWriter != null || currentSegment != null) {
            return false
        }

        resetLocked()
        importedSegments
            .sortedBy { it.startedAtMillis }
            .forEach { imported ->
                val segment = Segment(
                    file = imported.file,
                    startedAtMillis = imported.startedAtMillis,
                    sampleBytes = imported.sampleBytes,
                )
                finalizeSegmentFileLocked(segment)
                if (segment.sampleBytes > 0L && segment.file.isFile && segment.file.length() > 0L) {
                    segments.addLast(segment)
                } else {
                    segment.file.delete()
                }
            }
        nextSegmentStartMillis = segments.lastOrNull()?.let { segment ->
            expectedConfig.bytesToDurationMillis(segment.sampleBytes) + segment.startedAtMillis
        }
        pruneLocked()
        return true
    }

    @Synchronized
    fun snapshotForHistoryReencode(
        expectedConfig: Config,
        reopenForContinuedCapture: Boolean,
    ): HistoryReencodeSourceSnapshot? {
        val currentConfig = config ?: return null
        if (currentWriter != null) {
            currentSegment?.let { segment ->
                nextSegmentStartMillis = segment.startedAtMillis + currentConfig.bytesToDurationMillis(segment.sampleBytes)
            }
            closeCurrentSegmentLocked()
        }
        if (segments.isEmpty()) {
            if (reopenForContinuedCapture) {
                ensureWriterLocked(nextSegmentStartMillis ?: System.currentTimeMillis())
            }
            return null
        }
        val sourceRanges = buildHistoryReencodeRangesLocked(expectedConfig)
        if (sourceRanges.isEmpty()) {
            if (reopenForContinuedCapture) {
                ensureWriterLocked(nextSegmentStartMillis ?: System.currentTimeMillis())
            }
            return null
        }
        val sourcePaths = sourceRanges.flatMap { it.sourcePaths }
        sourcePaths.forEach { path ->
            pinnedFiles[path] = (pinnedFiles[path] ?: 0) + 1
        }
        if (reopenForContinuedCapture) {
            ensureWriterLocked(nextSegmentStartMillis ?: System.currentTimeMillis())
        }
        return HistoryReencodeSourceSnapshot(
            sourceRanges = sourceRanges,
            sourcePaths = sourcePaths,
            totalSampleBytes = sourceRanges.sumOf { it.totalSampleBytes },
        )
    }

    @Synchronized
    fun replaceSourceSegmentsWithImported(
        sourcePaths: List<String>,
        expectedConfig: Config,
        importedSegments: List<ImportedSegment>,
    ): Boolean {
        return replaceSourceSegmentsWithImportedRanges(
            replacements = listOf(HistoryReencodeReplacementRange(sourcePaths, importedSegments)),
            expectedConfig = expectedConfig,
        )
    }

    @Synchronized
    fun replaceSourceSegmentsWithImportedRanges(
        replacements: List<HistoryReencodeReplacementRange>,
        expectedConfig: Config,
    ): Boolean {
        if (replacements.isEmpty()) {
            return true
        }
        val pinnedSourcePaths = replacements.flatMap { it.sourcePaths }
        if (config != expectedConfig) {
            releasePinnedSourcePathsLocked(pinnedSourcePaths)
            return false
        }
        val currentSegments = segments.toList()
        val indexedReplacements = replacements.map { replacement ->
            val startIndex = findContiguousSegmentRangeStartLocked(currentSegments, replacement.sourcePaths)
            if (startIndex < 0) {
                releasePinnedSourcePathsLocked(pinnedSourcePaths)
                return false
            }
            IndexedHistoryReencodeReplacementRange(
                startIndex = startIndex,
                endIndexExclusive = startIndex + replacement.sourcePaths.size,
                replacement = replacement,
            )
        }.sortedBy { it.startIndex }
        if (indexedReplacements.zipWithNext().any { (left, right) -> left.endIndexExclusive > right.startIndex }) {
            releasePinnedSourcePathsLocked(pinnedSourcePaths)
            return false
        }
        val replacementSegments = ArrayDeque<Segment>(
            currentSegments.size - pinnedSourcePaths.size + replacements.sumOf { it.importedSegments.size },
        )
        var sourceCursor = 0
        indexedReplacements.forEach { indexed ->
            currentSegments.subList(sourceCursor, indexed.startIndex).forEach(replacementSegments::addLast)
            indexed.replacement.importedSegments
                .sortedBy { it.startedAtMillis }
                .forEach { imported ->
                val segment = Segment(
                    file = imported.file,
                    startedAtMillis = imported.startedAtMillis,
                    sampleBytes = imported.sampleBytes,
                )
                finalizeSegmentFileLocked(segment)
                if (segment.sampleBytes > 0L && segment.file.isFile && segment.file.length() > 0L) {
                    replacementSegments.addLast(segment)
                } else {
                    segment.file.delete()
                }
            }
            sourceCursor = indexed.endIndexExclusive
        }
        currentSegments.subList(sourceCursor, currentSegments.size).forEach(replacementSegments::addLast)
        segments.clear()
        segments.addAll(replacementSegments)
        releasePinnedSourcePathsLocked(pinnedSourcePaths)
        val livePaths = segments.mapTo(HashSet()) { it.file.absolutePath }
        pinnedSourcePaths.forEach { path ->
            if (path !in livePaths && !pinnedFiles.containsKey(path)) {
                File(path).delete()
            }
        }
        pruneLocked()
        return true
    }

    @Synchronized
    fun releasePinnedSourcePaths(
        sourcePaths: List<String>,
    ) {
        releasePinnedSourcePathsLocked(sourcePaths)
        pruneLocked()
    }

    @Synchronized
    fun debugDeleteChunks(
        filePaths: Collection<String>,
    ): Int {
        if (filePaths.isEmpty()) {
            return 0
        }
        val requestedPaths = filePaths.toHashSet()
        val busyPaths = debugOperations.values.flatMapTo(HashSet()) { it.sourcePaths }
        val keptSegments = ArrayDeque<Segment>(segments.size)
        var deletedCount = 0
        segments.forEach { segment ->
            val path = segment.file.absolutePath
            val shouldDelete = path in requestedPaths && path !in busyPaths && !pinnedFiles.containsKey(path)
            if (shouldDelete && segment.file.delete()) {
                deletedCount++
            } else {
                keptSegments.addLast(segment)
            }
        }
        if (deletedCount <= 0) {
            return 0
        }
        segments.clear()
        segments.addAll(keptSegments)
        pruneLocked()
        return deletedCount
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
        val task = synchronized(this) { claimCompactionTaskLocked(includeExportFallback = false) } ?: return false
        var mergedFile: File? = null
        return try {
            synchronized(this) {
                debugOperations[task.operationId] =
                    DebugOperation(
                        id = task.operationId,
                        kind = DebugOperationKind.BACKGROUND_MERGE,
                        sourcePaths = task.sourcePaths,
                        targetSampleBytes = task.totalSampleBytes,
                        startedAtMillis = task.startedAtMillis,
                    )
            }
            mergeSnapshotBalanced(
                snapshot = task.snapshot,
                outputTarget = task.outputTarget,
                preferredParallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            )
            mergedFile = requireNotNull(task.outputTarget.file)
            true
        } catch (e: Exception) {
            Log.w(TAG, "History compaction failed", e)
            false
        } finally {
            synchronized(this) {
                completeDebugOperationLocked(task.operationId)
                finishCompactionLocked(task, mergedFile)
            }
        }
    }

    @Throws(IOException::class)
    fun exportSnapshotOptimized(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        preferredParallelism: Int = DEFAULT_EXPORT_COMPACTION_PARALLELISM,
        shouldCancel: () -> Boolean = { false },
    ) {
        ensureExportNotCancelled(shouldCancel)
        if (!supportsPreparedExportOptimization(snapshot.config.format)) {
            exportSnapshot(snapshot, outputTarget, shouldCancel)
            return
        }
        val preparedSession = prepareSnapshotForStreamingExport(snapshot, preferredParallelism, shouldCancel)
        if (preparedSession == null) {
            exportSnapshot(snapshot, outputTarget, shouldCancel)
            return
        }
        try {
            ensureExportNotCancelled(shouldCancel)
            exportPreparedSnapshot(snapshot.config, preparedSession.parts, outputTarget, shouldCancel)
        } finally {
            preparedSession.close()
        }
    }

    @Throws(IOException::class)
    fun exportSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean = { false },
    ) {
        ensureExportNotCancelled(shouldCancel)
        if (snapshot.slices.size == 1) {
            val only = snapshot.slices.single()
            if (only.skipSampleBytes == 0L && only.takeSampleBytes == only.segment.sampleBytes && canCopyWholeSliceDirectly(only.segment, snapshot.config)) {
                copyWholeFile(only.segment.file, outputTarget, shouldCancel)
                return
            }
        }

        when {
            snapshot.config.format.isPcmContainer -> exportWaveSnapshot(snapshot, outputTarget, shouldCancel)
            snapshot.config.format.usesMuxer -> exportEncodedSnapshot(snapshot, outputTarget, shouldCancel)
            snapshot.config.format.isRawAacAdts -> exportAdtsSnapshot(snapshot, outputTarget, shouldCancel)
            snapshot.config.format.isRawAmr -> exportRawAmrSnapshot(snapshot, outputTarget, shouldCancel)
            snapshot.config.format.isTransportStream -> exportTransportStreamSnapshot(snapshot, outputTarget, shouldCancel)
            else -> throw IOException("Unsupported history export format ${snapshot.config.format.prefValue}")
        }
    }

    @Throws(IOException::class)
    private fun compactSnapshotRegionForExport(
        snapshot: Snapshot,
        preferredParallelism: Int,
    ): Snapshot {
        val plan = synchronized(this) { buildRegionalCompactionPlanLocked(snapshot, preferredParallelism) } ?: return snapshot
        val completedGroups = runRegionalCompactionJobs(plan)
        if (completedGroups.isEmpty()) {
            return snapshot
        }
        return synchronized(this) {
            installRegionalCompactionResultsLocked(snapshot, plan, completedGroups)
        }
    }

    private fun prepareSnapshotForStreamingExport(
        snapshot: Snapshot,
        preferredParallelism: Int,
        shouldCancel: () -> Boolean,
    ): PreparedExportSession? {
        val plan = synchronized(this) { buildPreparedExportPlanLocked(snapshot) } ?: return null
        val compactedPartCount = plan.parts.count { it is PreparedExportPlanPart.Compacted }
        if (compactedPartCount <= 0) {
            return null
        }
        val parallelism = minOf(compactedPartCount, preferredParallelism, MAX_EXPORT_COMPACTION_PARALLELISM).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(parallelism)
        val preparedParts =
            plan.parts.map { part ->
                when (part) {
                    is PreparedExportPlanPart.Direct -> PreparedExportPart.Direct(part.slice)
                    is PreparedExportPlanPart.Compacted -> PreparedExportPart.Compacted(
                        group = part.group,
                        future = submitPreparedCompactionJob(executor, part.group, shouldCancel),
                    )
                }
            }
        return PreparedExportSession(preparedParts, executor)
    }

    @Synchronized
    private fun buildPreparedExportPlanLocked(snapshot: Snapshot): PreparedExportPlan? {
        val currentConfig = config ?: return null
        if (currentConfig != snapshot.config) {
            return null
        }
        val baseSegmentBytes = baseSegmentSampleBytesLocked(currentConfig)
        val configuredTargetSampleBytes = resolvedCompactionTargetSampleBytesLocked(currentConfig, includeExportFallback = true) ?: return null
        val observedBlockSampleBytes =
            snapshot.slices
                .asSequence()
                .filter { slice -> slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes }
                .map { it.segment.sampleBytes }
                .maxOrNull()
                ?: 0L
        val targetSampleBytes = maxOf(configuredTargetSampleBytes, observedBlockSampleBytes)
        if (targetSampleBytes <= baseSegmentBytes) {
            return null
        }
        ensureHistoryRootExists()

        val parts = ArrayList<PreparedExportPlanPart>(snapshot.slices.size)
        var bufferedSlices = ArrayList<SegmentSlice>()
        var bufferedSampleBytes = 0L
        var bufferedStartIndex = -1

        fun flushBuffered() {
            if (bufferedSlices.isEmpty()) {
                return
            }
            if (bufferedSlices.size >= MIN_COMPACTION_SEGMENTS) {
                parts += PreparedExportPlanPart.Compacted(
                    buildPreparedExportGroupLocked(
                        startSliceIndex = bufferedStartIndex,
                        slices = bufferedSlices.toList(),
                        totalSampleBytes = bufferedSampleBytes,
                        currentConfig = currentConfig,
                    ),
                )
            } else {
                bufferedSlices.forEach { parts += PreparedExportPlanPart.Direct(it) }
            }
            bufferedSlices = ArrayList()
            bufferedSampleBytes = 0L
            bufferedStartIndex = -1
        }

        snapshot.slices.forEachIndexed { index, slice ->
            val eligible =
                slice.skipSampleBytes == 0L &&
                    slice.takeSampleBytes == slice.segment.sampleBytes &&
                    isExportBlockCandidateLocked(slice.segment, targetSampleBytes, baseSegmentBytes)
            if (!eligible) {
                flushBuffered()
                parts += PreparedExportPlanPart.Direct(slice)
                return@forEachIndexed
            }

            if (bufferedSlices.isNotEmpty() && bufferedSampleBytes + slice.takeSampleBytes > targetSampleBytes) {
                flushBuffered()
            }
            if (bufferedSlices.isEmpty()) {
                bufferedStartIndex = index
            }
            bufferedSlices += slice.copy()
            bufferedSampleBytes += slice.takeSampleBytes
            if (bufferedSampleBytes == targetSampleBytes) {
                flushBuffered()
            }
        }
        flushBuffered()

        return if (parts.any { it is PreparedExportPlanPart.Compacted }) {
            PreparedExportPlan(parts)
        } else {
            null
        }
    }

    private fun buildPreparedExportGroupLocked(
        startSliceIndex: Int,
        slices: List<SegmentSlice>,
        totalSampleBytes: Long,
        currentConfig: Config,
    ): RegionalCompactionGroup {
        val startedAtMillis = slices.first().segment.startedAtMillis
        val tempFile = File(historyRoot, "export-compact-$startedAtMillis-${System.nanoTime()}.${currentConfig.format.extension}.tmp")
        return RegionalCompactionGroup(
            operationId = "export-$startedAtMillis-${System.nanoTime()}",
            startSliceIndex = startSliceIndex,
            endSliceIndexExclusive = startSliceIndex + slices.size,
            sourcePaths = slices.map { it.segment.file.absolutePath },
            startedAtMillis = startedAtMillis,
            totalSampleBytes = totalSampleBytes,
            snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
            outputTarget = RecordingOutputTarget(
                id = tempFile.absolutePath,
                displayName = tempFile.name,
                mimeType = currentConfig.format.outputMimeType,
                storageType = RecordingStorageType.FILE,
                directoryId = historyRoot.absolutePath,
                startedAtMillis = startedAtMillis,
                file = tempFile,
            ),
        )
    }

    private fun submitPreparedCompactionJob(
        executor: java.util.concurrent.ExecutorService,
        group: RegionalCompactionGroup,
        shouldCancel: () -> Boolean,
    ): Future<CompletedRegionalCompaction?> {
        synchronized(this) {
            debugOperations[group.operationId] =
                DebugOperation(
                    id = group.operationId,
                    kind = DebugOperationKind.EXPORT_MERGE,
                    sourcePaths = group.sourcePaths,
                    targetSampleBytes = group.totalSampleBytes,
                    startedAtMillis = group.startedAtMillis,
                )
        }
        return executor.submit(
            Callable {
                try {
                    mergeSnapshotBalanced(
                        snapshot = group.snapshot,
                        outputTarget = group.outputTarget,
                        preferredParallelism = group.snapshot.slices.size / 2,
                        shouldCancel = shouldCancel,
                    )
                    val file = group.outputTarget.file
                    if (file == null || !file.isFile || file.length() <= 0L) {
                        null
                    } else {
                        CompletedRegionalCompaction(group, file)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Prepared export compaction failed", e)
                    group.outputTarget.file?.delete()
                    null
                } finally {
                    synchronized(this@LiveExportHistory) {
                        completeDebugOperationLocked(group.operationId)
                    }
                }
            },
        )
    }

    @Throws(IOException::class)
    private fun exportPreparedSnapshot(
        currentConfig: Config,
        parts: List<PreparedExportPart>,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        when {
            currentConfig.format.isPcmContainer -> exportPreparedWaveSnapshot(currentConfig, parts, outputTarget, shouldCancel)
            currentConfig.format.usesMuxer -> exportPreparedEncodedSnapshot(currentConfig, parts, outputTarget, shouldCancel)
            currentConfig.format.isRawAacAdts -> exportPreparedAdtsSnapshot(currentConfig, parts, outputTarget, shouldCancel)
            currentConfig.format.isRawAmr -> exportPreparedRawAmrSnapshot(currentConfig, parts, outputTarget, shouldCancel)
            currentConfig.format.isTransportStream -> exportPreparedTransportStreamSnapshot(currentConfig, parts, outputTarget, shouldCancel)
            else -> throw IOException("Unsupported history export format ${currentConfig.format.prefValue}")
        }
    }

    private fun exportPreparedWaveSnapshot(
        currentConfig: Config,
        parts: List<PreparedExportPart>,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        WavAudioFileWriter(context, outputTarget, currentConfig.sampleRate, currentConfig.channelCount).use { writer ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            forEachPreparedExportSlice(parts, shouldCancel) { prepared ->
                ensureExportNotCancelled(shouldCancel)
                RandomAccessFile(prepared.slice.segment.file, "r").use { input ->
                    input.seek(WAV_HEADER_BYTES + prepared.slice.skipSampleBytes)
                    var remaining = prepared.slice.takeSampleBytes
                    while (remaining > 0L) {
                        ensureExportNotCancelled(shouldCancel)
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

    private fun exportPreparedEncodedSnapshot(
        currentConfig: Config,
        parts: List<PreparedExportPart>,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        val parcelFileDescriptor: ParcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        val bufferInfo = MediaCodec.BufferInfo()
        val byteBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
        var outputBaseTimeUs = 0L

        try {
            muxer = MediaMuxer(parcelFileDescriptor.fileDescriptor, requireNotNull(currentConfig.format.muxerOutputFormat))
            forEachPreparedExportSlice(parts, shouldCancel) { prepared ->
                ensureExportNotCancelled(shouldCancel)
                val slice = prepared.slice
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(slice.segment.file.absolutePath)
                    val extractorTrackIndex = findAudioTrack(extractor)
                    if (extractorTrackIndex < 0) {
                        return@forEachPreparedExportSlice
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
                        ensureExportNotCancelled(shouldCancel)
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
                            mediaCodecBufferFlagsForExtractorSample(extractor.sampleFlags),
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

    private fun exportPreparedAdtsSnapshot(
        currentConfig: Config,
        parts: List<PreparedExportPart>,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        require(currentConfig.codec == ExportCodec.AAC_LC) { "ADTS snapshot export supports AAC-LC only" }
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), COPY_BUFFER_BYTES)
        val adtsHeader = ByteArray(7)
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            forEachPreparedExportSlice(parts, shouldCancel) { prepared ->
                ensureExportNotCancelled(shouldCancel)
                val slice = prepared.slice
                if (slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes && canCopyWholeSliceDirectly(slice.segment, currentConfig)) {
                    copyWholeFileToStream(slice.segment.file, outputStream, scratch, shouldCancel = shouldCancel)
                } else {
                    exportEncodedSliceBuffers(currentConfig, slice, shouldCancel) { _: SegmentSlice, data: ByteBuffer, sampleSize: Int, _: Long ->
                        fillAdtsHeader(adtsHeader, currentConfig.sampleRate, currentConfig.channelCount, sampleSize)
                        outputStream.write(adtsHeader, 0, adtsHeader.size)
                        writeByteBufferToStream(data, sampleSize, outputStream, scratch)
                    }
                }
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun exportPreparedRawAmrSnapshot(
        currentConfig: Config,
        parts: List<PreparedExportPart>,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), COPY_BUFFER_BYTES)
        val header = rawAmrMagicHeader(currentConfig.codec)
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            outputStream.write(header)
            forEachPreparedExportSlice(parts, shouldCancel) { prepared ->
                ensureExportNotCancelled(shouldCancel)
                val slice = prepared.slice
                if (slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes && canCopyWholeSliceDirectly(slice.segment, currentConfig)) {
                    copyWholeFileToStream(slice.segment.file, outputStream, scratch, skipBytes = header.size.toLong(), shouldCancel = shouldCancel)
                } else {
                    exportEncodedSliceBuffers(currentConfig, slice, shouldCancel) { _: SegmentSlice, data: ByteBuffer, sampleSize: Int, _: Long ->
                        writeByteBufferToStream(data, sampleSize, outputStream, scratch)
                    }
                }
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun exportPreparedTransportStreamSnapshot(
        currentConfig: Config,
        parts: List<PreparedExportPart>,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        require(currentConfig.codec == ExportCodec.AAC_LC) { "TS snapshot export supports AAC-LC only" }
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), COPY_BUFFER_BYTES)
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            var packetizer: MpegTsAacPacketizer? = null
            forEachPreparedExportSlice(parts, shouldCancel) { prepared ->
                ensureExportNotCancelled(shouldCancel)
                val slice = prepared.slice
                if (slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes && canCopyWholeSliceDirectly(slice.segment, currentConfig)) {
                    copyWholeFileToStream(slice.segment.file, outputStream, scratch, shouldCancel = shouldCancel)
                } else {
                    val activePacketizer =
                        packetizer ?: MpegTsAacPacketizer(outputStream, currentConfig.sampleRate, currentConfig.channelCount).also {
                            packetizer = it
                        }
                    exportEncodedSliceBuffers(currentConfig, slice, shouldCancel) { _: SegmentSlice, data: ByteBuffer, sampleSize: Int, presentationTimeUs: Long ->
                        activePacketizer.writeAccessUnit(data, sampleSize, presentationTimeUs)
                    }
                }
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun forEachPreparedExportSlice(
        parts: List<PreparedExportPart>,
        shouldCancel: () -> Boolean,
        consumer: (PreparedSegmentSlice) -> Unit,
    ) {
        parts.forEach { part ->
            ensureExportNotCancelled(shouldCancel)
            val resolved =
                when (part) {
                    is PreparedExportPart.Direct -> listOf(PreparedSegmentSlice(part.slice, deleteAfterUse = false))
                    is PreparedExportPart.Compacted -> {
                        val completed = awaitPreparedCompaction(part.future, shouldCancel)
                        if (completed != null) {
                            listOf(
                                PreparedSegmentSlice(
                                    slice = SegmentSlice(
                                        segment = Segment(
                                            file = completed.file,
                                            startedAtMillis = completed.group.startedAtMillis,
                                            sampleBytes = completed.group.totalSampleBytes,
                                        ),
                                        skipSampleBytes = 0L,
                                        takeSampleBytes = completed.group.totalSampleBytes,
                                    ),
                                    deleteAfterUse = true,
                                ),
                            )
                        } else {
                            part.group.snapshot.slices.map { PreparedSegmentSlice(it, deleteAfterUse = false) }
                        }
                    }
                }
            resolved.forEach { prepared ->
                try {
                    consumer(prepared)
                } finally {
                    if (prepared.deleteAfterUse) {
                        prepared.slice.segment.file.delete()
                    }
                }
            }
        }
    }

    private fun exportWaveSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        WavAudioFileWriter(context, outputTarget, snapshot.config.sampleRate, snapshot.config.channelCount).use { writer ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            snapshot.slices.forEach { slice ->
                ensureExportNotCancelled(shouldCancel)
                RandomAccessFile(slice.segment.file, "r").use { input ->
                    input.seek(WAV_HEADER_BYTES + slice.skipSampleBytes)
                    var remaining = slice.takeSampleBytes
                    while (remaining > 0L) {
                        ensureExportNotCancelled(shouldCancel)
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
        shouldCancel: () -> Boolean,
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
                ensureExportNotCancelled(shouldCancel)
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
                        ensureExportNotCancelled(shouldCancel)
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
                            mediaCodecBufferFlagsForExtractorSample(extractor.sampleFlags),
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

    private fun exportAdtsSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        val currentConfig = snapshot.config
        require(currentConfig.codec == ExportCodec.AAC_LC) { "ADTS snapshot export supports AAC-LC only" }
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), COPY_BUFFER_BYTES)
        val adtsHeader = ByteArray(7)
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            snapshot.slices.forEach { slice ->
                ensureExportNotCancelled(shouldCancel)
                if (slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes && canCopyWholeSliceDirectly(slice.segment, currentConfig)) {
                    copyWholeFileToStream(slice.segment.file, outputStream, scratch, shouldCancel = shouldCancel)
                } else {
                    exportEncodedSliceBuffers(currentConfig, slice, shouldCancel) { _: SegmentSlice, data: ByteBuffer, sampleSize: Int, _: Long ->
                        fillAdtsHeader(adtsHeader, currentConfig.sampleRate, currentConfig.channelCount, sampleSize)
                        outputStream.write(adtsHeader, 0, adtsHeader.size)
                        writeByteBufferToStream(data, sampleSize, outputStream, scratch)
                    }
                }
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun exportRawAmrSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        val currentConfig = snapshot.config
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), COPY_BUFFER_BYTES)
        val header = rawAmrMagicHeader(currentConfig.codec)
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            outputStream.write(header)
            snapshot.slices.forEach { slice ->
                ensureExportNotCancelled(shouldCancel)
                if (slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes && canCopyWholeSliceDirectly(slice.segment, currentConfig)) {
                    copyWholeFileToStream(slice.segment.file, outputStream, scratch, skipBytes = header.size.toLong(), shouldCancel = shouldCancel)
                } else {
                    exportEncodedSliceBuffers(currentConfig, slice, shouldCancel) { _: SegmentSlice, data: ByteBuffer, sampleSize: Int, _: Long ->
                        writeByteBufferToStream(data, sampleSize, outputStream, scratch)
                    }
                }
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun exportTransportStreamSnapshot(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean,
    ) {
        val currentConfig = snapshot.config
        require(currentConfig.codec == ExportCodec.AAC_LC) { "TS snapshot export supports AAC-LC only" }
        val parcelFileDescriptor = openWritableParcelFileDescriptor(context, outputTarget)
        val outputStream = BufferedOutputStream(FileOutputStream(parcelFileDescriptor.fileDescriptor), COPY_BUFFER_BYTES)
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        try {
            val packetizer = MpegTsAacPacketizer(outputStream, currentConfig.sampleRate, currentConfig.channelCount)
            forEachEncodedSliceBuffer(snapshot, shouldCancel) { _: SegmentSlice, data: ByteBuffer, sampleSize: Int, presentationTimeUs: Long ->
                packetizer.writeAccessUnit(data, sampleSize, presentationTimeUs)
            }
            outputStream.flush()
        } finally {
            runCatching { outputStream.close() }
            runCatching { parcelFileDescriptor.close() }
        }
    }

    private fun exportEncodedSliceBuffers(
        currentConfig: Config,
        slice: SegmentSlice,
        shouldCancel: () -> Boolean,
        consumer: (SegmentSlice, ByteBuffer, Int, Long) -> Unit,
    ) {
        val byteBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(slice.segment.file.absolutePath)
            val extractorTrackIndex = findAudioTrack(extractor)
            if (extractorTrackIndex < 0) {
                return
            }
            extractor.selectTrack(extractorTrackIndex)

            val sliceStartUs = currentConfig.bytesToDurationUs(slice.skipSampleBytes)
            val sliceDurationUs = currentConfig.bytesToDurationUs(slice.takeSampleBytes)
            val sliceEndUs = sliceStartUs + sliceDurationUs
            extractor.seekTo(sliceStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var firstWrittenSampleTimeUs = Long.MIN_VALUE
            while (true) {
                ensureExportNotCancelled(shouldCancel)
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
                byteBuffer.flip()
                consumer(slice, byteBuffer, sampleSize, (sampleTimeUs - firstWrittenSampleTimeUs).coerceAtLeast(0L))
                if (!extractor.advance()) {
                    break
                }
            }
        } finally {
            extractor.release()
        }
    }

    private fun forEachEncodedSliceBuffer(
        snapshot: Snapshot,
        shouldCancel: () -> Boolean,
        consumer: (SegmentSlice, ByteBuffer, Int, Long) -> Unit,
    ) {
        val currentConfig = snapshot.config
        val byteBuffer = ByteBuffer.allocateDirect(COPY_BUFFER_BYTES)
        var outputBaseTimeUs = 0L
        snapshot.slices.forEach { slice ->
            ensureExportNotCancelled(shouldCancel)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(slice.segment.file.absolutePath)
                val extractorTrackIndex = findAudioTrack(extractor)
                if (extractorTrackIndex < 0) {
                    return@forEach
                }
                extractor.selectTrack(extractorTrackIndex)

                val sliceStartUs = currentConfig.bytesToDurationUs(slice.skipSampleBytes)
                val sliceDurationUs = currentConfig.bytesToDurationUs(slice.takeSampleBytes)
                val sliceEndUs = sliceStartUs + sliceDurationUs
                extractor.seekTo(sliceStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                var firstWrittenSampleTimeUs = Long.MIN_VALUE
                while (true) {
                    ensureExportNotCancelled(shouldCancel)
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
                    byteBuffer.flip()
                    consumer(slice, byteBuffer, sampleSize, outputBaseTimeUs + (sampleTimeUs - firstWrittenSampleTimeUs).coerceAtLeast(0L))
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
    }

    private fun copyWholeFile(
        source: File,
        outputTarget: RecordingOutputTarget,
        shouldCancel: () -> Boolean = { false },
    ) {
        val scratch = ByteArray(COPY_BUFFER_BYTES)
        when (outputTarget.storageType) {
            RecordingStorageType.FILE -> {
                FileOutputStream(requireNotNull(outputTarget.file)).use { output ->
                    source.inputStream().use { input ->
                        while (true) {
                            ensureExportNotCancelled(shouldCancel)
                            val read = input.read(scratch)
                            if (read <= 0) break
                            output.write(scratch, 0, read)
                        }
                    }
                }
            }

            RecordingStorageType.DOCUMENT -> {
                context.contentResolver.openOutputStream(requireNotNull(outputTarget.uri), "w")?.use { output ->
                    source.inputStream().use { input ->
                        while (true) {
                            ensureExportNotCancelled(shouldCancel)
                            val read = input.read(scratch)
                            if (read <= 0) break
                            output.write(scratch, 0, read)
                        }
                    }
                } ?: throw IOException("Unable to open export output stream")
            }
        }
    }

    private fun copyWholeFileToStream(
        source: File,
        outputStream: BufferedOutputStream,
        scratch: ByteArray,
        skipBytes: Long = 0L,
        shouldCancel: () -> Boolean = { false },
    ) {
        RandomAccessFile(source, "r").use { input ->
            if (skipBytes > 0L) {
                input.seek(skipBytes)
            }
            while (true) {
                ensureExportNotCancelled(shouldCancel)
                val read = input.read(scratch)
                if (read <= 0) {
                    break
                }
                outputStream.write(scratch, 0, read)
            }
        }
    }

    private fun rawAmrMagicHeader(codec: ExportCodec): ByteArray {
        return when (codec) {
            ExportCodec.AMR_WB -> AMR_WB_MAGIC_HEADER
            ExportCodec.AMR_NB -> AMR_NB_MAGIC_HEADER
            else -> throw IOException("Raw AMR export requires AMR codec")
        }
    }

    @Throws(IOException::class)
    private fun mergeSnapshotBalanced(
        snapshot: Snapshot,
        outputTarget: RecordingOutputTarget,
        preferredParallelism: Int,
        shouldCancel: () -> Boolean = { false },
    ) {
        ensureExportNotCancelled(shouldCancel)
        ensureHistoryRootExists()
        val fullSlices = snapshot.slices.all { it.skipSampleBytes == 0L && it.takeSampleBytes == it.segment.sampleBytes }
        if (!fullSlices || snapshot.slices.size < MIN_COMPACTION_SEGMENTS) {
            exportSnapshot(snapshot, outputTarget, shouldCancel)
            return
        }

        val parallelism = minOf(preferredParallelism.coerceAtLeast(1), MAX_EXPORT_COMPACTION_PARALLELISM)
        if (parallelism <= 1) {
            exportSnapshot(snapshot, outputTarget, shouldCancel)
            return
        }

        val executor = Executors.newFixedThreadPool(parallelism)
        val tempFiles = ArrayList<File>()
        var tempOutputInstalled = false
        try {
            var level =
                snapshot.slices.map { slice ->
                    MergeLevelNode(
                        segment = slice.segment,
                    )
                }
            while (level.size > 1) {
                ensureExportNotCancelled(shouldCancel)
                if (level.size == 2) {
                    exportSnapshot(buildMergeSnapshot(snapshot.config, level[0], level[1]), outputTarget, shouldCancel)
                    tempOutputInstalled = true
                    return
                }

                val nextLevel = arrayOfNulls<MergeLevelNode>((level.size + 1) / 2)
                val futures = ArrayList<Future<Pair<Int, MergeLevelNode>>>(level.size / 2)
                var index = 0
                while (index < level.size) {
                    val slot = index / 2
                    val left = level[index]
                    val right = level.getOrNull(index + 1)
                    if (right == null) {
                        nextLevel[slot] = left
                        index += 1
                        continue
                    }

                    val totalSampleBytes = left.segment.sampleBytes + right.segment.sampleBytes
                    val startedAtMillis = left.segment.startedAtMillis
                    val tempFile = File(historyRoot, "pair-compact-$startedAtMillis-${System.nanoTime()}.${snapshot.config.format.extension}.tmp")
                    tempFiles += tempFile
                    val tempTarget = RecordingOutputTarget(
                        id = tempFile.absolutePath,
                        displayName = tempFile.name,
                        mimeType = snapshot.config.format.outputMimeType,
                        storageType = RecordingStorageType.FILE,
                        directoryId = historyRoot.absolutePath,
                        startedAtMillis = startedAtMillis,
                        file = tempFile,
                    )
                    futures += executor.submit(
                        Callable {
                            ensureExportNotCancelled(shouldCancel)
                            exportSnapshot(buildMergeSnapshot(snapshot.config, left, right), tempTarget, shouldCancel)
                            ensureExportNotCancelled(shouldCancel)
                            Pair(
                                slot,
                                MergeLevelNode(
                                    segment = Segment(
                                        file = tempFile,
                                        startedAtMillis = startedAtMillis,
                                        sampleBytes = totalSampleBytes,
                                    ),
                                ),
                            )
                        },
                    )
                    index += 2
                }

                futures.forEach { future ->
                    val (slot, mergedNode) =
                        try {
                            future.get()
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw InterruptedIOException("Export cancelled")
                        }
                    nextLevel[slot] = mergedNode
                }
                level = nextLevel.filterNotNull()
            }

            val finalNode = level.singleOrNull()
            if (finalNode != null) {
                copyWholeFile(finalNode.segment.file, outputTarget, shouldCancel)
                tempOutputInstalled = true
            }
        } finally {
            executor.shutdownNow()
            if (!tempOutputInstalled) {
                outputTarget.file?.takeIf { it.isFile && it.length() == 0L }?.delete()
            }
            tempFiles.forEach { file ->
                if (file != outputTarget.file) {
                    file.delete()
                }
            }
        }
    }

    private fun buildMergeSnapshot(
        config: Config,
        left: MergeLevelNode,
        right: MergeLevelNode,
    ): Snapshot {
        val slices =
            listOf(
                SegmentSlice(left.segment, 0L, left.segment.sampleBytes),
                SegmentSlice(right.segment, 0L, right.segment.sampleBytes),
            )
        val totalSampleBytes = slices.sumOf { it.takeSampleBytes }
        return Snapshot(config = config, slices = slices, requestedSampleBytes = totalSampleBytes)
    }

    @Throws(InterruptedIOException::class)
    private fun ensureExportNotCancelled(shouldCancel: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || shouldCancel()) {
            throw InterruptedIOException("Export cancelled")
        }
    }

    private fun awaitPreparedCompaction(
        future: Future<CompletedRegionalCompaction?>,
        shouldCancel: () -> Boolean,
    ): CompletedRegionalCompaction? {
        while (true) {
            ensureExportNotCancelled(shouldCancel)
            try {
                return future.get(25L, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                Unit
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Export cancelled")
            } catch (e: Exception) {
                Log.w(TAG, "Prepared export compaction wait failed", e)
                return null
            }
        }
    }

    private fun mediaCodecBufferFlagsForExtractorSample(sampleFlags: Int): Int {
        var flags = 0
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return flags
    }

    private fun canCopyWholeSliceDirectly(
        segment: Segment,
        currentConfig: Config,
    ): Boolean {
        return inspectPersistedSegment(segment.file, currentConfig) != null
    }

    private fun supportsPreparedExportOptimization(format: ExportFormat): Boolean {
        return when (format) {
            ExportFormat.THREE_GPP,
            ExportFormat.AMR_NB_FILE,
            ExportFormat.AMR_WB_FILE,
            ExportFormat.MPEG_2_TS,
                -> false
            else -> true
        }
    }

    private fun ensureWriterLocked(startedAtMillis: Long) {
        if (currentWriter != null) {
            return
        }
        val currentConfig = config ?: return
        ensureHistoryRootExists()
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
                currentConfig.format.usesMuxer -> EncodedAudioFileWriter(
                    context = context,
                    target = target,
                    outputFormat = currentConfig.format,
                    codecConfig = currentConfig.codec,
                    sampleRate = currentConfig.sampleRate,
                    channelCount = currentConfig.channelCount,
                    bitrateKbps = currentConfig.bitrateKbps,
                )
                currentConfig.format.isRawAacAdts -> AdtsAudioFileWriter(
                    context = context,
                    target = target,
                    codecConfig = currentConfig.codec,
                    configuredSampleRate = currentConfig.sampleRate,
                    configuredChannelCount = currentConfig.channelCount,
                    bitrateKbps = currentConfig.bitrateKbps,
                )
                currentConfig.format.isRawAmr -> RawAmrAudioFileWriter(
                    context = context,
                    target = target,
                    codecConfig = currentConfig.codec,
                    configuredSampleRate = currentConfig.sampleRate,
                    configuredChannelCount = currentConfig.channelCount,
                    bitrateKbps = currentConfig.bitrateKbps,
                )
                currentConfig.format.isTransportStream -> TsAudioFileWriter(
                    context = context,
                    target = target,
                    codecConfig = currentConfig.codec,
                    configuredSampleRate = currentConfig.sampleRate,
                    configuredChannelCount = currentConfig.channelCount,
                    bitrateKbps = currentConfig.bitrateKbps,
                )
                else -> throw IOException("Unsupported history format ${currentConfig.format.prefValue}")
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

    private fun claimCompactionTaskLocked(includeExportFallback: Boolean): CompactionTask? {
        if (compactionInFlight) {
            return null
        }
        val currentConfig = config ?: return null
        val baseSegmentBytes = baseSegmentSampleBytesLocked(currentConfig)
        val targetSampleBytes = resolvedCompactionTargetSampleBytesLocked(currentConfig, includeExportFallback = includeExportFallback) ?: return null
        if (targetSampleBytes <= baseSegmentBytes) {
            return null
        }
        val allowTailRemainder = includeExportFallback || eagerAutoMergeEnabled

        val currentSegments = segments.toList()
        for (startIndex in currentSegments.indices) {
            val selectedSegments = ArrayList<Segment>()
            var totalSampleBytes = 0L
            for (index in startIndex until currentSegments.size) {
                val segment = currentSegments[index]
                if (!isCompactionCandidateLocked(segment, targetSampleBytes, baseSegmentBytes)) {
                    break
                }
                val nextTotal = totalSampleBytes + segment.sampleBytes
                if (nextTotal > targetSampleBytes && selectedSegments.size >= MIN_COMPACTION_SEGMENTS) {
                    val slices = selectedSegments.map { selected ->
                        val key = selected.file.absolutePath
                        pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
                        SegmentSlice(selected, 0L, selected.sampleBytes)
                    }
                    ensureHistoryRootExists()
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
                        operationId = "bg-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}",
                        startIndex = startIndex,
                        sourcePaths = selectedSegments.map { it.file.absolutePath },
                        startedAtMillis = selectedSegments.first().startedAtMillis,
                        totalSampleBytes = totalSampleBytes,
                        snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
                        outputTarget = outputTarget,
                    )
                } else if (nextTotal > targetSampleBytes) {
                    break
                }
                selectedSegments += segment
                totalSampleBytes = nextTotal
                val reachedEnd = index == currentSegments.lastIndex
                if (shouldCompactSelection(totalSampleBytes, targetSampleBytes, baseSegmentBytes, selectedSegments.size, reachedEnd, allowTailRemainder)) {
                    val slices = selectedSegments.map { selected ->
                        val key = selected.file.absolutePath
                        pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
                        SegmentSlice(selected, 0L, selected.sampleBytes)
                    }
                    ensureHistoryRootExists()
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
                        operationId = "bg-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}",
                        startIndex = startIndex,
                        sourcePaths = selectedSegments.map { it.file.absolutePath },
                        startedAtMillis = selectedSegments.first().startedAtMillis,
                        totalSampleBytes = totalSampleBytes,
                        snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
                        outputTarget = outputTarget,
                    )
                }
            }
        }
        return null
    }

    private fun claimCompactionTaskAroundChunkLocked(filePath: String): CompactionTask? {
        if (compactionInFlight) {
            return null
        }
        val currentConfig = config ?: return null
        val baseSegmentBytes = baseSegmentSampleBytesLocked(currentConfig)
        val targetSampleBytes = resolvedCompactionTargetSampleBytesLocked(currentConfig, includeExportFallback = true) ?: return null
        if (targetSampleBytes <= baseSegmentBytes) {
            return null
        }

        val currentSegments = segments.toList()
        val selectedIndex = currentSegments.indexOfFirst { it.file.absolutePath == filePath }
        if (selectedIndex < 0) {
            return null
        }

        for (startIndex in 0..selectedIndex) {
            val selectedSegments = ArrayList<Segment>()
            var totalSampleBytes = 0L
            var includesSelected = false
            for (index in startIndex until currentSegments.size) {
                val segment = currentSegments[index]
                if (!isCompactionCandidateLocked(segment, targetSampleBytes, baseSegmentBytes)) {
                    break
                }
                val nextTotal = totalSampleBytes + segment.sampleBytes
                if (nextTotal > targetSampleBytes && includesSelected && selectedSegments.size >= MIN_COMPACTION_SEGMENTS) {
                    val slices = selectedSegments.map { selected ->
                        val key = selected.file.absolutePath
                        pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
                        SegmentSlice(selected, 0L, selected.sampleBytes)
                    }
                    ensureHistoryRootExists()
                    val tempFile = File(historyRoot, "manual-compact-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}.${currentConfig.format.extension}.tmp")
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
                        operationId = "manual-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}",
                        startIndex = startIndex,
                        sourcePaths = selectedSegments.map { it.file.absolutePath },
                        startedAtMillis = selectedSegments.first().startedAtMillis,
                        totalSampleBytes = totalSampleBytes,
                        snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
                        outputTarget = outputTarget,
                    )
                } else if (nextTotal > targetSampleBytes) {
                    break
                }
                selectedSegments += segment
                totalSampleBytes = nextTotal
                includesSelected = includesSelected || index == selectedIndex
                if (!includesSelected) {
                    continue
                }
                val reachedEnd = index == currentSegments.lastIndex
                if (shouldCompactSelection(totalSampleBytes, targetSampleBytes, baseSegmentBytes, selectedSegments.size, reachedEnd, allowTailRemainder = true)) {
                    val slices = selectedSegments.map { selected ->
                        val key = selected.file.absolutePath
                        pinnedFiles[key] = (pinnedFiles[key] ?: 0) + 1
                        SegmentSlice(selected, 0L, selected.sampleBytes)
                    }
                    ensureHistoryRootExists()
                    val tempFile = File(historyRoot, "manual-compact-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}.${currentConfig.format.extension}.tmp")
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
                        operationId = "manual-${selectedSegments.first().startedAtMillis}-${System.nanoTime()}",
                        startIndex = startIndex,
                        sourcePaths = selectedSegments.map { it.file.absolutePath },
                        startedAtMillis = selectedSegments.first().startedAtMillis,
                        totalSampleBytes = totalSampleBytes,
                        snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
                        outputTarget = outputTarget,
                    )
                }
            }
        }
        return null
    }

    private fun buildRegionalCompactionPlanLocked(
        snapshot: Snapshot,
        preferredParallelism: Int,
    ): RegionalCompactionPlan? {
        val currentConfig = config ?: return null
        val groups = ArrayList<RegionalCompactionGroup>()
        var runStart = -1
        for (index in snapshot.slices.indices) {
            val slice = snapshot.slices[index]
            val isFull = slice.skipSampleBytes == 0L && slice.takeSampleBytes == slice.segment.sampleBytes
            if (isFull) {
                if (runStart < 0) {
                    runStart = index
                }
            } else if (runStart >= 0) {
                buildRegionalGroupForRunLocked(snapshot, runStart, index)?.let(groups::add)
                runStart = -1
            }
        }
        if (runStart >= 0) {
            buildRegionalGroupForRunLocked(snapshot, runStart, snapshot.slices.size)?.let(groups::add)
        }
        val desiredParallelism = preferredParallelism.coerceIn(1, MAX_EXPORT_COMPACTION_PARALLELISM)
        val grouped = rebalanceRegionalGroups(groups, desiredParallelism)
        return if (grouped.isEmpty()) null else RegionalCompactionPlan(snapshot, grouped)
    }

    private fun buildRegionalGroupForRunLocked(
        snapshot: Snapshot,
        runStartInclusive: Int,
        runEndExclusive: Int,
    ): RegionalCompactionGroup? {
        val currentConfig = config ?: return null
        val sliceCount = runEndExclusive - runStartInclusive
        if (sliceCount < MIN_COMPACTION_SEGMENTS) {
            return null
        }
        val slices = snapshot.slices.subList(runStartInclusive, runEndExclusive).map { it.copy() }
        val totalSampleBytes = slices.sumOf { it.takeSampleBytes }
        if (totalSampleBytes <= 0L) {
            return null
        }
        val startedAtMillis = slices.first().segment.startedAtMillis
        val tempFile = File(historyRoot, "export-compact-$startedAtMillis-${System.nanoTime()}.${currentConfig.format.extension}.tmp")
        return RegionalCompactionGroup(
            operationId = "export-$startedAtMillis-${System.nanoTime()}",
            startSliceIndex = runStartInclusive,
            endSliceIndexExclusive = runEndExclusive,
            sourcePaths = slices.map { it.segment.file.absolutePath },
            startedAtMillis = startedAtMillis,
            totalSampleBytes = totalSampleBytes,
            snapshot = Snapshot(currentConfig, slices, totalSampleBytes),
            outputTarget = RecordingOutputTarget(
                id = tempFile.absolutePath,
                displayName = tempFile.name,
                mimeType = currentConfig.format.outputMimeType,
                storageType = RecordingStorageType.FILE,
                directoryId = historyRoot.absolutePath,
                startedAtMillis = startedAtMillis,
                file = tempFile,
            ),
        )
    }

    private fun rebalanceRegionalGroups(
        groups: List<RegionalCompactionGroup>,
        desiredParallelism: Int,
    ): List<RegionalCompactionGroup> {
        if (groups.size <= desiredParallelism) {
            return groups
        }
        return groups.sortedByDescending { it.totalSampleBytes }
    }

    private fun resolvedCompactionTargetSampleBytesLocked(
        currentConfig: Config,
        includeExportFallback: Boolean,
    ): Long? {
        val baseSegmentBytes = baseSegmentSampleBytesLocked(currentConfig)
        if (baseSegmentBytes <= 0L) {
            return null
        }
        val rawTargetBytes =
            compactionTargetSampleBytes ?: if (includeExportFallback) {
                (retentionBytes / DEFAULT_EXPORT_COMPACTION_DIVISOR).coerceAtLeast(baseSegmentBytes)
            } else {
                null
            }
        val boundedTargetBytes = minOf(rawTargetBytes ?: return null, retentionBytes.coerceAtLeast(baseSegmentBytes))
        val alignedTargetBytes = (boundedTargetBytes / baseSegmentBytes) * baseSegmentBytes
        return alignedTargetBytes.takeIf { it > baseSegmentBytes }
    }

    private fun baseSegmentSampleBytesLocked(currentConfig: Config): Long {
        return currentConfig.durationMillisToSampleBytes(segmentDurationMillis).coerceAtLeast(1L)
    }

    private fun isCompactionCandidateLocked(
        segment: Segment,
        targetSampleBytes: Long,
        baseSegmentBytes: Long,
    ): Boolean {
        val key = segment.file.absolutePath
        if (pinnedFiles.containsKey(key)) {
            return false
        }
        val sampleBytes = segment.sampleBytes
        return sampleBytes > 0L && sampleBytes < targetSampleBytes
    }

    private fun isExportBlockCandidateLocked(
        segment: Segment,
        targetSampleBytes: Long,
        baseSegmentBytes: Long,
    ): Boolean {
        val key = segment.file.absolutePath
        if (pinnedFiles.containsKey(key)) {
            return false
        }
        val sampleBytes = segment.sampleBytes
        return sampleBytes > 0L && sampleBytes < targetSampleBytes
    }

    private fun shouldCompactSelection(
        totalSampleBytes: Long,
        targetSampleBytes: Long,
        baseSegmentBytes: Long,
        segmentCount: Int,
        reachedEnd: Boolean,
        allowTailRemainder: Boolean,
    ): Boolean {
        if (segmentCount < MIN_COMPACTION_SEGMENTS) {
            return false
        }
        if (totalSampleBytes == targetSampleBytes) {
            return true
        }
        return allowTailRemainder && reachedEnd && totalSampleBytes > baseSegmentBytes
    }

    @Throws(IOException::class)
    private fun runRegionalCompactionJobs(plan: RegionalCompactionPlan): List<CompletedRegionalCompaction> {
        val parallelism = minOf(plan.groups.size, MAX_EXPORT_COMPACTION_PARALLELISM).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(parallelism)
        val futures = ArrayList<Future<CompletedRegionalCompaction?>>(plan.groups.size)
        try {
            plan.groups.forEach { group ->
                synchronized(this) {
                    debugOperations[group.operationId] =
                        DebugOperation(
                            id = group.operationId,
                            kind = DebugOperationKind.EXPORT_MERGE,
                            sourcePaths = group.sourcePaths,
                            targetSampleBytes = group.totalSampleBytes,
                            startedAtMillis = group.startedAtMillis,
                        )
                }
                futures +=
                    executor.submit(
                        Callable {
                            try {
                                mergeSnapshotBalanced(
                                    snapshot = group.snapshot,
                                    outputTarget = group.outputTarget,
                                    preferredParallelism = group.snapshot.slices.size / 2,
                                )
                                val file = group.outputTarget.file
                                if (file == null || !file.isFile || file.length() <= 0L) {
                                    null
                                } else {
                                    CompletedRegionalCompaction(group, file)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Regional export compaction failed", e)
                                group.outputTarget.file?.delete()
                                null
                            } finally {
                                synchronized(this@LiveExportHistory) {
                                    completeDebugOperationLocked(group.operationId)
                                }
                            }
                        },
                    )
            }
            return futures.mapNotNull { future -> future.get() }
        } catch (e: Exception) {
            throw IOException("Regional export compaction failed", e)
        } finally {
            executor.shutdown()
        }
    }

    private fun installRegionalCompactionResultsLocked(
        originalSnapshot: Snapshot,
        plan: RegionalCompactionPlan,
        completedGroups: List<CompletedRegionalCompaction>,
    ): Snapshot {
        val replacementByStartIndex = HashMap<Int, SegmentSlice>()
        completedGroups
            .sortedBy { it.group.startSliceIndex }
            .forEach { completed ->
                val mergedSegment = Segment(
                    file = completed.file,
                    startedAtMillis = completed.group.startedAtMillis,
                    sampleBytes = completed.group.totalSampleBytes,
                )
                finalizeSegmentFileLocked(mergedSegment)
                if (replaceSegmentsLocked(completed.group.sourcePaths, mergedSegment)) {
                    replacementByStartIndex[completed.group.startSliceIndex] =
                        SegmentSlice(
                            segment = mergedSegment,
                            skipSampleBytes = 0L,
                            takeSampleBytes = mergedSegment.sampleBytes,
                        )
                } else {
                    mergedSegment.file.delete()
                }
            }

        if (replacementByStartIndex.isEmpty()) {
            return originalSnapshot
        }

        val mergedSlices = ArrayList<SegmentSlice>(originalSnapshot.slices.size)
        var index = 0
        while (index < originalSnapshot.slices.size) {
            val replacement = replacementByStartIndex[index]
            if (replacement != null) {
                mergedSlices += replacement
                val group = completedGroups.first { it.group.startSliceIndex == index }.group
                index = group.endSliceIndexExclusive
                continue
            }
            mergedSlices += originalSnapshot.slices[index]
            index++
        }
        return Snapshot(
            config = originalSnapshot.config,
            slices = mergedSlices,
            requestedSampleBytes = originalSnapshot.requestedSampleBytes,
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

    private fun replaceSegmentsLocked(
        sourcePaths: List<String>,
        mergedSegment: Segment,
    ): Boolean {
        val currentSegments = segments.toList()
        val startIndex = findContiguousSegmentRangeStartLocked(currentSegments, sourcePaths)
        if (startIndex < 0) {
            return false
        }
        finalizeSegmentFileLocked(mergedSegment)
        val endIndexExclusive = startIndex + sourcePaths.size
        val replacement = ArrayDeque<Segment>(currentSegments.size - sourcePaths.size + 1)
        currentSegments.subList(0, startIndex).forEach(replacement::addLast)
        replacement.addLast(mergedSegment)
        currentSegments.subList(endIndexExclusive, currentSegments.size).forEach(replacement::addLast)
        segments.clear()
        segments.addAll(replacement)
        return true
    }

    private fun findContiguousSegmentRangeStartLocked(
        currentSegments: List<Segment>,
        sourcePaths: List<String>,
    ): Int {
        if (sourcePaths.isEmpty() || sourcePaths.size > currentSegments.size) {
            return -1
        }
        val lastStart = currentSegments.size - sourcePaths.size
        for (startIndex in 0..lastStart) {
            var matches = true
            for (offset in sourcePaths.indices) {
                if (currentSegments[startIndex + offset].file.absolutePath != sourcePaths[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return startIndex
            }
        }
        return -1
    }

    private fun buildHistoryReencodeRangesLocked(
        expectedConfig: Config,
    ): List<ReencodeSourceRange> {
        if (segments.isEmpty()) {
            return emptyList()
        }
        val ranges = ArrayList<ReencodeSourceRange>()
        var currentRangeSegments: MutableList<ReencodeSourceSegment>? = null
        var currentRangeTotalSampleBytes = 0L

        fun flushCurrentRange() {
            val rangeSegments = currentRangeSegments ?: return
            if (rangeSegments.isNotEmpty() && currentRangeTotalSampleBytes > 0L) {
                ranges += ReencodeSourceRange(
                    sourceSegments = rangeSegments.toList(),
                    sourcePaths = rangeSegments.map { it.file.absolutePath },
                    totalSampleBytes = currentRangeTotalSampleBytes,
                )
            }
            currentRangeSegments = null
            currentRangeTotalSampleBytes = 0L
        }

        segments.forEach { segment ->
            val matchesExpectedConfig = inspectPersistedSegment(segment.file, expectedConfig) != null
            if (matchesExpectedConfig) {
                flushCurrentRange()
                return@forEach
            }
            if (currentRangeSegments == null) {
                currentRangeSegments = ArrayList()
            }
            currentRangeSegments?.add(
                ReencodeSourceSegment(
                    file = segment.file,
                    startedAtMillis = segment.startedAtMillis,
                    sampleBytes = segment.sampleBytes,
                ),
            )
            currentRangeTotalSampleBytes += segment.sampleBytes
        }

        flushCurrentRange()
        return ranges
    }

    private fun releasePinnedSourcePathsLocked(
        sourcePaths: List<String>,
    ) {
        sourcePaths.forEach { path ->
            val count = pinnedFiles[path] ?: return@forEach
            if (count <= 1) {
                pinnedFiles.remove(path)
            } else {
                pinnedFiles[path] = count - 1
            }
        }
    }

    private fun completeDebugOperationLocked(operationId: String) {
        val operation = debugOperations.remove(operationId) ?: return
        pruneRecentDebugOperationsLocked()
        recentDebugOperations.addLast(
            RecentDebugOperation(
                operation = operation,
                visibleUntilElapsedRealtime = SystemClock.elapsedRealtime() + DEBUG_OPERATION_VISIBLE_AFTER_COMPLETE_MS,
            ),
        )
    }

    private fun pruneRecentDebugOperationsLocked() {
        val now = SystemClock.elapsedRealtime()
        while (recentDebugOperations.isNotEmpty() && recentDebugOperations.first().visibleUntilElapsedRealtime <= now) {
            recentDebugOperations.removeFirst()
        }
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

        val startedAtMillis = parseStartedAtMillis(file) ?: file.lastModified()
        val parsedSampleBytes = parseSampleBytes(file)
        when {
            currentConfig.format.isRawAacAdts -> {
                return inspectRawAdtsSegment(file, currentConfig, startedAtMillis, parsedSampleBytes)
            }

            currentConfig.format.isRawAmr -> {
                return inspectRawAmrSegment(file, currentConfig, startedAtMillis, parsedSampleBytes)
            }
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

            val sampleBytes =
                parsedSampleBytes
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

    private fun inspectRawAdtsSegment(
        file: File,
        currentConfig: Config,
        startedAtMillis: Long,
        parsedSampleBytes: Long?,
    ): Segment? {
        if (currentConfig.codec != ExportCodec.AAC_LC) {
            return null
        }
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(7)
            val read = input.read(header)
            val parsedHeader = parseAdtsHeader(header, read)
            if (
                parsedHeader == null ||
                parsedHeader.profile != ADTS_LC_PROFILE ||
                parsedHeader.sampleRate != currentConfig.sampleRate ||
                parsedHeader.channelCount != currentConfig.channelCount
            ) {
                return null
            }
        }
        val sampleBytes = parsedSampleBytes ?: return null
        if (sampleBytes <= 0L) {
            return null
        }
        return Segment(file = file, startedAtMillis = startedAtMillis, sampleBytes = sampleBytes)
    }

    private fun inspectRawAmrSegment(
        file: File,
        currentConfig: Config,
        startedAtMillis: Long,
        parsedSampleBytes: Long?,
    ): Segment? {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(9)
            val read = input.read(header)
            if (detectRawAmrCodec(header, read) != currentConfig.codec) {
                return null
            }
        }
        if (
            currentConfig.channelCount != 1 ||
            (currentConfig.codec == ExportCodec.AMR_NB && currentConfig.sampleRate != 8_000) ||
            (currentConfig.codec == ExportCodec.AMR_WB && currentConfig.sampleRate != 16_000)
        ) {
            return null
        }
        val sampleBytes = parsedSampleBytes ?: return null
        if (sampleBytes <= 0L) {
            return null
        }
        return Segment(file = file, startedAtMillis = startedAtMillis, sampleBytes = sampleBytes)
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
        if (expectedMime != null && mime != null && mime != expectedMime) {
            return false
        }
        if (currentConfig.codec.isAacFamily) {
            val aacProfile = format.getIntegerOrNull(MediaFormat.KEY_AAC_PROFILE)
            if (aacProfile != null && aacProfile != currentConfig.codec.aacProfile) {
                return false
            }
        }
        return true
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
        ensureHistoryRootExists()
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

    private fun ensureHistoryRootExists() {
        if (!historyRoot.exists() && !historyRoot.mkdirs() && !historyRoot.exists()) {
            throw IOException("Unable to create history cache directory: ${historyRoot.absolutePath}")
        }
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

    data class ImportedSegment(
        val file: File,
        val startedAtMillis: Long,
        val sampleBytes: Long,
    )

    data class HistoryReencodeSourceSnapshot(
        val sourceRanges: List<ReencodeSourceRange>,
        val sourcePaths: List<String>,
        val totalSampleBytes: Long,
    )

    data class ReencodeSourceRange(
        val sourceSegments: List<ReencodeSourceSegment>,
        val sourcePaths: List<String>,
        val totalSampleBytes: Long,
    )

    data class ReencodeSourceSegment(
        val file: File,
        val startedAtMillis: Long,
        val sampleBytes: Long,
    )

    data class HistoryReencodeReplacementRange(
        val sourcePaths: List<String>,
        val importedSegments: List<ImportedSegment>,
    )

    private data class IndexedHistoryReencodeReplacementRange(
        val startIndex: Int,
        val endIndexExclusive: Int,
        val replacement: HistoryReencodeReplacementRange,
    )

    private data class CompactionTask(
        val startIndex: Int,
        val operationId: String,
        val sourcePaths: List<String>,
        val startedAtMillis: Long,
        val totalSampleBytes: Long,
        val snapshot: Snapshot,
        val outputTarget: RecordingOutputTarget,
    )

    private data class RegionalCompactionPlan(
        val originalSnapshot: Snapshot,
        val groups: List<RegionalCompactionGroup>,
    )

    private data class PreparedExportPlan(
        val parts: List<PreparedExportPlanPart>,
    )

    private sealed class PreparedExportPlanPart {
        data class Direct(
            val slice: SegmentSlice,
        ) : PreparedExportPlanPart()

        data class Compacted(
            val group: RegionalCompactionGroup,
        ) : PreparedExportPlanPart()
    }

    private data class PreparedExportSession(
        val parts: List<PreparedExportPart>,
        val executor: java.util.concurrent.ExecutorService,
    ) {
        fun close() {
            executor.shutdownNow()
            parts.forEach { part ->
                if (part is PreparedExportPart.Compacted) {
                    part.group.outputTarget.file?.delete()
                }
            }
        }
    }

    private sealed class PreparedExportPart {
        data class Direct(
            val slice: SegmentSlice,
        ) : PreparedExportPart()

        data class Compacted(
            val group: RegionalCompactionGroup,
            val future: Future<CompletedRegionalCompaction?>,
        ) : PreparedExportPart()
    }

    private data class PreparedSegmentSlice(
        // Prepared export can spill full slice groups into temp files; callers must know
        // whether the slice is original history or disposable prepared output.
        val slice: SegmentSlice,
        val deleteAfterUse: Boolean,
    )

    private data class MergeLevelNode(
        // Balanced merge builds one persisted segment per tree level instead of repeatedly
        // appending onto one ever-growing left branch.
        val segment: Segment,
    )

    private data class RegionalCompactionGroup(
        val operationId: String,
        val startSliceIndex: Int,
        val endSliceIndexExclusive: Int,
        val sourcePaths: List<String>,
        val startedAtMillis: Long,
        val totalSampleBytes: Long,
        val snapshot: Snapshot,
        val outputTarget: RecordingOutputTarget,
    )

    private data class CompletedRegionalCompaction(
        val group: RegionalCompactionGroup,
        val file: File,
    )

    data class DebugChunk(
        val fileName: String,
        val filePath: String,
        val startedAtMillis: Long,
        val endedAtMillis: Long,
        val sampleBytes: Long,
        val fileSizeBytes: Long,
        val format: String?,
        val codec: String?,
        val sampleRate: Int,
        val channelCount: Int,
        val active: Boolean,
    )

    data class DebugChunkExportDescriptor(
        val file: File,
        val startedAtMillis: Long,
        val durationMillis: Long,
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleRate: Int,
        val channelCount: Int,
        val bitrateKbps: Int?,
    ) {
        val mimeType: String get() = format.outputMimeType
        val extension: String get() = format.extension
    }

    data class DebugOperation(
        val id: String,
        val kind: DebugOperationKind,
        val sourcePaths: List<String>,
        val targetSampleBytes: Long,
        val startedAtMillis: Long,
    )

    private data class RecentDebugOperation(
        // Keep completed ops visible briefly so the debug screen can show recent merges
        // without racing the next snapshot refresh.
        val operation: DebugOperation,
        val visibleUntilElapsedRealtime: Long,
    )

    enum class DebugOperationKind {
        BACKGROUND_MERGE,
        EXPORT_MERGE,
    }

    data class DebugSnapshot(
        val segmentCount: Int,
        val totalSampleBytes: Long,
        val currentSegmentSampleBytes: Long,
        val nextSegmentStartMillis: Long?,
        val segmentFiles: List<String>,
        val format: String?,
        val codec: String?,
        val sampleRate: Int,
        val channelCount: Int,
        val chunks: List<DebugChunk>,
        val operations: List<DebugOperation>,
    )

    @Synchronized
    fun debugSnapshot(): DebugSnapshot {
        val currentConfig = config
        val activeSampleBytes = currentWriter?.totalSampleBytesWritten?.toLong() ?: 0L
        pruneRecentDebugOperationsLocked()
        val chunks = ArrayList<DebugChunk>(segments.size + if (activeSampleBytes > 0L) 1 else 0)
        segments.forEach { segment ->
            chunks += buildDebugChunk(segment, currentConfig, active = false)
        }
        currentSegment?.takeIf { activeSampleBytes > 0L }?.let { segment ->
            segment.sampleBytes = activeSampleBytes
            chunks += buildDebugChunk(segment, currentConfig, active = true)
        }
        return DebugSnapshot(
            segmentCount = segments.size,
            totalSampleBytes = countRetainedSampleBytes(),
            currentSegmentSampleBytes = activeSampleBytes,
            nextSegmentStartMillis = nextSegmentStartMillis,
            segmentFiles = segments.map { it.file.name },
            format = currentConfig?.format?.prefValue,
            codec = currentConfig?.codec?.prefValue,
            sampleRate = currentConfig?.sampleRate ?: 0,
            channelCount = currentConfig?.channelCount ?: 0,
            chunks = chunks,
            operations = debugOperations.values.toList() + recentDebugOperations.map { it.operation },
        )
    }

    @Synchronized
    fun debugChunkExportDescriptor(filePath: String): DebugChunkExportDescriptor? {
        val currentConfig = config ?: return null
        if (currentSegment?.file?.absolutePath == filePath && currentWriter != null) {
            return null
        }
        val segment = segments.firstOrNull { it.file.absolutePath == filePath } ?: return null
        return DebugChunkExportDescriptor(
            file = segment.file,
            startedAtMillis = segment.startedAtMillis,
            durationMillis = currentConfig.bytesToDurationMillis(segment.sampleBytes),
            format = currentConfig.format,
            codec = currentConfig.codec,
            sampleRate = currentConfig.sampleRate,
            channelCount = currentConfig.channelCount,
            bitrateKbps = currentConfig.bitrateKbps,
        )
    }

    fun debugCompactChunkNow(filePath: String): Boolean {
        val task = synchronized(this) { claimCompactionTaskAroundChunkLocked(filePath) } ?: return false
        var mergedFile: File? = null
        return try {
            synchronized(this) {
                debugOperations[task.operationId] =
                    DebugOperation(
                        id = task.operationId,
                        kind = DebugOperationKind.BACKGROUND_MERGE,
                        sourcePaths = task.sourcePaths,
                        targetSampleBytes = task.totalSampleBytes,
                        startedAtMillis = task.startedAtMillis,
                    )
            }
            mergeSnapshotBalanced(
                snapshot = task.snapshot,
                outputTarget = task.outputTarget,
                preferredParallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            )
            mergedFile = requireNotNull(task.outputTarget.file)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Manual chunk compaction failed", e)
            false
        } finally {
            synchronized(this) {
                completeDebugOperationLocked(task.operationId)
                finishCompactionLocked(task, mergedFile)
            }
        }
    }

    fun debugCompactAllChunksNow(): Int {
        var mergedCount = 0
        while (true) {
            val task = synchronized(this) { claimCompactionTaskLocked(includeExportFallback = true) } ?: break
            var mergedFile: File? = null
            val merged =
                try {
                    synchronized(this) {
                        debugOperations[task.operationId] =
                            DebugOperation(
                                id = task.operationId,
                                kind = DebugOperationKind.BACKGROUND_MERGE,
                                sourcePaths = task.sourcePaths,
                                targetSampleBytes = task.totalSampleBytes,
                                startedAtMillis = task.startedAtMillis,
                            )
                    }
                    mergeSnapshotBalanced(
                        snapshot = task.snapshot,
                        outputTarget = task.outputTarget,
                        preferredParallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                    )
                    mergedFile = requireNotNull(task.outputTarget.file)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Debug full-history compaction failed", e)
                    false
                } finally {
                    synchronized(this) {
                        completeDebugOperationLocked(task.operationId)
                        finishCompactionLocked(task, mergedFile)
                    }
                }
            if (!merged) {
                break
            }
            mergedCount++
        }
        return mergedCount
    }

    fun debugExportChunkToTarget(
        filePath: String,
        outputTarget: RecordingOutputTarget,
    ): DebugChunkExportDescriptor? {
        val descriptor = synchronized(this) { debugChunkExportDescriptor(filePath) } ?: return null
        copyWholeFile(descriptor.file, outputTarget)
        return descriptor
    }

    @Synchronized
    private fun buildDebugChunk(
        segment: Segment,
        currentConfig: Config?,
        active: Boolean,
    ): DebugChunk {
        val durationMillis = currentConfig?.bytesToDurationMillis(segment.sampleBytes) ?: 0L
        return DebugChunk(
            fileName = segment.file.name,
            filePath = segment.file.absolutePath,
            startedAtMillis = segment.startedAtMillis,
            endedAtMillis = segment.startedAtMillis + durationMillis,
            sampleBytes = segment.sampleBytes,
            fileSizeBytes = segment.file.takeIf(File::isFile)?.length() ?: 0L,
            format = currentConfig?.format?.prefValue,
            codec = currentConfig?.codec?.prefValue,
            sampleRate = currentConfig?.sampleRate ?: 0,
            channelCount = currentConfig?.channelCount ?: 0,
            active = active,
        )
    }

    private companion object {
        const val TAG = "LiveExportHistory"
        const val WAV_HEADER_BYTES = 44L
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val DEFAULT_SEGMENT_DURATION_MS = 10_000L
        const val MIN_SEGMENT_DURATION_MS = 2_000L
        const val MAX_SEGMENT_DURATION_MS = 300_000L
        const val MIN_COMPACTION_SEGMENTS = 2
        const val DEFAULT_EXPORT_COMPACTION_DIVISOR = 100L
        const val MAX_EXPORT_COMPACTION_PARALLELISM = 4
        const val DEFAULT_EXPORT_COMPACTION_PARALLELISM = 4
        const val DEBUG_OPERATION_VISIBLE_AFTER_COMPLETE_MS = 5_000L
        const val TEMP_FILE_SUFFIX = ".tmp"
        val AMR_NB_MAGIC_HEADER = "#!AMR\n".toByteArray(Charsets.US_ASCII)
        val AMR_WB_MAGIC_HEADER = "#!AMR-WB\n".toByteArray(Charsets.US_ASCII)
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
