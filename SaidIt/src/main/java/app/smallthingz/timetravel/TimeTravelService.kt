package app.smallthingz.timetravel

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("ImplicitSamInstance")
class TimeTravelService : Service() {
    @Volatile
    private var sampleRate = 48_000

    @Volatile
    private var fillRate = 96_000

    @Volatile
    private var audioSource = AudioSourceMode.MIC.sourceValue

    @Volatile
    private var sourceMode = AudioSourceMode.MIC

    @Volatile
    private var channelMode = ChannelMode.MONO

    @Volatile
    private var outputFormat = ExportFormat.WAV

    @Volatile
    private var outputCodec = ExportCodec.PCM_16

    @Volatile
    private var inputRouteMode = InputRouteMode.AUTO

    @Volatile
    private var state = STATE_READY

    @Volatile
    private var recordingTarget: RecordingOutputTarget? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var audioFileWriter: AudioFileWriter? = null

    @Volatile
    private var historyReencodePending = false

    @Volatile
    private var historyReencoding = false

    @Volatile
    private var historyReencodeProcessedBytes = 0L

    @Volatile
    private var historyReencodeTotalBytes = 0L

    private val audioMemory = AudioMemory()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioThread: HandlerThread
    private lateinit var audioHandler: Handler
    private lateinit var exportThread: HandlerThread
    private lateinit var exportHandler: Handler
    private lateinit var persistentAudioRingStore: PersistentAudioRingStore
    private lateinit var liveExportHistory: LiveExportHistory

    private var wakeLock: PowerManager.WakeLock? = null
    private val historyCompactor: Runnable = object : Runnable {
        override fun run() {
            val compacted =
                try {
                    if (::liveExportHistory.isInitialized && !historyReencodePending && !historyReencoding) {
                        liveExportHistory.compactIfNeeded()
                    } else {
                        false
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "History compaction pass failed", t)
                    false
                }

            if (!::exportHandler.isInitialized) {
                return
            }
            if (compacted) {
                exportHandler.post(this)
            } else {
                exportHandler.postDelayed(this, HISTORY_COMPACTION_IDLE_DELAY_MS)
            }
        }
    }

