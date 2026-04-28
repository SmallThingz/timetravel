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
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private var outputCodec = ExportCodec.WAV

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
                    if (::liveExportHistory.isInitialized) liveExportHistory.compactIfNeeded() else false
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
        var selectedCodec = getConfiguredOutputCodec(this)

        if (supportedSampleRates(this, selectedSourceMode, selectedRouteMode, selectedCodec, selectedChannelMode).isEmpty()) {
            if (
                selectedChannelMode != ChannelMode.MONO &&
                supportedSampleRates(this, selectedSourceMode, selectedRouteMode, selectedCodec, ChannelMode.MONO).isNotEmpty()
            ) {
                selectedChannelMode = ChannelMode.MONO
            } else {
                selectedCodec =
                    supportedCodecs().firstOrNull {
                        supportedSampleRates(this, selectedSourceMode, selectedRouteMode, it, selectedChannelMode).isNotEmpty()
                    }
                        ?: ExportCodec.WAV
            }
        }

        if (supportedSampleRates(this, selectedSourceMode, selectedRouteMode, selectedCodec, selectedChannelMode).isEmpty()) {
            val codecFallback =
                supportedCodecs().firstOrNull {
                    supportedSampleRates(this, selectedSourceMode, selectedRouteMode, it, selectedChannelMode).isNotEmpty()
                }
            if (codecFallback != null) {
                selectedCodec = codecFallback
            } else {
                selectedRouteMode = InputRouteMode.AUTO
                selectedSourceMode = AudioSourceMode.MIC
                selectedChannelMode = ChannelMode.MONO
                selectedCodec =
                    supportedCodecs().firstOrNull {
                        supportedSampleRates(this, selectedSourceMode, selectedRouteMode, it, selectedChannelMode).isNotEmpty()
                    }
                        ?: ExportCodec.WAV
            }
        }

        val requestedRate = getConfiguredSampleRate(this, selectedSourceMode, selectedRouteMode, selectedCodec, selectedChannelMode)
        val preferredRate = resolveOperationalSampleRate(
            this,
            requestedRate,
            selectedSourceMode,
            selectedRouteMode,
            selectedCodec,
            selectedChannelMode,
        )

        sampleRate = if (preferredRate > 0) preferredRate else 48_000
        fillRate = sampleRate * selectedChannelMode.channelCount * 2
        sourceMode = selectedSourceMode
        channelMode = selectedChannelMode
        audioSource = selectedSourceMode.sourceValue
        outputCodec = supportedCodecs().firstOrNull { it == selectedCodec && isCodecSupported(it, sampleRate, selectedChannelMode) }
            ?: supportedCodecs().firstOrNull { isCodecSupported(it, sampleRate, selectedChannelMode) }
            ?: ExportCodec.WAV
        inputRouteMode = selectedRouteMode
        updateLiveExportHistoryConfiguration()
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

        val memorySize = getConfiguredMemorySizeBytes(this, sampleRate, channelMode)
        audioHandler.post {
            releaseAudioRecord()
            audioMemory.allocate(memorySize)
            restorePersistedBufferIfNeeded(memorySize)

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
        check(state == STATE_LISTENING || state == STATE_PAUSED) { "Buffer unavailable" }

        audioHandler.post {
            flushAudioRecord()

            val prependBytes = (memorySeconds * fillRate).toInt()
            val bytesAvailable = audioMemory.countFilled()
            val skipBytes = maxOf(0, bytesAvailable - prependBytes)
            val useBytes = bytesAvailable - skipBytes
            val startedAtMillis = System.currentTimeMillis() - 1000L * useBytes / maxOf(fillRate, 1)
            val exportCodec = effectiveOutputCodec
            val exportConfig = LiveExportHistory.Config(
                codec = exportCodec,
                sampleRate = sampleRate,
                channelCount = channelMode.channelCount,
                bitrateKbps = getConfiguredCodecBitrateKbps(this@TimeTravelService, exportCodec, sampleRate, channelMode.channelCount),
            )

            val outTarget = try {
                createOutputTarget(this@TimeTravelService, newFileName, startedAtMillis, exportCodec)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to prepare export file", e)
                val message = getString(R.string.cant_create_file_generic)
                showToast(message)
                notifyReceiverFailure(receiver, message)
                return@post
            }
            val historySnapshot = liveExportHistory.snapshotForExport(
                requestedSampleBytes = useBytes.toLong(),
                reopenForContinuedCapture = state == STATE_LISTENING || state == STATE_RECORDING,
            )
            exportHandler.post {
                try {
                    var durationMillis = 0L
                    if (historySnapshot != null && historySnapshot.config == exportConfig) {
                        liveExportHistory.exportSnapshot(historySnapshot, outTarget)
                        durationMillis = (historySnapshot.requestedSampleBytes * bytesToSeconds * 1000f).toLong()
                    } else {
                        createAudioFileWriter(outTarget).use { writer ->
                            audioMemory.read(skipBytes) { array, offset, count ->
                                writer.write(array, offset, count)
                                0
                            }
                            durationMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
                        }
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
                        createAudioFileWriter(outTarget).use { writer ->
                            audioMemory.read(skipBytes) { array, offset, count ->
                                writer.write(array, offset, count)
                                0
                            }
                            durationMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
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

            val prependBytes = (prependedMemorySeconds * fillRate).toInt()
            val bytesAvailable = audioMemory.countFilled()
            val skipBytes = maxOf(0, bytesAvailable - prependBytes)
            val useBytes = bytesAvailable - skipBytes
            val startedAtMillis = System.currentTimeMillis() - 1000L * useBytes / maxOf(fillRate, 1)

            try {
                recordingTarget = createOutputTarget(this@TimeTravelService, null, startedAtMillis, effectiveOutputCodec)
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
                    audioMemory.read(skipBytes) { array, offset, count ->
                        writer?.write(array, offset, count)
                        0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error while priming recording into ${recordingTarget?.displayName}", e)
                    stopRecording(TimeTravelFragment.NotifyFileReceiver(this@TimeTravelService))
                }
            }
        }
    }

    fun getMemorySize(): Long = audioMemory.allocatedMemorySize

    fun setMemorySize(memorySize: Long) {
        val clamped = minOf(memorySize, getRetentionMemoryCapBytes())
        getRecorderPreferences(this).edit()
            .putString(TimeTravelConfig.RETENTION_MODE_KEY, TimeTravelConfig.RETENTION_MODE_SIZE)
            .putLong(TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY, clamped)
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
        val newCodec = getConfiguredOutputCodec(this)
        val newSampleRate = resolveOperationalSampleRate(
            this,
            getConfiguredSampleRate(this, newSourceMode, newRouteMode, newCodec, newChannelMode),
            newSourceMode,
            newRouteMode,
            newCodec,
            newChannelMode,
        )
        val captureConfigChanged =
            newSampleRate != sampleRate ||
                newSourceMode != sourceMode ||
                newChannelMode != channelMode ||
                newRouteMode != inputRouteMode
        val hasRetainedBuffer = audioMemory.countFilled() > 0 || persistentAudioRingStore.hasData()
        updateWakeLockState()

        if (state != STATE_LISTENING || !isListeningEnabled()) {
            if (captureConfigChanged && hasRetainedBuffer) {
                return ApplySettingsResult.DEFERRED_UNTIL_RESTART
            }
            loadConfiguration()
            audioHandler.post {
                syncPersistentBufferFromMemory()
                restorePersistedBufferIfNeeded()
            }
            return ApplySettingsResult.APPLIED_NOW
        }

        audioHandler.post {
            audioMemory.allocate(getConfiguredMemorySizeBytes(this, sampleRate, channelMode))
            syncPersistentBufferFromMemory()
            updateLiveExportHistoryConfiguration()
        }

        return if (captureConfigChanged) {
            ApplySettingsResult.DEFERRED_UNTIL_RESTART
        } else {
            loadConfiguration()
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

    private val effectiveOutputCodec: ExportCodec
        get() = if (isCodecSupported(outputCodec, sampleRate, channelMode)) {
            outputCodec
        } else {
            supportedCodecs().firstOrNull { isCodecSupported(it, sampleRate, channelMode) } ?: ExportCodec.WAV
        }

    @Throws(IOException::class)
    private fun createAudioFileWriter(target: RecordingOutputTarget): AudioFileWriter {
        return when (effectiveOutputCodec) {
            ExportCodec.WAV -> WavAudioFileWriter(this, target, sampleRate, channelMode.channelCount)
            else -> {
                EncodedAudioFileWriter(
                    this,
                    target,
                    effectiveOutputCodec,
                    sampleRate,
                    channelMode.channelCount,
                    getConfiguredCodecBitrateKbps(this, effectiveOutputCodec, sampleRate, channelMode.channelCount),
                )
            }
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

    private fun currentCodecSummary(): String {
        return buildCodecSummary(
            effectiveOutputCodec,
            sampleRate,
            channelMode.channelCount,
            getConfiguredCodecBitrateKbps(this, effectiveOutputCodec, sampleRate, channelMode.channelCount),
        )
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
                    capacityBytes = audioMemory.allocatedMemorySize,
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
            var recordedBytes = 0
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
                    (if (stats.overwriting) stats.total else stats.filled + stats.estimation) * bytesToSeconds,
                    stats.total * bytesToSeconds,
                    finalRecordedBytes * bytesToSeconds,
                )
            }
        }
    }

    fun getConfigurationSnapshot(): RecorderConfigurationSnapshot {
        return RecorderConfigurationSnapshot(
            codec = effectiveOutputCodec,
            sampleRate = sampleRate,
            sourceMode = sourceMode,
            channelMode = channelMode,
            routeMode = inputRouteMode,
        )
    }

    fun clearBuffer() {
        if (state == STATE_RECORDING) {
            return
        }
        audioHandler.post {
            audioMemory.clear()
            liveExportHistory.clear()
            persistentAudioRingStore.clear()
        }
    }

    private val bytesToSeconds: Float
        get() = if (fillRate > 0) 1f / fillRate else 0f

    private fun updateLiveExportHistoryConfiguration(memorySizeOverride: Long? = null) {
        if (!::liveExportHistory.isInitialized) {
            return
        }
        val codec = effectiveOutputCodec
        liveExportHistory.updateConfiguration(
            codec = codec,
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
            bitrateKbps = getConfiguredCodecBitrateKbps(this, codec, sampleRate, channelMode.channelCount),
            retentionBytes = memorySizeOverride ?: getConfiguredMemorySizeBytes(this, sampleRate, channelMode),
        )
    }

    private fun restoredOrConfiguredMemorySize(): Long {
        val configured = getConfiguredMemorySizeBytes(this, sampleRate, channelMode)
        val restored = persistentAudioRingStore.peekSnapshot()?.capacityBytes?.toLong() ?: 0L
        return maxOf(configured, restored)
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

    private fun restorePersistedBufferIfNeeded(memorySize: Long = getConfiguredMemorySizeBytes(this, sampleRate, channelMode)) {
        if (!isDiskBufferCacheEnabled(this)) {
            persistentAudioRingStore.clear()
            return
        }
        val persisted = persistentAudioRingStore.peekSnapshot()
        val restoredCapacityBytes = persisted?.capacityBytes?.toLong() ?: 0L
        val targetMemorySize = maxOf(memorySize, restoredCapacityBytes)
        if (targetMemorySize <= 0L) {
            return
        }
        persisted?.let { snapshot ->
            if (audioMemory.countFilled() == 0 && snapshot.sampleRate > 0 && snapshot.channelCount > 0) {
                sampleRate = snapshot.sampleRate
                channelMode = if (snapshot.channelCount >= 2) ChannelMode.STEREO else ChannelMode.MONO
                fillRate = sampleRate * channelMode.channelCount * 2
            }
        }
        updateLiveExportHistoryConfiguration(targetMemorySize)
        if (audioMemory.allocatedMemorySize != targetMemorySize) {
            audioMemory.allocate(targetMemorySize)
        }
        if (audioMemory.countFilled() > 0) {
            return
        }

        val restored = persistentAudioRingStore.restoreInto(
            audioMemory = audioMemory,
            capacityBytes = targetMemorySize,
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
        persistentAudioRingStore.replaceWith(audioMemory, sampleRate, channelMode.channelCount)
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
        if (state != STATE_LISTENING && state != STATE_PAUSED) {
            Log.w(TAG, "Debug export ignored; state=$state")
            return
        }
        dumpRecording(seconds, DebugAudioFileReceiver(), "")
    }

    private fun logDebugState() {
        val stats = audioMemory.getStats(fillRate)
        val persisted = persistentAudioRingStore.peekSnapshot()
        Log.d(
            TAG,
            "debug-state state=$state filled=${stats.filled} total=${stats.total} overwriting=${stats.overwriting} " +
                "sampleRate=$sampleRate channels=${channelMode.channelCount} codec=${effectiveOutputCodec.prefValue} " +
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
        reportFile.parentFile?.mkdirs()
        reportFile.appendText(status + "\n---\n")
        storeDebugStatus(status)
    }

    private fun resolveDebugReportFile(): File {
        val directory = getSavedRecordingsDirectory(this)
        if (!directory.exists()) {
            directory.mkdirs()
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
        )
    }

    data class RecorderConfigurationSnapshot(
        val codec: ExportCodec,
        val sampleRate: Int,
        val sourceMode: AudioSourceMode,
        val channelMode: ChannelMode,
        val routeMode: InputRouteMode,
    )

    enum class ApplySettingsResult {
        APPLIED_NOW,
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
        const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        const val HISTORY_COMPACTION_IDLE_DELAY_MS = 1_500L
        const val DEBUG_ACTION_PREFIX = "app.smallthingz.timetravel.debug."
        const val ACTION_DEBUG_ENABLE_LISTENING = "${DEBUG_ACTION_PREFIX}ENABLE_LISTENING"
        const val ACTION_DEBUG_DISABLE_LISTENING = "${DEBUG_ACTION_PREFIX}DISABLE_LISTENING"
        const val ACTION_DEBUG_CLEAR_BUFFER = "${DEBUG_ACTION_PREFIX}CLEAR_BUFFER"
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