    override fun onCreate() {
        loadConfiguration()
        persistentAudioRingStore = PersistentAudioRingStore(this)
        liveExportHistory = LiveExportHistory(this)
        adoptPersistedBufferConfigurationIfNeeded()
        updateLiveExportHistoryConfiguration(restoredOrConfiguredMemorySize())
        audioThread = HandlerThread("timeTravelAudioThread", Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        audioHandler = Handler(audioThread.looper)
        exportThread = HandlerThread("timeTravelExportThread", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        exportHandler = Handler(exportThread.looper)
        exportHandler.post(historyCompactor)
        audioHandler.post {
            restorePersistedBufferIfNeeded()
        }
        scheduleRecorderCapabilityCacheWarm(applicationContext)

        if (isListeningEnabled()) {
            innerStartListening()
        }
    }

    override fun onDestroy() {
        flushAndPersistBeforeShutdown()
        historyReencoding = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (::exportHandler.isInitialized) {
            exportHandler.removeCallbacks(historyCompactor)
        }
        liveExportHistory.closePreservingHistory()
        persistentAudioRingStore.close()
        audioThread.quitSafely()
        exportThread.quitSafely()
        scheduleRestartIfNeeded()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        audioHandler.post { syncPersistentBufferFromMemory() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        audioHandler.post { syncPersistentBufferFromMemory() }
    }

    override fun onBind(intent: Intent): IBinder = BackgroundRecorderBinder()

    override fun onUnbind(intent: Intent): Boolean = true

    override fun dump(
        fd: FileDescriptor,
        writer: PrintWriter,
        args: Array<out String>,
    ) {
        if (!isDebuggableBuild()) {
            super.dump(fd, writer, args)
            return
        }
        val stats = audioMemory.getStats(fillRate)
        val persisted = if (::persistentAudioRingStore.isInitialized) persistentAudioRingStore.peekSnapshot() else null
        val history = if (::liveExportHistory.isInitialized) liveExportHistory.debugSnapshot() else null
        writer.println("TimeTravelService")
        writer.println("  state=$state")
        writer.println("  listeningEnabled=${isListeningEnabled()}")
        writer.println("  sampleRate=$sampleRate")
        writer.println("  channelCount=${channelMode.channelCount}")
        writer.println("  format=${effectiveOutputFormat.prefValue}")
        writer.println("  codec=${effectiveOutputCodec.prefValue}")
        writer.println("  fillRate=$fillRate")
        writer.println("  exportDir=${describeConfiguredOutputDirectory(this)}")
        writer.println("  buffer filled=${stats.filled} total=${stats.total} overwriting=${stats.overwriting}")
        writer.println(
            "  persisted filled=${persisted?.filledBytes ?: 0} capacity=${persisted?.capacityBytes ?: 0} " +
                "sampleRate=${persisted?.sampleRate ?: 0} channelCount=${persisted?.channelCount ?: 0} " +
                "lastWrite=${persisted?.lastWriteAtMillis ?: 0}",
        )
        writer.println(
            "  history segments=${history?.segmentCount ?: 0} totalSampleBytes=${history?.totalSampleBytes ?: 0} " +
                "currentSegmentSampleBytes=${history?.currentSegmentSampleBytes ?: 0} " +
                "nextSegmentStart=${history?.nextSegmentStartMillis ?: 0}",
        )
        history?.segmentFiles?.forEach { fileName ->
            writer.println("    historyFile=$fileName")
        }
    }

    fun enableListening() {
        getRecorderPreferences(this).edit()
            .putBoolean(TimeTravelConfig.AUDIO_MEMORY_ENABLED_KEY, true)
            .apply()
        innerStartListening()
    }

    fun disableListening() {
        getRecorderPreferences(this).edit()
            .putBoolean(TimeTravelConfig.AUDIO_MEMORY_ENABLED_KEY, false)
            .apply()
        if (state == STATE_RECORDING) {
            stopRecording(TimeTravelFragment.NotifyFileReceiver(this))
        }
        innerPauseListening()
    }

    private fun isListeningEnabled(): Boolean {
        return getRecorderPreferences(this).getBoolean(TimeTravelConfig.AUDIO_MEMORY_ENABLED_KEY, true)
    }

    private fun loadConfiguration() {
        var selectedSourceMode = getConfiguredAudioSourceMode(this)
        var selectedChannelMode = getConfiguredChannelMode(this)
        var selectedRouteMode = getConfiguredInputRouteMode(this)
        var selectedFormat = getConfiguredOutputFormat(this)
        var selectedCodec = getConfiguredOutputCodec(this)

        val requestedRate = getConfiguredSampleRate(this, selectedSourceMode, selectedRouteMode, selectedFormat, selectedCodec, selectedChannelMode)
        val resolvedConfig = resolveOperationalConfiguration(
            preferredSourceMode = selectedSourceMode,
            preferredChannelMode = selectedChannelMode,
            preferredRouteMode = selectedRouteMode,
            preferredFormat = selectedFormat,
            preferredCodec = selectedCodec,
            preferredRate = requestedRate,
        )

        if (resolvedConfig != null) {
            selectedSourceMode = resolvedConfig.sourceMode
            selectedChannelMode = resolvedConfig.channelMode
            selectedRouteMode = resolvedConfig.routeMode
            selectedFormat = resolvedConfig.format
            selectedCodec = resolvedConfig.codec
        }

        sampleRate = resolvedConfig?.sampleRate ?: 48_000
        fillRate = sampleRate * selectedChannelMode.channelCount * 2
        sourceMode = selectedSourceMode
        channelMode = selectedChannelMode
        audioSource = selectedSourceMode.sourceValue
        outputFormat = selectedFormat
        outputCodec = selectedCodec
        inputRouteMode = selectedRouteMode
        updateLiveExportHistoryConfiguration()
    }

    private fun resolveOperationalConfiguration(
        preferredSourceMode: AudioSourceMode,
        preferredChannelMode: ChannelMode,
        preferredRouteMode: InputRouteMode,
        preferredFormat: ExportFormat,
        preferredCodec: ExportCodec,
        preferredRate: Int,
    ): OperationalConfig? {
        val formatCandidates = listOf(preferredFormat) + supportedFormats().filter { it != preferredFormat }
        val routeCandidates = listOf(preferredRouteMode) + supportedInputRouteModes(this).filter { it != preferredRouteMode }
        val sourceCandidates = listOf(preferredSourceMode) + AudioSourceMode.availableModes().filter { it != preferredSourceMode }
        val channelCandidates = listOf(preferredChannelMode) + ChannelMode.entries.filter { it != preferredChannelMode }

        formatCandidates.forEach { format ->
            val codecCandidates = listOf(preferredCodec) + supportedCodecs(format).filter { it != preferredCodec }
            codecCandidates.forEach { codec ->
                routeCandidates.forEach { routeMode ->
                    sourceCandidates.forEach { sourceMode ->
                        channelCandidates.forEach { channelMode ->
                            val sampleRate = resolveOperationalSampleRate(
                                this,
                                preferredRate,
                                sourceMode,
                                routeMode,
                                format,
                                codec,
                                channelMode,
                            )
                            if (sampleRate > 0 && isCodecSupported(format, codec, sampleRate, channelMode)) {
                                return OperationalConfig(
                                    sourceMode = sourceMode,
                                    channelMode = channelMode,
                                    routeMode = routeMode,
                                    format = format,
                                    codec = codec,
                                    sampleRate = sampleRate,
                                )
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun innerStartListening() {
        when (state) {
            STATE_LISTENING, STATE_RECORDING -> return
            STATE_READY, STATE_PAUSED -> Unit
            else -> return
        }

        state = STATE_LISTENING
        updateWakeLockState()
        ContextCompat.startForegroundService(this, Intent(this, javaClass))

        val logicalRetentionBytes = configuredRetentionSampleBytes()
        val workingMemorySize = configuredWorkingMemorySizeBytes()
        audioHandler.post {
            releaseAudioRecord()
            audioMemory.allocate(workingMemorySize)
            restorePersistedBufferIfNeeded(logicalRetentionBytes, workingMemorySize)

            audioRecord = createAudioRecord()
            val record = audioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Audio input initialization failed")
                releaseAudioRecord()
                state = STATE_READY
                updateWakeLockState()
                showToast(getString(R.string.audio_input_init_failed))
                return@post
            }

            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioRecord.startRecording failed", e)
                releaseAudioRecord()
                state = STATE_READY
                updateWakeLockState()
                showToast(getString(R.string.audio_input_init_failed))
                return@post
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord failed to enter recording state")
                releaseAudioRecord()
                state = STATE_READY
                updateWakeLockState()
                showToast(getString(R.string.audio_input_init_failed))
                return@post
            }

            audioHandler.removeCallbacks(audioReader)
            audioHandler.post(audioReader)
        }
    }

    private fun innerPauseListening() {
        when (state) {
            STATE_READY, STATE_PAUSED, STATE_RECORDING -> return
            STATE_LISTENING -> Unit
            else -> return
        }

        state = STATE_PAUSED
        updateWakeLockState()
        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
            syncPersistentBufferFromMemory()
            liveExportHistory.pause()
            releaseAudioRecord()
        }
    }

    private fun innerStopListening() {
        when (state) {
            STATE_READY, STATE_RECORDING -> return
            STATE_LISTENING, STATE_PAUSED -> Unit
            else -> return
        }

        state = STATE_READY
        updateWakeLockState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopService(Intent(this, javaClass))

        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
            syncPersistentBufferFromMemory()
            liveExportHistory.pause()
            releaseAudioRecord()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMode.inputChannelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioRecord min buffer invalid for $sampleRate Hz")
            return null
        }

        return try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMode.inputChannelMask)
                        .setSampleRate(sampleRate)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, MIN_AUDIO_RECORD_BUFFER_SIZE))
                .build()
                .also { record ->
                    if (inputRouteMode == InputRouteMode.BUILTIN_MIC) {
                        findBuiltInMicrophone(this)?.let { record.preferredDevice = it }
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to create AudioRecord", t)
            null
        }
    }

    private fun releaseAudioRecord() {
        val record = audioRecord ?: return
        runCatching { record.stop() }
        record.release()
        audioRecord = null
    }

    fun dumpRecording(
        memorySeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        check(canExportBufferedAudio()) { "Buffer unavailable" }

        audioHandler.post {
            flushAudioRecord()

            val bytesAvailable = availableBufferedSampleBytes()
            val prependBytes = (memorySeconds * fillRate).toLong()
            val skipBytes = maxOf(0L, bytesAvailable - prependBytes)
            val useBytes = bytesAvailable - skipBytes
            exportBufferedRange(skipBytes, useBytes, receiver, newFileName)
        }
    }

    fun dumpRecordingRange(
        startOffsetSeconds: Float,
        endOffsetSeconds: Float,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        check(canExportBufferedAudio()) { "Buffer unavailable" }

        audioHandler.post {
            flushAudioRecord()

            val bytesAvailable = availableBufferedSampleBytes()
            val boundedStart = startOffsetSeconds.coerceAtLeast(0f)
            val boundedEnd = endOffsetSeconds.coerceAtLeast(boundedStart)
            val skipBytes = (boundedStart * fillRate).toLong().coerceAtMost(bytesAvailable)
            val endBytes = (boundedEnd * fillRate).toLong().coerceAtMost(bytesAvailable)
            val useBytes = (endBytes - skipBytes).coerceAtLeast(0L)
            exportBufferedRange(skipBytes, useBytes, receiver, newFileName)
        }
    }

    private fun exportBufferedRange(
        skipBytes: Long,
        useBytes: Long,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        if (useBytes <= 0L) {
            notifyReceiverFailure(receiver, getString(R.string.custom_export_duration_invalid))
            return
        }
        val bytesAvailable = availableBufferedSampleBytes()
        val startedAtMillis = System.currentTimeMillis() - 1000L * (bytesAvailable - skipBytes) / maxOf(fillRate, 1)
        val exportFormat = effectiveOutputFormat
        val exportCodec = effectiveOutputCodec
        val exportConfig = LiveExportHistory.Config(
            format = exportFormat,
            codec = exportCodec,
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
            bitrateKbps = getConfiguredCodecBitrateKbps(this@TimeTravelService, exportCodec, sampleRate, channelMode.channelCount),
        )

        val outTarget = try {
            createOutputTarget(this@TimeTravelService, newFileName, startedAtMillis, exportFormat, exportCodec)
        } catch (e: IOException) {
            Log.e(TAG, "Unable to prepare export file", e)
            val message = getString(R.string.cant_create_file_generic)
            showToast(message)
            notifyReceiverFailure(receiver, message)
            return
        }
        val historySnapshot = liveExportHistory.snapshotForRange(
            skipSampleBytes = skipBytes,
            requestedSampleBytes = useBytes,
            reopenForContinuedCapture = state == STATE_LISTENING || state == STATE_RECORDING,
        )
        exportHandler.post {
            try {
                var durationMillis = 0L
                if (historySnapshot != null && historySnapshot.config == exportConfig) {
                    liveExportHistory.exportSnapshotOptimized(
                        snapshot = historySnapshot,
                        outputTarget = outTarget,
                        preferredParallelism = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                    )
                    requireExportedOutput(outTarget)
                    durationMillis = (historySnapshot.requestedSampleBytes * bytesToSeconds * 1000f).toLong()
                } else {
                    val readSucceeded =
                        createAudioFileWriter(outTarget).use { writer ->
                            val didRead = readBufferedPcm(skipBytes, useBytes) { array, offset, count ->
                                writer.write(array, offset, count)
                                count
                            }
                            durationMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
                            didRead
                        }
                    if (!readSucceeded) {
                        throw IOException("Requested PCM range not available in fallback buffer")
                    }
                    requireExportedOutput(outTarget)
                }
                notifyReceiver(
                    receiver,
                    buildRecordingEntity(
                        this@TimeTravelService,
                        outTarget,
                        durationMillis,
                        currentCodecSummary(),
                    ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fast export failed for ${outTarget.displayName}; falling back to PCM export", e)
                deleteIfEmpty(outTarget)
                try {
                    var durationMillis = 0L
                    val readSucceeded =
                        createAudioFileWriter(outTarget).use { writer ->
                            val didRead = readBufferedPcm(skipBytes, useBytes) { array, offset, count ->
                                writer.write(array, offset, count)
                                count
                            }
                            durationMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
                            didRead
                        }
                    if (!readSucceeded) {
                        throw IOException("Requested PCM range not available in fallback buffer")
                    }
                    requireExportedOutput(outTarget)
                    notifyReceiver(
                        receiver,
                        buildRecordingEntity(
                            this@TimeTravelService,
                            outTarget,
                            durationMillis,
                            currentCodecSummary(),
                        ),
                    )
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "Error while exporting history into ${outTarget.displayName}", fallbackError)
                    val message = getString(R.string.error_during_writing_history_into) + outTarget.displayName
                    showToast(message)
                    notifyReceiverFailure(receiver, message)
                    deleteIfEmpty(outTarget)
                }
            } finally {
                historySnapshot?.let { liveExportHistory.releaseSnapshot(it) }
            }
        }
    }

    fun startRecording(prependedMemorySeconds: Float) {
        when (state) {
            STATE_READY -> innerStartListening()
            STATE_LISTENING -> Unit
            STATE_PAUSED -> innerStartListening()
            STATE_RECORDING -> return
            else -> return
        }

        state = STATE_RECORDING
        updateWakeLockState()

        audioHandler.post {
            flushAudioRecord()

            val prependBytes = (prependedMemorySeconds * fillRate).toLong()
            val bytesAvailable = currentRawBufferedSampleBytes()
            val skipBytes = maxOf(0L, bytesAvailable - prependBytes)
            val useBytes = bytesAvailable - skipBytes
            val startedAtMillis = System.currentTimeMillis() - 1000L * useBytes / maxOf(fillRate, 1)

            try {
                recordingTarget = createOutputTarget(this@TimeTravelService, null, startedAtMillis, effectiveOutputFormat, effectiveOutputCodec)
                audioFileWriter = createAudioFileWriter(requireNotNull(recordingTarget))
            } catch (e: Exception) {
                Log.e(TAG, "Unable to create recording output", e)
                recordingTarget = null
                audioFileWriter = null
                state = STATE_LISTENING
                showToast(getString(R.string.cant_create_file_generic))
                return@post
            }

            if (skipBytes >= bytesAvailable) {
                return@post
            }

            exportHandler.post {
                try {
                    val writer = audioFileWriter
                    readBufferedPcm(skipBytes, useBytes) { array, offset, count ->
                        writer?.write(array, offset, count)
                        count
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error while priming recording into ${recordingTarget?.displayName}", e)
                    stopRecording(TimeTravelFragment.NotifyFileReceiver(this@TimeTravelService))
                }
            }
        }
    }

    fun getMemorySize(): Long = configuredRetentionSampleBytes()

    fun setMemorySize(memorySize: Long) {
        getRecorderPreferences(this).edit()
            .putString(TimeTravelConfig.RETENTION_MODE_KEY, TimeTravelConfig.RETENTION_MODE_SIZE)
            .putLong(TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY, memorySize.coerceAtLeast(1L))
            .apply()
        reloadConfiguration()
    }

    fun getSamplingRate(): Int = sampleRate

    fun setSampleRate(sampleRate: Int) {
        getRecorderPreferences(this).edit()
            .putInt(TimeTravelConfig.SAMPLE_RATE_KEY, sampleRate)
            .apply()
        reloadConfiguration()
    }

    fun applyUpdatedPreferences(): ApplySettingsResult {
        if (state == STATE_RECORDING) {
            return ApplySettingsResult.BLOCKED_RECORDING
        }

        val newSourceMode = getConfiguredAudioSourceMode(this)
        val newChannelMode = getConfiguredChannelMode(this)
        val newRouteMode = getConfiguredInputRouteMode(this)
        val newFormat = getConfiguredOutputFormat(this)
        val newCodec = getConfiguredOutputCodec(this)
        val newSampleRate = resolveOperationalSampleRate(
            this,
            getConfiguredSampleRate(this, newSourceMode, newRouteMode, newFormat, newCodec, newChannelMode),
            newSourceMode,
            newRouteMode,
            newFormat,
            newCodec,
            newChannelMode,
        )
        val newBitrateKbps = getConfiguredCodecBitrateKbps(this, newCodec, newSampleRate, newChannelMode.channelCount)
        val captureConfigChanged =
            newSampleRate != sampleRate ||
                newSourceMode != sourceMode ||
                newChannelMode != channelMode ||
                newRouteMode != inputRouteMode
        val outputConfigChanged = newFormat != outputFormat || newCodec != outputCodec
        val historyEncodingChanged =
            outputConfigChanged || newBitrateKbps != configuredCodecBitrateKbps()
        val hasRetainedBuffer = availableBufferedSampleBytes() > 0L
        if (historyEncodingChanged && hasRetainedBuffer) {
            historyReencodePending = true
            historyReencoding = false
        }
        updateWakeLockState()

        if (state != STATE_LISTENING || !isListeningEnabled()) {
            if (captureConfigChanged && hasRetainedBuffer) {
                return ApplySettingsResult.DEFERRED_UNTIL_RESTART
            }
            loadConfiguration()
            audioHandler.post {
                if (historyEncodingChanged && hasRetainedBuffer) {
                    historyReencodeProcessedBytes = 0L
                    historyReencodeTotalBytes = currentRawBufferedSampleBytes()
                    updateLiveExportHistoryConfiguration()
                    syncPersistentBufferFromMemory()
                    maybeStartHistoryReencode()
                    return@post
                }
                historyReencodePending = false
                historyReencoding = false
                historyReencodeProcessedBytes = 0L
                historyReencodeTotalBytes = 0L
                restorePersistedBufferIfNeeded()
            }
            return if (historyEncodingChanged && hasRetainedBuffer) {
                ApplySettingsResult.REENCODE_REQUIRED
            } else {
                ApplySettingsResult.APPLIED_NOW
            }
        }

        if (captureConfigChanged) {
            return ApplySettingsResult.DEFERRED_UNTIL_RESTART
        }

        loadConfiguration()
        audioHandler.post {
            audioMemory.allocate(configuredWorkingMemorySizeBytes())
            historyReencodePending = historyEncodingChanged && hasRetainedBuffer
            historyReencoding = false
            historyReencodeProcessedBytes = 0L
            historyReencodeTotalBytes = if (historyReencodePending) currentRawBufferedSampleBytes() else 0L
            updateLiveExportHistoryConfiguration()
            syncPersistentBufferFromMemory()
            if (historyReencodePending) {
                maybeStartHistoryReencode()
            }
        }

        return if (historyEncodingChanged && hasRetainedBuffer) {
            ApplySettingsResult.REENCODE_REQUIRED
        } else {
            ApplySettingsResult.APPLIED_NOW
        }
    }

    fun reloadConfiguration(): Boolean {
        if (state == STATE_RECORDING) {
            return false
        }

        val shouldListen = isListeningEnabled()
        if (state == STATE_LISTENING || state == STATE_PAUSED) {
            innerStopListening()
        }
        loadConfiguration()
        if (shouldListen) {
            innerStartListening()
        }
        return true
    }

    fun stopRecording(receiver: AudioFileReceiver?) {
        if (state != STATE_RECORDING) {
            return
        }

        state = STATE_LISTENING
        updateWakeLockState()
        audioHandler.post {
            flushAudioRecord()

            val writer = audioFileWriter
            val target = recordingTarget
            audioFileWriter = null
            recordingTarget = null

            if (writer == null || target == null) {
                return@post
            }

            val runtimeMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
            runCatching { writer.close() }
                .onFailure { Log.e(TAG, "Error while closing recording file", it) }
            runCatching { requireExportedOutput(target) }
                .onFailure {
                    Log.e(TAG, "Recorded output missing or empty", it)
                    notifyReceiverFailure(receiver, getString(R.string.error_during_writing_history_into) + target.displayName)
                    return@post
                }

            notifyReceiver(
                receiver,
                buildRecordingEntity(
                    this@TimeTravelService,
                    target,
                    runtimeMillis,
                    currentCodecSummary(),
                ),
            )
        }

        if (!isListeningEnabled()) {
            innerPauseListening()
        }
    }

    private fun notifyReceiver(
        receiver: AudioFileReceiver?,
        recording: RecordingEntity,
    ) {
        receiver ?: return
        mainHandler.post { receiver.fileReady(recording) }
    }

    private fun notifyReceiverFailure(
        receiver: AudioFileReceiver?,
        message: String,
    ) {
        receiver ?: return
        mainHandler.post { receiver.fileFailed(message) }
    }

    private val effectiveOutputFormat: ExportFormat
        get() = if (outputFormat in supportedFormats()) outputFormat else supportedFormats().firstOrNull() ?: ExportFormat.WAV

    private val effectiveOutputCodec: ExportCodec
        get() = if (isCodecSupported(effectiveOutputFormat, outputCodec, sampleRate, channelMode)) {
            outputCodec
        } else {
            supportedCodecs(effectiveOutputFormat).firstOrNull { isCodecSupported(effectiveOutputFormat, it, sampleRate, channelMode) } ?: ExportCodec.PCM_16
        }

    @Throws(IOException::class)
    private fun createAudioFileWriter(target: RecordingOutputTarget): AudioFileWriter {
        val format = effectiveOutputFormat
        val codec = effectiveOutputCodec
        val bitrateKbps = getConfiguredCodecBitrateKbps(this, codec, sampleRate, channelMode.channelCount)
        return when {
            format.isPcmContainer -> WavAudioFileWriter(this, target, sampleRate, channelMode.channelCount)
            format.usesMuxer -> EncodedAudioFileWriter(
                this,
                target,
                format,
                codec,
                sampleRate,
                channelMode.channelCount,
                bitrateKbps,
            )
            format.isRawAacAdts -> AdtsAudioFileWriter(this, target, codec, sampleRate, channelMode.channelCount, bitrateKbps)
            format.isRawAmr -> RawAmrAudioFileWriter(this, target, codec, sampleRate, channelMode.channelCount, bitrateKbps)
            format.isTransportStream -> TsAudioFileWriter(this, target, codec, sampleRate, channelMode.channelCount, bitrateKbps)
            else -> throw IOException("Unsupported output format: ${format.prefValue}")
        }
    }

    private fun deleteIfEmpty(target: RecordingOutputTarget?) {
        if (target == null) {
            return
        }
        if (resolveOutputTargetSize(this, target) == 0L) {
            when (target.storageType) {
                RecordingStorageType.FILE -> target.file?.delete()
                RecordingStorageType.DOCUMENT -> {
                    androidx.documentfile.provider.DocumentFile.fromSingleUri(this, requireNotNull(target.uri))?.delete()
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun requireExportedOutput(target: RecordingOutputTarget) {
        val size = resolveOutputTargetSize(this, target)
        if (size > 0L) {
            return
        }
        deleteIfEmpty(target)
        throw IOException("Export produced empty output: ${target.displayName}")
    }

    private fun currentCodecSummary(): String {
        return buildCodecSummary(
            effectiveOutputFormat,
            effectiveOutputCodec,
            sampleRate,
            channelMode.channelCount,
            getConfiguredCodecBitrateKbps(this, effectiveOutputCodec, sampleRate, channelMode.channelCount),
        )
    }

    private fun configuredCodecBitrateKbps(): Int? {
        val codec = effectiveOutputCodec
        return getConfiguredCodecBitrateKbps(this, codec, sampleRate, channelMode.channelCount)
    }

    private fun configuredRetentionSampleBytes(): Long {
        return getConfiguredMemorySizeBytes(
            context = this,
            sampleRate = sampleRate,
            channelMode = channelMode,
            format = effectiveOutputFormat,
            codec = effectiveOutputCodec,
            bitrateKbps = configuredCodecBitrateKbps(),
        )
    }

    private fun configuredWorkingMemorySizeBytes(): Long {
        return getConfiguredWorkingMemorySizeBytes(
            context = this,
            sampleRate = sampleRate,
            channelMode = channelMode,
            format = effectiveOutputFormat,
            codec = effectiveOutputCodec,
            bitrateKbps = configuredCodecBitrateKbps(),
        )
    }

    private fun configuredPersistentPcmSizeBytes(): Long {
        return getConfiguredPersistentPcmSizeBytes(
            context = this,
            sampleRate = sampleRate,
            channelMode = channelMode,
            format = effectiveOutputFormat,
            codec = effectiveOutputCodec,
            bitrateKbps = configuredCodecBitrateKbps(),
        )
    }

    private fun availableBufferedSampleBytes(): Long {
        val historyBytes = if (::liveExportHistory.isInitialized) liveExportHistory.countRetainedSampleBytes() else 0L
        if (historyBytes > 0L) {
            return historyBytes
        }
        return maxOf(
            if (::persistentAudioRingStore.isInitialized) persistentAudioRingStore.countFilledBytes() else 0L,
            audioMemory.countFilled(),
        )
    }

    private fun currentRawBufferedSampleBytes(): Long {
        return maxOf(
            if (::persistentAudioRingStore.isInitialized) persistentAudioRingStore.countFilledBytes() else 0L,
            audioMemory.countFilled(),
        )
    }

    private fun readBufferedPcm(
        skipBytes: Long,
        maxBytes: Long,
        reader: AudioMemory.Consumer,
    ): Boolean {
        val normalizedSkip = skipBytes.coerceAtLeast(0L)
        val normalizedMax = maxBytes.coerceAtLeast(0L)
        if (normalizedMax <= 0L) {
            return false
        }
        val persistedBytes = if (::persistentAudioRingStore.isInitialized) persistentAudioRingStore.countFilledBytes() else 0L
        if (persistedBytes >= normalizedSkip + normalizedMax) {
            persistentAudioRingStore.read(normalizedSkip, normalizedMax, reader)
            return true
        }
        val inMemoryBytes = audioMemory.countFilled()
        if (inMemoryBytes >= normalizedSkip + normalizedMax) {
            audioMemory.read(normalizedSkip, normalizedMax, reader)
            return true
        }
        return false
    }

    private fun canExportBufferedAudio(): Boolean {
        if (historyReencodePending || historyReencoding) {
            return false
        }
        return availableBufferedSampleBytes() > 0L || state == STATE_LISTENING || state == STATE_PAUSED
    }

    private fun flushAudioRecord() {
        check(audioHandler.looper == Looper.myLooper())
        if (audioRecord == null) {
            return
        }
        audioHandler.removeCallbacks(audioReader)
        audioReader.run()
    }

    private val filler: AudioMemory.Consumer = AudioMemory.Consumer { array, offset, count ->
        val currentRecord = audioRecord ?: return@Consumer 0
        val read = currentRecord.read(array, offset, count, AudioRecord.READ_NON_BLOCKING)
        when (read) {
            AudioRecord.ERROR_BAD_VALUE -> {
                Log.e(TAG, "AudioRecord returned ERROR_BAD_VALUE")
                return@Consumer 0
            }
            AudioRecord.ERROR_INVALID_OPERATION -> {
                Log.e(TAG, "AudioRecord returned ERROR_INVALID_OPERATION")
                return@Consumer 0
            }
            AudioRecord.ERROR -> {
                Log.e(TAG, "AudioRecord returned ERROR")
                return@Consumer 0
            }
        }

        val writer = audioFileWriter
        if (writer != null && read > 0) {
            writer.write(array, offset, read)
        }
        if (read > 0) {
            liveExportHistory.append(array, offset, read, System.currentTimeMillis())
            if (isDiskBufferCacheEnabled(this@TimeTravelService)) {
                persistentAudioRingStore.append(
                    array = array,
                    offset = offset,
                    count = read,
                    capacityBytes = configuredPersistentPcmSizeBytes(),
                    sampleRate = sampleRate,
                    channelCount = channelMode.channelCount,
                )
            }
        }

        if (read == count) {
            audioHandler.post(audioReader)
        } else {
            val bufferSizeInSeconds = currentRecord.bufferSizeInFrames / maxOf(sampleRate, 1).toFloat()
            var delaySeconds = bufferSizeInSeconds - 1f
            delaySeconds = maxOf(delaySeconds, bufferSizeInSeconds * 0.5f)
            delaySeconds = minOf(delaySeconds, bufferSizeInSeconds * 0.9f)
            audioHandler.postDelayed(audioReader, (delaySeconds * 1000f).toLong())
        }
        read
    }

    private val audioReader: Runnable = Runnable {
        try {
            audioMemory.fill(filler)
        } catch (e: Exception) {
            val fileName = recordingTarget?.displayName ?: getString(R.string.recording)
            val errorMessage = getString(R.string.error_during_recording_into) + fileName
            Log.e(TAG, errorMessage, e)
            showToast(errorMessage)
            stopRecording(TimeTravelFragment.NotifyFileReceiver(this))
        }
    }

    fun getState(callback: StateCallback) {
        val listeningEnabled = state == STATE_LISTENING || state == STATE_RECORDING
        val recording = state == STATE_RECORDING

        audioHandler.post {
            val stats = audioMemory.getStats(fillRate)
            val configuredRetentionBytes = configuredRetentionSampleBytes()
            val retainedBytes = availableBufferedSampleBytes()
            val memorizedBytes = (retainedBytes + stats.estimation).coerceAtMost(configuredRetentionBytes)
            var recordedBytes = 0L
            val writer = audioFileWriter
            if (writer != null) {
                recordedBytes += writer.totalSampleBytesWritten
                recordedBytes += stats.estimation
            }
            val finalRecordedBytes = recordedBytes
            mainHandler.post {
                callback.state(
                    listeningEnabled,
                    recording,
                    memorizedBytes * bytesToSeconds,
                    configuredRetentionBytes * bytesToSeconds,
                    finalRecordedBytes * bytesToSeconds,
                    historyReencodePending,
                    historyReencoding,
                    historyReencodeProcessedBytes,
                    historyReencodeTotalBytes,
                )
            }
        }
    }

    fun getConfigurationSnapshot(): RecorderConfigurationSnapshot {
        return RecorderConfigurationSnapshot(
            format = effectiveOutputFormat,
            codec = effectiveOutputCodec,
            sampleRate = sampleRate,
            sourceMode = sourceMode,
            channelMode = channelMode,
            routeMode = inputRouteMode,
        )
    }

    fun getChunkDebugSnapshot(callback: ChunkDebugCallback) {
        val listeningEnabled = state == STATE_LISTENING || state == STATE_RECORDING
        val recording = state == STATE_RECORDING
        audioHandler.post {
            val rawHistory = if (::liveExportHistory.isInitialized) liveExportHistory.debugSnapshot() else null
            val snapshot =
                ChunkDebugSnapshot(
                    listeningEnabled = listeningEnabled,
                    recording = recording,
                    format = effectiveOutputFormat,
                    codec = effectiveOutputCodec,
                    sampleRate = sampleRate,
                    channelCount = channelMode.channelCount,
                    history = rawHistory?.let { history ->
                        ChunkHistorySnapshot(
                            segmentCount = history.segmentCount,
                            totalSampleBytes = history.totalSampleBytes,
                            currentSegmentSampleBytes = history.currentSegmentSampleBytes,
                            nextSegmentStartMillis = history.nextSegmentStartMillis,
                            format = history.format,
                            codec = history.codec,
                            sampleRate = history.sampleRate,
                            channelCount = history.channelCount,
                            chunks = history.chunks.map { chunk ->
                                ChunkHistoryItem(
                                    fileName = chunk.fileName,
                                    filePath = chunk.filePath,
                                    startedAtMillis = chunk.startedAtMillis,
                                    endedAtMillis = chunk.endedAtMillis,
                                    sampleBytes = chunk.sampleBytes,
                                    fileSizeBytes = chunk.fileSizeBytes,
                                    format = chunk.format,
                                    codec = chunk.codec,
                                    sampleRate = chunk.sampleRate,
                                    channelCount = chunk.channelCount,
                                    active = chunk.active,
                                )
                            },
                            operations = history.operations.map { operation ->
                                ChunkOperationItem(
                                    id = operation.id,
                                    kind = operation.kind.name,
                                    sourcePaths = operation.sourcePaths,
                                    targetSampleBytes = operation.targetSampleBytes,
                                    startedAtMillis = operation.startedAtMillis,
                                )
                            },
                        )
                    },
                    historyReencodePending = historyReencodePending,
                    historyReencoding = historyReencoding,
                    historyReencodeProcessedBytes = historyReencodeProcessedBytes,
                    historyReencodeTotalBytes = historyReencodeTotalBytes,
                )
            mainHandler.post {
                callback.snapshot(snapshot)
            }
        }
    }

    fun debugCompactHistory(
        callback: ChunkActionCallback? = null,
    ) {
        if (!isDebuggableBuild()) {
            return
        }
        exportHandler.post {
            val mergedCount =
                try {
                    if (::liveExportHistory.isInitialized) liveExportHistory.debugCompactAllChunksNow() else 0
                } catch (t: Throwable) {
                    Log.w(TAG, "Debug history compaction failed", t)
                    0
                }
            val message =
                if (mergedCount > 0) {
                    getString(R.string.chunks_merge_done)
                } else {
                    getString(R.string.chunks_merge_failed)
                }
            mainHandler.post {
                callback?.completed(mergedCount > 0, message)
            }
        }
    }

    fun debugExportChunk(
        filePath: String,
        receiver: AudioFileReceiver?,
        callback: ChunkActionCallback? = null,
    ) {
        if (!isDebuggableBuild()) {
            return
        }
        exportHandler.post {
            try {
                if (!::liveExportHistory.isInitialized) {
                    throw IOException("History unavailable")
                }
                val descriptor = liveExportHistory.debugChunkExportDescriptor(filePath)
                    ?: throw IOException("Chunk unavailable")
                val displayName = "${buildRecordingBaseName(descriptor.startedAtMillis)}-chunk.${descriptor.extension}"
                val outTarget = createOutputTarget(this@TimeTravelService, displayName, descriptor.mimeType, descriptor.startedAtMillis)
                val exported = liveExportHistory.debugExportChunkToTarget(filePath, outTarget)
                    ?: throw IOException("Chunk export failed")
                requireExportedOutput(outTarget)
                val recording = buildRecordingEntity(
                    this@TimeTravelService,
                    outTarget,
                    exported.durationMillis,
                    buildCodecSummary(
                        exported.format,
                        exported.codec,
                        exported.sampleRate,
                        exported.channelCount,
                        exported.bitrateKbps,
                    ),
                )
                notifyReceiver(receiver, recording)
                mainHandler.post {
                    callback?.completed(true, getString(R.string.chunks_export_done))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Debug chunk export failed", e)
                mainHandler.post {
                    callback?.completed(false, getString(R.string.chunks_export_failed))
                }
            }
        }
    }

    fun clearBuffer() {
        if (state == STATE_RECORDING) {
            return
        }
        audioHandler.post {
            historyReencodePending = false
            historyReencoding = false
            historyReencodeProcessedBytes = 0L
            historyReencodeTotalBytes = 0L
            audioMemory.clear()
            liveExportHistory.clear()
            persistentAudioRingStore.clear()
        }
    }

    fun startHistoryReencode(): Boolean {
        if (state == STATE_RECORDING || historyReencoding || !historyReencodePending) {
            return false
        }
        historyReencoding = true
        historyReencodeProcessedBytes = 0L
        historyReencodeTotalBytes = currentRawBufferedSampleBytes()
        exportHandler.post { performHistoryReencode() }
        return true
    }

    private fun maybeStartHistoryReencode() {
        if (!historyReencodePending || historyReencoding || state == STATE_RECORDING) {
            return
        }
        historyReencoding = true
        historyReencodeProcessedBytes = 0L
        historyReencodeTotalBytes = currentRawBufferedSampleBytes()
        exportHandler.post { performHistoryReencode() }
    }

    private val bytesToSeconds: Float
        get() = if (fillRate > 0) 1f / fillRate else 0f

    private fun updateLiveExportHistoryConfiguration(memorySizeOverride: Long? = null) {
        if (!::liveExportHistory.isInitialized) {
            return
        }
        val codec = effectiveOutputCodec
        val retentionBytes = memorySizeOverride ?: configuredRetentionSampleBytes()
        liveExportHistory.updateConfiguration(
            format = effectiveOutputFormat,
            codec = codec,
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
            bitrateKbps = getConfiguredCodecBitrateKbps(this, codec, sampleRate, channelMode.channelCount),
            retentionBytes = retentionBytes,
            segmentDurationMillis = getConfiguredHistoryChunkSeconds(this).toLong() * 1000L,
            compactionTargetSampleBytes = configuredAutoMergeTargetSampleBytes(
                context = this,
                retentionBytes = retentionBytes,
                sampleRate = sampleRate,
                channelCount = channelMode.channelCount,
                baseChunkSeconds = getConfiguredHistoryChunkSeconds(this),
            ),
        )
    }

    private fun restoredOrConfiguredMemorySize(): Long {
        val configured = configuredRetentionSampleBytes()
        val restored = persistentAudioRingStore.peekSnapshot()?.capacityBytes?.toLong() ?: 0L
        return maxOf(configured, restored)
    }

    private fun performHistoryReencode() {
        val snapshot =
            try {
                createHistoryReencodeSnapshot()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to snapshot retained PCM for history re-encode", e)
                completeHistoryReencode(false, getString(R.string.reencode_history_failed))
                return
            }

        if (snapshot == null) {
            historyReencodePending = false
            completeHistoryReencode(true, null)
            return
        }

        val expectedConfig = LiveExportHistory.Config(
            format = effectiveOutputFormat,
            codec = effectiveOutputCodec,
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
            bitrateKbps = configuredCodecBitrateKbps(),
        )

        var importedSegments: List<LiveExportHistory.ImportedSegment> = emptyList()
        try {
            importedSegments = encodeHistorySnapshotParallel(snapshot)
            val installed = liveExportHistory.replaceSourceSegmentsWithImported(snapshot.sourcePaths, expectedConfig, importedSegments)
            if (!installed) {
                throw IOException("Unable to install re-encoded history segments")
            }
            historyReencodePending = false
            completeHistoryReencode(true, getString(R.string.reencode_history_done))
        } catch (e: Exception) {
            Log.e(TAG, "History re-encode failed", e)
            importedSegments.forEach { it.file.delete() }
            liveExportHistory.releasePinnedSourcePaths(snapshot.sourcePaths)
            completeHistoryReencode(false, getString(R.string.reencode_history_failed))
        } finally {
            snapshot.rootDirectory.deleteRecursively()
        }
    }

    private fun createHistoryReencodeSnapshot(): HistoryReencodeSnapshot? {
        val historySnapshot = liveExportHistory.snapshotForHistoryReencode(
            reopenForContinuedCapture = state == STATE_LISTENING || state == STATE_RECORDING,
        ) ?: return null
        val retainedBytes = historySnapshot.totalSampleBytes
        if (retainedBytes <= 0L) {
            liveExportHistory.releasePinnedSourcePaths(historySnapshot.sourcePaths)
            return null
        }

        val rootDirectory = File(noBackupFilesDir, "${TimeTravelConfig.BUFFER_CACHE_FOLDER_NAME}/history-reencode-${SystemClock.elapsedRealtime()}").also { directory ->
            if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
                liveExportHistory.releasePinnedSourcePaths(historySnapshot.sourcePaths)
                throw IOException("Unable to create re-encode workspace: ${directory.absolutePath}")
            }
        }
        val chunkBytes = reencodeChunkSampleBytes()
        val startedAtMillis = System.currentTimeMillis() - (retainedBytes * 1000L / maxOf(fillRate, 1))
        val chunks = ArrayList<ReencodeChunk>()
        var currentChunkIndex = 0
        var currentChunkStartMillis = startedAtMillis
        var currentChunkBytes = 0L
        var currentChunkFile: File? = null
        var currentChunkOutput: BufferedOutputStream? = null

        fun closeChunk() {
            val output = currentChunkOutput ?: return
            output.close()
            val file = requireNotNull(currentChunkFile)
            chunks += ReencodeChunk(file, currentChunkStartMillis, currentChunkBytes)
            currentChunkOutput = null
            currentChunkFile = null
            currentChunkStartMillis += currentChunkBytes * 1000L / maxOf(fillRate, 1)
            currentChunkBytes = 0L
        }

        val readSucceeded =
            try {
                readBufferedPcm(0L, retainedBytes) { array, offset, count ->
                    var remaining = count
                    var readOffset = offset
                    while (remaining > 0) {
                        if (currentChunkOutput == null) {
                            currentChunkFile = File(rootDirectory, "chunk-$currentChunkIndex.pcm")
                            currentChunkOutput = BufferedOutputStream(FileOutputStream(requireNotNull(currentChunkFile)))
                            currentChunkIndex++
                        }
                        val toWrite = minOf(remaining.toLong(), chunkBytes - currentChunkBytes).toInt()
                        currentChunkOutput?.write(array, readOffset, toWrite)
                        currentChunkBytes += toWrite.toLong()
                        readOffset += toWrite
                        remaining -= toWrite
                        if (currentChunkBytes >= chunkBytes) {
                            closeChunk()
                        }
                    }
                    count
                }
            } finally {
                currentChunkOutput?.close()
                if (currentChunkBytes > 0L && currentChunkFile != null) {
                    chunks += ReencodeChunk(requireNotNull(currentChunkFile), currentChunkStartMillis, currentChunkBytes)
                }
            }
        if (!readSucceeded) {
            liveExportHistory.releasePinnedSourcePaths(historySnapshot.sourcePaths)
            throw IOException("Requested PCM range not available for history re-encode")
        }

        historyReencodeTotalBytes = retainedBytes
        return HistoryReencodeSnapshot(rootDirectory, chunks, retainedBytes, historySnapshot.sourcePaths)
    }

    private fun encodeHistorySnapshotParallel(
        snapshot: HistoryReencodeSnapshot,
    ): List<LiveExportHistory.ImportedSegment> {
        val parallelism = minOf(snapshot.chunks.size, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)).coerceAtLeast(1)
        val processedBytes = AtomicLong(0L)
        val executor = Executors.newFixedThreadPool(parallelism)
        val futures = ArrayList<Future<LiveExportHistory.ImportedSegment>>(snapshot.chunks.size)
        try {
            snapshot.chunks.forEachIndexed { index, chunk ->
                futures += executor.submit<LiveExportHistory.ImportedSegment> {
                    val targetFile = File(snapshot.rootDirectory, "encoded-$index.${effectiveOutputFormat.extension}.tmp")
                    val target = RecordingOutputTarget(
                        id = targetFile.absolutePath,
                        displayName = targetFile.name,
                        mimeType = effectiveOutputFormat.outputMimeType,
                        storageType = RecordingStorageType.FILE,
                        directoryId = snapshot.rootDirectory.absolutePath,
                        startedAtMillis = chunk.startedAtMillis,
                        file = targetFile,
                    )
                    createAudioFileWriter(target).use { writer ->
                        FileInputStream(chunk.file).use { input ->
                            val buffer = ByteArray(REENCODE_IO_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) {
                                    break
                                }
                                writer.write(buffer, 0, read)
                                historyReencodeProcessedBytes = processedBytes.addAndGet(read.toLong())
                            }
                        }
                    }
                    LiveExportHistory.ImportedSegment(
                        file = targetFile,
                        startedAtMillis = chunk.startedAtMillis,
                        sampleBytes = chunk.sampleBytes,
                    )
                }
            }
            return futures.map { it.get() }.sortedBy { it.startedAtMillis }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun completeHistoryReencode(
        success: Boolean,
        message: String?,
    ) {
        historyReencoding = false
        if (!success) {
            historyReencodeProcessedBytes = 0L
        } else if (!historyReencodePending) {
            historyReencodeProcessedBytes = historyReencodeTotalBytes
        }
        message?.let(::showToast)
    }

    private fun reencodeChunkSampleBytes(): Long {
        val frameBytes = maxOf(channelMode.channelCount * 2, 1)
        val configured = getConfiguredHistoryChunkSeconds(this).toLong() * maxOf(fillRate, 1).toLong()
        return (configured / frameBytes).coerceAtLeast(1L) * frameBytes
    }

    inner class BackgroundRecorderBinder : Binder() {
        val service: TimeTravelService
            get() = this@TimeTravelService
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
        }
        handleDebugCommand(intent)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        audioHandler.post { syncPersistentBufferFromMemory() }
        scheduleRestartIfNeeded()
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, TimeTravelActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setTicker(getString(R.string.recording))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .build()
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this@TimeTravelService, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun restorePersistedBufferIfNeeded(
        memorySize: Long = configuredRetentionSampleBytes(),
        workingMemorySize: Long = configuredWorkingMemorySizeBytes(),
    ) {
        if (!isDiskBufferCacheEnabled(this)) {
            persistentAudioRingStore.clear()
            return
        }
        val persisted = persistentAudioRingStore.peekSnapshot()
        val restoredCapacityBytes = persisted?.capacityBytes?.toLong() ?: 0L
        val targetMemorySize = maxOf(memorySize, restoredCapacityBytes)
        if (targetMemorySize <= 0L || workingMemorySize <= 0L) {
            return
        }
        persisted?.let { snapshot ->
            if (audioMemory.countFilled() == 0L && snapshot.sampleRate > 0 && snapshot.channelCount > 0) {
                sampleRate = snapshot.sampleRate
                channelMode = if (snapshot.channelCount >= 2) ChannelMode.STEREO else ChannelMode.MONO
                fillRate = sampleRate * channelMode.channelCount * 2
            }
        }
        updateLiveExportHistoryConfiguration(targetMemorySize)
        if (audioMemory.allocatedMemorySize != workingMemorySize) {
            audioMemory.allocate(workingMemorySize)
        }
        if (audioMemory.countFilled() > 0L) {
            return
        }

        val restored = persistentAudioRingStore.restoreInto(
            audioMemory = audioMemory,
            capacityBytes = minOf(targetMemorySize, workingMemorySize),
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
        )
        if (restored.restoredBytes > 0) {
            if (!isListeningEnabled() && state == STATE_READY) {
                state = STATE_PAUSED
            }
        }
    }

    private fun syncPersistentBufferFromMemory() {
        if (!isDiskBufferCacheEnabled(this)) {
            persistentAudioRingStore.clear()
            return
        }
        liveExportHistory.checkpoint()
        persistentAudioRingStore.checkpoint()
    }

    private fun flushAndPersistBeforeShutdown() {
        if (!::audioHandler.isInitialized) {
            return
        }
        runOnAudioThreadAndWait {
            if (::audioHandler.isInitialized) {
                audioHandler.removeCallbacks(audioReader)
            }
            if (state == STATE_RECORDING) {
                flushAudioRecord()
                val writer = audioFileWriter
                audioFileWriter = null
                recordingTarget = null
                runCatching { writer?.close() }
                    .onFailure { Log.e(TAG, "Error while closing recording file during shutdown", it) }
            }
            syncPersistentBufferFromMemory()
            liveExportHistory.pause()
            releaseAudioRecord()
        }
    }

    private fun runOnAudioThreadAndWait(block: () -> Unit) {
        if (!::audioHandler.isInitialized) {
            block()
            return
        }
        if (Looper.myLooper() == audioHandler.looper) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        audioHandler.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out waiting for audio-thread shutdown work")
        }
    }

    private fun handleDebugCommand(intent: Intent?) {
        if (!isDebuggableBuild()) {
            return
        }
        val action = intent?.action ?: return
        if (!action.startsWith(DEBUG_ACTION_PREFIX)) {
            return
        }
        Log.d(TAG, "handleDebugCommand action=$action")

        val seconds = intent.getFloatExtra(EXTRA_DEBUG_SECONDS, 0f)
        audioHandler.post {
            when (action) {
                ACTION_DEBUG_ENABLE_LISTENING -> mainHandler.post { enableListening() }
                ACTION_DEBUG_DISABLE_LISTENING -> mainHandler.post { disableListening() }
                ACTION_DEBUG_CLEAR_BUFFER -> if (state != STATE_RECORDING) {
                    audioMemory.clear()
                    liveExportHistory.clear()
                    persistentAudioRingStore.clear()
                }
                ACTION_DEBUG_INJECT_BUFFER -> injectDebugBuffer(seconds)
                ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS -> {
                    setConfiguredExportTreeUri(this@TimeTravelService, null)
                    writeDebugReport("force-app-storage-exports")
                }
                ACTION_DEBUG_EXPORT_FULL -> exportDebug(FULL_BUFFER_SECONDS)
                ACTION_DEBUG_EXPORT_SECONDS -> exportDebug(seconds)
                ACTION_DEBUG_CHECKPOINT -> syncPersistentBufferFromMemory()
                ACTION_DEBUG_LOG_STATE -> logDebugState()
                ACTION_DEBUG_DUMP_REPORT -> writeDebugReport("manual-dump")
            }
        }
    }

    private fun exportDebug(seconds: Float) {
        if (seconds <= 0f) {
            Log.w(TAG, "Debug export ignored; seconds=$seconds")
            return
        }
        if (!canExportBufferedAudio()) {
            Log.w(TAG, "Debug export ignored; state=$state")
            return
        }
        Log.d(TAG, "exportDebug seconds=$seconds available=${availableBufferedSampleBytes()}")
        dumpRecording(seconds, DebugAudioFileReceiver(), "")
    }

    private fun injectDebugBuffer(seconds: Float) {
        if (!isDebuggableBuild() || seconds <= 0f || state == STATE_RECORDING) {
            Log.w(TAG, "injectDebugBuffer ignored seconds=$seconds state=$state")
            return
        }
        val logicalRetentionBytes = configuredRetentionSampleBytes()
        val workingMemoryBytes = configuredWorkingMemorySizeBytes()
        val persistentBytes = configuredPersistentPcmSizeBytes()
        if (workingMemoryBytes <= 0L || persistentBytes <= 0L) {
            Log.w(TAG, "injectDebugBuffer ignored working=$workingMemoryBytes persistent=$persistentBytes")
            return
        }
        audioMemory.allocate(workingMemoryBytes)
        val totalBytes = (seconds * fillRate).toLong().coerceAtLeast(0L)
        val chunk = ByteArray(64 * 1024)
        var remaining = totalBytes
        var endedAtMillis = System.currentTimeMillis() - (seconds * 1000f).toLong()
        while (remaining > 0L) {
            val count = minOf(chunk.size.toLong(), remaining).toInt()
            audioMemory.write(chunk, 0, count)
            liveExportHistory.append(chunk, 0, count, endedAtMillis)
            if (isDiskBufferCacheEnabled(this)) {
                persistentAudioRingStore.append(
                    array = chunk,
                    offset = 0,
                    count = count,
                    capacityBytes = persistentBytes,
                    sampleRate = sampleRate,
                    channelCount = channelMode.channelCount,
                )
            }
            remaining -= count.toLong()
            endedAtMillis += maxOf(1L, count.toLong() * 1000L / maxOf(fillRate, 1))
        }
        state = STATE_PAUSED
        updateWakeLockState()
        updateLiveExportHistoryConfiguration(logicalRetentionBytes)
        syncPersistentBufferFromMemory()
        Log.d(
            TAG,
            "injectDebugBuffer seconds=$seconds logical=$logicalRetentionBytes working=$workingMemoryBytes persisted=$persistentBytes available=${availableBufferedSampleBytes()}",
        )
        writeDebugReport("inject-buffer-${seconds}s")
    }

    private fun logDebugState() {
        val stats = audioMemory.getStats(fillRate)
        val persisted = persistentAudioRingStore.peekSnapshot()
        val historyBytes = if (::liveExportHistory.isInitialized) liveExportHistory.countRetainedSampleBytes() else 0L
        Log.d(
            TAG,
            "debug-state state=$state filled=${stats.filled} total=${stats.total} overwriting=${stats.overwriting} " +
                "sampleRate=$sampleRate channels=${channelMode.channelCount} codec=${effectiveOutputCodec.prefValue} " +
                "format=${effectiveOutputFormat.prefValue} logicalRetention=${configuredRetentionSampleBytes()} " +
                "heapWorking=${audioMemory.allocatedMemorySize} historyBytes=$historyBytes " +
                "persistedFilled=${persisted?.filledBytes ?: 0} persistedCapacity=${persisted?.capacityBytes ?: 0}",
        )
    }

    private fun writeDebugReport(reason: String) {
        val reportFile = resolveDebugReportFile()
        val stats = audioMemory.getStats(fillRate)
        val persisted = persistentAudioRingStore.peekSnapshot()
        val history = liveExportHistory.debugSnapshot()
        val status =
            buildString {
                append("reason=").append(reason)
                append(" state=").append(state)
                append(" sampleRate=").append(sampleRate)
                append(" channelCount=").append(channelMode.channelCount)
                append(" format=").append(effectiveOutputFormat.prefValue)
                append(" codec=").append(effectiveOutputCodec.prefValue)
                append(" filled=").append(stats.filled)
                append(" total=").append(stats.total)
                append(" overwriting=").append(stats.overwriting)
                append(" persistedFilled=").append(persisted?.filledBytes ?: 0)
                append(" persistedCapacity=").append(persisted?.capacityBytes ?: 0)
                append(" persistedLastWrite=").append(persisted?.lastWriteAtMillis ?: 0)
                append(" historySegments=").append(history.segmentCount)
                append(" historyTotalSampleBytes=").append(history.totalSampleBytes)
                append(" historyCurrentSegmentBytes=").append(history.currentSegmentSampleBytes)
                append(" historyNextSegmentStart=").append(history.nextSegmentStartMillis ?: 0)
                append(" historyFiles=").append(history.segmentFiles.joinToString(","))
                append(" exportDir=").append(describeConfiguredOutputDirectory(this@TimeTravelService))
            }
        reportFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw IOException("Unable to create debug report directory: ${parent.absolutePath}")
            }
        }
        reportFile.appendText(status + "\n---\n")
        Log.d(TAG, "writeDebugReport $status path=${reportFile.absolutePath}")
        storeDebugStatus(status)
    }

    private fun resolveDebugReportFile(): File {
        val directory = getSavedRecordingsDirectory(this)
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw IOException("Unable to create recordings directory: ${directory.absolutePath}")
        }
        return File(directory, "debug-report.txt")
    }

    private fun storeDebugStatus(status: String) {
        runCatching {
            val clazz = Class.forName("app.smallthingz.timetravel.DebugStatusStore")
            val method = clazz.getMethod("write", Context::class.java, String::class.java)
            method.invoke(null, this, status)
        }
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun adoptPersistedBufferConfigurationIfNeeded() {
        val snapshot = persistentAudioRingStore.peekSnapshot() ?: return
        sampleRate = snapshot.sampleRate
        channelMode = if (snapshot.channelCount >= 2) ChannelMode.STEREO else ChannelMode.MONO
        fillRate = sampleRate * channelMode.channelCount * 2
    }

    private fun updateWakeLockState() {
        if (!isWakeLockEnabled(this)) {
            releaseWakeLock()
            return
        }

        val shouldHoldWakeLock = state == STATE_LISTENING || state == STATE_RECORDING
        if (!shouldHoldWakeLock) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:timeTravelBuffer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    private fun scheduleRestartIfNeeded() {
        if (!isAggressiveRestartEnabled(this)) {
            return
        }

        val shouldKeepBuffer =
            state == STATE_PAUSED ||
                state == STATE_LISTENING ||
                state == STATE_RECORDING ||
                audioMemory.countFilled() > 0 ||
                persistentAudioRingStore.hasData()
        if (!shouldKeepBuffer && !isListeningEnabled()) {
            return
        }

        val restartServiceIntent = Intent(applicationContext, javaClass).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        (getSystemService(ALARM_SERVICE) as? AlarmManager)?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1_000L,
            restartServicePendingIntent,
        )
    }

    interface AudioFileReceiver {
        fun fileReady(recording: RecordingEntity)

        fun fileFailed(message: String) = Unit
    }

    interface StateCallback {
        fun state(
            listeningEnabled: Boolean,
            recording: Boolean,
            memorized: Float,
            totalMemory: Float,
            recorded: Float,
            historyReencodePending: Boolean,
            historyReencoding: Boolean,
            historyReencodeProcessedBytes: Long,
            historyReencodeTotalBytes: Long,
        )
    }

    interface ChunkDebugCallback {
        fun snapshot(data: ChunkDebugSnapshot)
    }

    interface ChunkActionCallback {
        fun completed(success: Boolean, message: String)
    }

    data class ChunkDebugSnapshot(
        val listeningEnabled: Boolean,
        val recording: Boolean,
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleRate: Int,
        val channelCount: Int,
        val history: ChunkHistorySnapshot?,
        val historyReencodePending: Boolean,
        val historyReencoding: Boolean,
        val historyReencodeProcessedBytes: Long,
        val historyReencodeTotalBytes: Long,
    )

    data class ChunkHistorySnapshot(
        val segmentCount: Int,
        val totalSampleBytes: Long,
        val currentSegmentSampleBytes: Long,
        val nextSegmentStartMillis: Long?,
        val format: String?,
        val codec: String?,
        val sampleRate: Int,
        val channelCount: Int,
        val chunks: List<ChunkHistoryItem>,
        val operations: List<ChunkOperationItem>,
    )

    data class ChunkHistoryItem(
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

    data class ChunkOperationItem(
        val id: String,
        val kind: String,
        val sourcePaths: List<String>,
        val targetSampleBytes: Long,
        val startedAtMillis: Long,
    )

    data class RecorderConfigurationSnapshot(
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleRate: Int,
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
    )

    private data class OperationalConfig(
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleRate: Int,
    )

    private data class ReencodeChunk(
        val file: File,
        val startedAtMillis: Long,
        val sampleBytes: Long,
    )

    private data class HistoryReencodeSnapshot(
        val rootDirectory: File,
        val chunks: List<ReencodeChunk>,
        val totalSampleBytes: Long,
        val sourcePaths: List<String>,
    )

    enum class ApplySettingsResult {
        APPLIED_NOW,
        REENCODE_REQUIRED,
        DEFERRED_UNTIL_RESTART,
        BLOCKED_RECORDING,
    }

    private inner class DebugAudioFileReceiver : AudioFileReceiver {
        override fun fileReady(recording: RecordingEntity) {
            Log.d(TAG, "debug-export-ready id=${recording.id} name=${recording.displayName} size=${recording.sizeBytes}")
            writeDebugReport("export-ready:${recording.displayName}:${recording.sizeBytes}")
        }

        override fun fileFailed(message: String) {
            Log.e(TAG, "debug-export-failed $message")
            writeDebugReport("export-failed:$message")
        }
    }

    companion object {
        val TAG: String = TimeTravelService::class.java.simpleName
        const val NOTIFICATION_CHANNEL_ID = "TimeTravelRecorderChannel"
        const val FOREGROUND_NOTIFICATION_ID = 458
        const val MIN_AUDIO_RECORD_BUFFER_SIZE = 16 * 1024
        const val REENCODE_IO_BUFFER_BYTES = 256 * 1024
        const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        const val HISTORY_COMPACTION_IDLE_DELAY_MS = 1_500L
        const val DEBUG_ACTION_PREFIX = "app.smallthingz.timetravel.debug."
        const val ACTION_DEBUG_ENABLE_LISTENING = "${DEBUG_ACTION_PREFIX}ENABLE_LISTENING"
        const val ACTION_DEBUG_DISABLE_LISTENING = "${DEBUG_ACTION_PREFIX}DISABLE_LISTENING"
        const val ACTION_DEBUG_CLEAR_BUFFER = "${DEBUG_ACTION_PREFIX}CLEAR_BUFFER"
        const val ACTION_DEBUG_INJECT_BUFFER = "${DEBUG_ACTION_PREFIX}INJECT_BUFFER"
        const val ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS = "${DEBUG_ACTION_PREFIX}FORCE_APP_STORAGE_EXPORTS"
        const val ACTION_DEBUG_EXPORT_FULL = "${DEBUG_ACTION_PREFIX}EXPORT_FULL"
        const val ACTION_DEBUG_EXPORT_SECONDS = "${DEBUG_ACTION_PREFIX}EXPORT_SECONDS"
        const val ACTION_DEBUG_CHECKPOINT = "${DEBUG_ACTION_PREFIX}CHECKPOINT"
        const val ACTION_DEBUG_LOG_STATE = "${DEBUG_ACTION_PREFIX}LOG_STATE"
        const val ACTION_DEBUG_DUMP_REPORT = "${DEBUG_ACTION_PREFIX}DUMP_REPORT"
        const val EXTRA_DEBUG_SECONDS = "seconds"

        const val STATE_READY = 0
        const val STATE_LISTENING = 1
        const val STATE_RECORDING = 2
        const val STATE_PAUSED = 3
    }

}
