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
import java.io.InterruptedIOException
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

@SuppressLint("ImplicitSamInstance")
class TimeTravelService : Service() {
    @Volatile
    private var sampleRate = TimeTravelConfig.PREFERRED_DEFAULT_SAMPLE_RATE

    @Volatile
    private var fillRate = 96_000

    @Volatile
    private var audioSource = AudioSourceMode.defaultMode().sourceValue

    @Volatile
    private var sourceMode = AudioSourceMode.defaultMode()

    @Volatile
    private var channelMode = ChannelMode.MONO

    @Volatile
    private var pcmSampleFormat = PcmSampleFormat.PCM_16

    @Volatile
    private var outputFormat = ExportFormat.WAV

    @Volatile
    private var outputCodec = ExportCodec.PCM_16

    @Volatile
    private var outputBitrateKbps: Int = -1

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

    @Volatile
    private var activeExportToken: ExportCancellationToken? = null

    @Volatile
    private var activeExportFuture: Future<*>? = null

    @Volatile
    private var activeExportReceiver: AudioFileReceiver? = null

    @Volatile
    private var cachedRetentionSampleBytes = 0L

    @Volatile
    private var cachedWorkingMemorySizeBytes = 0L

    @Volatile
    private var cachedPersistentPcmSizeBytes = 0L

    private val audioMemory = AudioMemory()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var audioThread: HandlerThread
    private lateinit var audioHandler: Handler
    private lateinit var exportThread: HandlerThread
    private lateinit var exportHandler: Handler
    private lateinit var exportWorkExecutor: ExecutorService
    private lateinit var persistentAudioRingStore: PersistentAudioRingStore

    private var wakeLock: PowerManager.WakeLock? = null

    private var statePollListeningEnabled = false
    private var statePollRecording = false
    @Volatile
    private var statePollCallback: StateCallback? = null
    private var statePollConfiguredRetentionBytes = 0L
    private var statePollRetainedBytes = 0L
    private var statePollMemorizedBytes = 0L
    private var statePollFinalRecordedBytes = 0L

    private val statePollAudioTask: Runnable = object : Runnable {
        override fun run() {
            val stats = audioMemory.getStats(fillRate)
            statePollConfiguredRetentionBytes = configuredRetentionSampleBytes()
            statePollRetainedBytes = availableBufferedSampleBytes()
            statePollMemorizedBytes = (statePollRetainedBytes + stats.estimation).coerceAtMost(statePollConfiguredRetentionBytes)
            var recordedBytes = 0L
            val writer = audioFileWriter
            if (writer != null) {
                recordedBytes += writer.totalSampleBytesWritten
                recordedBytes += stats.estimation
            }
            statePollFinalRecordedBytes = recordedBytes
            mainHandler.post(statePollMainTask)
        }
    }

    private val statePollMainTask: Runnable = object : Runnable {
        override fun run() {
            if (historyReencodePending && !historyReencoding && statePollRetainedBytes <= 0L) {
                historyReencodePending = false
                historyReencodeProcessedBytes = 0L
                historyReencodeTotalBytes = 0L
            }
            statePollCallback?.state(
                statePollListeningEnabled,
                statePollRecording,
                statePollMemorizedBytes * bytesToSeconds,
                statePollConfiguredRetentionBytes * bytesToSeconds,
                statePollFinalRecordedBytes * bytesToSeconds,
                historyReencodePending,
                historyReencoding,
                historyReencodeProcessedBytes,
                historyReencodeTotalBytes,
            )
        }
    }

    override fun onCreate() {
        loadConfiguration()
        persistentAudioRingStore = PersistentAudioRingStore(this)
        adoptPersistedBufferConfigurationIfNeeded()
        audioThread = HandlerThread(TimeTravelConfig.THREAD_NAME_AUDIO, Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        audioHandler = Handler(audioThread.looper)
        exportThread = HandlerThread(TimeTravelConfig.THREAD_NAME_EXPORT, Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        exportHandler = Handler(exportThread.looper)
        exportWorkExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, TimeTravelConfig.THREAD_NAME_EXPORT_WORK).apply {
                priority = Thread.NORM_PRIORITY
                isDaemon = true
            }
        }
        audioHandler.post {
            restorePersistedBufferIfNeeded()
        }

        if (isListeningEnabled()) {
            innerStartListening()
        }
    }

    override fun onDestroy() {
        flushAndPersistBeforeShutdown()
        historyReencoding = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (::exportWorkExecutor.isInitialized) {
            exportWorkExecutor.shutdownNow()
        }
        persistentAudioRingStore.close()
        audioThread.quitSafely()
        exportThread.quitSafely()
        serviceScope.cancel()
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
        writer.println("  rawHistoryFile=${TimeTravelConfig.BUFFER_PCM_FILE_NAME}")
    }

    fun enableListening() {
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, true)
            .apply()
        innerStartListening()
    }

    fun disableListening() {
        getRecorderPreferences(this).edit()
            .putBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
            .apply()
        if (state == STATE_RECORDING) {
            stopRecording(TimeTravelFragment.NotifyFileReceiver(this, serviceScope))
        }
        innerStopListening()
    }

    private fun isListeningEnabled(): Boolean {
        return getRecorderPreferences(this).getBoolean(PrefKey.AUDIO_MEMORY_ENABLED, false)
    }

    private fun loadConfiguration() {
        var selectedSourceMode = getConfiguredAudioSourceMode(this)
        var selectedChannelMode = getConfiguredChannelMode(this)
        var selectedRouteMode = getConfiguredInputRouteMode(this)
        var selectedFormat = getConfiguredOutputFormat(this)
        var selectedCodec = getConfiguredOutputCodec(this)
        val selectedSampleFormat = getConfiguredPcmSampleFormat(this)

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
        pcmSampleFormat = selectedSampleFormat
        fillRate = sampleRate * selectedChannelMode.channelCount * selectedSampleFormat.bytesPerSample
        sourceMode = selectedSourceMode
        channelMode = selectedChannelMode
        audioSource = selectedSourceMode.sourceValue
        outputFormat = selectedFormat
        outputCodec = selectedCodec
        outputBitrateKbps = getConfiguredCodecBitrateKbps(this, outputCodec, sampleRate, channelMode.channelCount) ?: -1
        inputRouteMode = selectedRouteMode
        refreshCachedBufferSizing()
    }

    private fun refreshCachedBufferSizing() {
        cachedRetentionSampleBytes =
            getConfiguredMemorySizeBytes(
                context = this,
                sampleRate = sampleRate,
                channelMode = channelMode,
                format = effectiveOutputFormat,
                codec = effectiveOutputCodec,
                bitrateKbps = null,
                sampleFormat = pcmSampleFormat,
            )
        cachedWorkingMemorySizeBytes =
            getConfiguredWorkingMemorySizeBytes(
                context = this,
                sampleRate = sampleRate,
                channelMode = channelMode,
                format = effectiveOutputFormat,
                codec = effectiveOutputCodec,
                bitrateKbps = null,
                sampleFormat = pcmSampleFormat,
            )
        cachedPersistentPcmSizeBytes =
            getConfiguredPersistentPcmSizeBytes(
                context = this,
                sampleRate = sampleRate,
                channelMode = channelMode,
                format = effectiveOutputFormat,
                codec = effectiveOutputCodec,
                bitrateKbps = null,
                sampleFormat = pcmSampleFormat,
            )
    }

    private fun resolveOperationalConfiguration(
        preferredSourceMode: AudioSourceMode,
        preferredChannelMode: ChannelMode,
        preferredRouteMode: InputRouteMode,
        preferredFormat: ExportFormat,
        preferredCodec: ExportCodec,
        preferredRate: Int,
    ): OperationalConfig? {
        val formatCandidates = buildList {
            add(preferredFormat); val formats = supportedFormats()
            for (f in formats) if (f != preferredFormat) add(f)
        }
        val routeCandidates = buildList {
            add(preferredRouteMode); val modes = supportedInputRouteModes(this@TimeTravelService)
            for (m in modes) if (m != preferredRouteMode) add(m)
        }
        val sourceCandidates = buildList {
            add(preferredSourceMode); val modes = AudioSourceMode.availableModes()
            for (m in modes) if (m != preferredSourceMode) add(m)
        }
        val channelCandidates = buildList {
            add(preferredChannelMode)
            for (m in ChannelMode.entries) if (m != preferredChannelMode) add(m)
        }

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
            releaseAudioRecord()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMode.inputChannelMask,
            pcmSampleFormat.audioEncoding,
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
                        .setEncoding(pcmSampleFormat.audioEncoding)
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

    fun cancelCurrentExport(): Boolean {
        val token = activeExportToken ?: return false
        val future = activeExportFuture
        val receiver = activeExportReceiver
        token.cancelled.set(true)
        if (!token.started.get() && future?.cancel(true) == true) {
            if (activeExportToken === token) {
                activeExportToken = null
                activeExportFuture = null
                activeExportReceiver = null
            }
            notifyReceiverCancelled(receiver)
            return true
        }
        future?.cancel(true)
        return true
    }

    private fun exportBufferedRange(
        skipBytes: Long,
        useBytes: Long,
        receiver: AudioFileReceiver,
        newFileName: String,
    ) {
        if (useBytes <= 0L) {
            notifyReceiverFailure(receiver, getString(R.string.retention_time_invalid))
            return
        }
        val bytesAvailable = availableBufferedSampleBytes()
        val startedAtMillis = System.currentTimeMillis() - 1000L * (bytesAvailable - skipBytes) / maxOf(fillRate, 1)
        val exportFormat = effectiveOutputFormat
        val exportCodec = effectiveOutputCodec
        val exportToken = ExportCancellationToken(nextExportTokenId.getAndIncrement())
        activeExportToken = exportToken
        activeExportReceiver = receiver
        val exportTask =
            object : FutureTask<Unit>(
                Callable {
                    var outTarget: RecordingOutputTarget? = null
                    try {
                        ensureExportNotCancelled(exportToken)
                        outTarget =
                            try {
                            createOutputTarget(this@TimeTravelService, newFileName, startedAtMillis, exportFormat, exportCodec)
                        } catch (e: IOException) {
                            Log.e(TAG, "Unable to prepare export file", e)
                            val message = getString(R.string.cant_create_file_generic)
                            showToast(message)
                            notifyReceiverFailure(receiver, message)
                            return@Callable Unit
                        }
                        var durationMillis = 0L
                        val readSucceeded =
                            createAudioFileWriter(outTarget).use { writer ->
                                val didRead = readBufferedPcm(skipBytes, useBytes) { array, offset, count ->
                                    ensureExportNotCancelled(exportToken)
                                    writer.write(array, offset, count)
                                    count
                                }
                                ensureExportNotCancelled(exportToken)
                                durationMillis = (writer.totalSampleBytesWritten * bytesToSeconds * 1000f).toLong()
                                didRead
                            }
                        if (!readSucceeded) {
                            throw IOException("Requested PCM range not available in raw buffer")
                        }
                        requireExportedOutput(outTarget)
                        ensureExportNotCancelled(exportToken)
                        notifyReceiver(
                            receiver,
                            buildRecordingEntity(
                                this@TimeTravelService,
                                outTarget,
                                durationMillis,
                                currentCodecSummary(),
                            ),
                        )
                    } catch (cancelled: InterruptedIOException) {
                        Log.i(TAG, "Export cancelled for ${outTarget?.displayName ?: newFileName}")
                        deleteOutputTarget(outTarget)
                        notifyReceiverCancelled(receiver)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error while exporting raw history into ${outTarget?.displayName ?: newFileName}", e)
                        val message = getString(R.string.error_during_writing_history_into) + (outTarget?.displayName ?: newFileName)
                        showToast(message)
                        notifyReceiverFailure(receiver, message)
                        deleteIfEmpty(outTarget)
                    } finally {
                        if (exportToken.cancelled.get()) {
                            deleteOutputTarget(outTarget)
                        }
                        if (activeExportToken === exportToken) {
                            activeExportToken = null
                            activeExportFuture = null
                            activeExportReceiver = null
                        }
                        Thread.interrupted()
                    }
                    Unit
                },
            ) {
                override fun run() {
                    exportToken.started.set(true)
                    super.run()
                }
            }
        activeExportFuture = exportTask
        exportWorkExecutor.execute(exportTask)
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
                    stopRecording(TimeTravelFragment.NotifyFileReceiver(this@TimeTravelService, serviceScope))
                }
            }
        }
    }

    fun getMemorySize(): Long = configuredRetentionSampleBytes()

    fun setMemorySize(memorySize: Long) {
        getRecorderPreferences(this).edit()
            .putInt(PrefKey.RETENTION_MODE, RetentionMode.SIZE.ordinal)
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, memorySize.coerceAtLeast(1L))
            .apply()
        reloadConfiguration()
    }

    fun getSamplingRate(): Int = sampleRate

    fun setSampleRate(sampleRate: Int) {
        getRecorderPreferences(this).edit()
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
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
        val newSampleFormat = getConfiguredPcmSampleFormat(this)
        val newSampleRate = resolveOperationalSampleRate(
            this,
            getConfiguredSampleRate(this, newSourceMode, newRouteMode, newFormat, newCodec, newChannelMode),
            newSourceMode,
            newRouteMode,
            newFormat,
            newCodec,
            newChannelMode,
        )
        val captureConfigChanged =
            newSourceMode != sourceMode ||
                newChannelMode != channelMode ||
                newRouteMode != inputRouteMode ||
                newSampleRate != sampleRate ||
                newSampleFormat != pcmSampleFormat
        val hasRetainedBuffer = availableBufferedSampleBytes() > 0L
        updateWakeLockState()

        if (state != STATE_LISTENING || !isListeningEnabled()) {
            if (captureConfigChanged && hasRetainedBuffer) {
                return ApplySettingsResult.DEFERRED_UNTIL_RESTART
            }
            loadConfiguration()
            audioHandler.post {
                historyReencodePending = false
                historyReencoding = false
                historyReencodeProcessedBytes = 0L
                historyReencodeTotalBytes = 0L
                restorePersistedBufferIfNeeded()
            }
            return ApplySettingsResult.APPLIED_NOW
        }

        if (captureConfigChanged) {
            return ApplySettingsResult.DEFERRED_UNTIL_RESTART
        }

        loadConfiguration()
        audioHandler.post {
            audioMemory.allocate(configuredWorkingMemorySizeBytes())
            historyReencodePending = false
            historyReencoding = false
            historyReencodeProcessedBytes = 0L
            historyReencodeTotalBytes = 0L
            syncPersistentBufferFromMemory()
        }

        return ApplySettingsResult.APPLIED_NOW
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

    private fun notifyReceiverCancelled(receiver: AudioFileReceiver?) {
        receiver ?: return
        mainHandler.post { receiver.fileCancelled() }
    }

    @Throws(InterruptedIOException::class)
    private fun ensureExportNotCancelled(token: ExportCancellationToken) {
        if (token.cancelled.get()) {
            throw InterruptedIOException("Export cancelled")
        }
    }

    private val effectiveOutputFormat: ExportFormat
        get() = ExportFormat.WAV

    private val effectiveOutputCodec: ExportCodec
        get() = ExportCodec.PCM_16

    @Throws(IOException::class)
    private fun createAudioFileWriter(target: RecordingOutputTarget): AudioFileWriter {
        return WavAudioFileWriter(this, target, sampleRate, channelMode.channelCount, pcmSampleFormat)
    }

    private fun deleteIfEmpty(target: RecordingOutputTarget?) {
        if (target == null) {
            return
        }
        if (resolveOutputTargetSize(this, target) == 0L) {
            deleteOutputTarget(target)
        }
    }

    private fun deleteOutputTarget(target: RecordingOutputTarget?) {
        if (target == null) {
            return
        }
        when (target.storageType) {
            RecordingStorageType.FILE -> target.file?.delete()
            RecordingStorageType.DOCUMENT -> {
                val uri = target.uri ?: return
                androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)?.delete()
            }
        }
        runCatching {
            runBlocking {
                RecordingRepository.forget(this@TimeTravelService, target.id)
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to forget deleted export target ${target.id}", error)
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
            this,
            effectiveOutputFormat,
            effectiveOutputCodec,
            sampleRate,
            channelMode.channelCount,
            null,
        )
    }

    private fun configuredCodecBitrateKbps(): Int {
        val kbps = outputBitrateKbps
        return if (kbps >= 0) kbps else (getConfiguredCodecBitrateKbps(this, effectiveOutputCodec, sampleRate, channelMode.channelCount) ?: -1)
    }

    private fun configuredRetentionSampleBytes(): Long {
        return cachedRetentionSampleBytes
    }

    private fun configuredWorkingMemorySizeBytes(): Long {
        return cachedWorkingMemorySizeBytes
    }

    private fun configuredPersistentPcmSizeBytes(): Long {
        return cachedPersistentPcmSizeBytes
    }

    private fun availableBufferedSampleBytes(): Long {
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
            if (isDiskBufferCacheEnabled(this@TimeTravelService)) {
                persistentAudioRingStore.append(
                    array = array,
                    offset = offset,
                    count = read,
                    capacityBytes = configuredPersistentPcmSizeBytes(),
                    sampleRate = sampleRate,
                    channelCount = channelMode.channelCount,
                    sampleFormat = pcmSampleFormat,
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
            val fileName = recordingTarget?.displayName ?: getString(R.string.app_name)
            val errorMessage = getString(R.string.error_during_recording_into) + fileName
            Log.e(TAG, errorMessage, e)
            showToast(errorMessage)
            stopRecording(TimeTravelFragment.NotifyFileReceiver(this, serviceScope))
        }
    }

    fun getState(callback: StateCallback) {
        statePollCallback = callback
        statePollListeningEnabled = state == STATE_LISTENING || state == STATE_RECORDING
        statePollRecording = state == STATE_RECORDING
        audioHandler.post(statePollAudioTask)
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
            val snapshot =
                ChunkDebugSnapshot(
                    listeningEnabled = listeningEnabled,
                    recording = recording,
                    format = effectiveOutputFormat,
                    codec = effectiveOutputCodec,
                    sampleRate = sampleRate,
                    channelCount = channelMode.channelCount,
                    history = null,
                    historyReencodePending = false,
                    historyReencoding = false,
                    historyReencodeProcessedBytes = 0L,
                    historyReencodeTotalBytes = 0L,
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
        mainHandler.post {
            callback?.completed(false, getString(R.string.chunks_merge_failed))
        }
    }

    fun debugDeleteChunks(
        filePaths: List<String>,
        callback: ChunkActionCallback? = null,
    ) {
        if (!isDebuggableBuild()) {
            return
        }
        mainHandler.post {
            callback?.completed(false, getString(R.string.chunks_delete_failed))
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
        mainHandler.post {
            callback?.completed(false, getString(R.string.chunks_export_failed))
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
            persistentAudioRingStore.clear()
        }
    }

    fun startHistoryReencode(): Boolean {
        return false
    }

    private val bytesToSeconds: Float
        get() = if (fillRate > 0) 1f / fillRate else 0f

    inner class BackgroundRecorderBinder : Binder() {
        val service: TimeTravelService
            get() = this@TimeTravelService
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (state == STATE_LISTENING || state == STATE_RECORDING) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setTicker(getString(R.string.app_name))
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
                pcmSampleFormat = PcmSampleFormat.entries.firstOrNull { it.bytesPerSample == snapshot.bytesPerSample } ?: PcmSampleFormat.PCM_16
                fillRate = sampleRate * channelMode.channelCount * pcmSampleFormat.bytesPerSample
            }
        }
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
            sampleFormat = pcmSampleFormat,
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
                    persistentAudioRingStore.clear()
                }
                ACTION_DEBUG_INJECT_BUFFER -> injectDebugBuffer(seconds)
                ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS -> {
                    setConfiguredExportTreeUri(this@TimeTravelService, null)
                    writeDebugReport("force-app-storage-exports")
                }
                ACTION_DEBUG_EXPORT_FULL -> exportDebug(FULL_BUFFER_SECONDS)
                ACTION_DEBUG_EXPORT_SECONDS -> exportDebug(seconds)
                ACTION_DEBUG_MERGE_ALL -> writeDebugReport("merge-all:0")
                ACTION_DEBUG_SET_OUTPUT_CONFIG -> {
                    val prefs = getRecorderPreferences(this@TimeTravelService)
                    val format = intent.getStringExtra(EXTRA_DEBUG_FORMAT)
                    val codec = intent.getStringExtra(EXTRA_DEBUG_CODEC)
                    val bitrateKbps = intent.getIntExtra(EXTRA_DEBUG_BITRATE_KBPS, Int.MIN_VALUE)
                    prefs.edit().apply {
                        format?.let { putString(PrefKey.OUTPUT_FORMAT, it) }
                        codec?.let { putString(PrefKey.OUTPUT_CODEC, it) }
                        if (bitrateKbps != Int.MIN_VALUE) {
                            putInt(PrefKey.OUTPUT_BITRATE_KBPS, bitrateKbps)
                        }
                    }.apply()
                    writeDebugReport(
                        "set-output-config:" +
                            "format=${format ?: "(unchanged)"}," +
                            "codec=${codec ?: "(unchanged)"}," +
                            "bitrate=${if (bitrateKbps == Int.MIN_VALUE) "(unchanged)" else bitrateKbps}",
                    )
                }
                ACTION_DEBUG_APPLY_SETTINGS -> {
                    val result =
                        try {
                            applyUpdatedPreferences().name
                        } catch (t: Throwable) {
                            Log.w(TAG, "Debug apply-settings failed", t)
                            "ERROR:" + t.javaClass.simpleName
                        }
                    writeDebugReport("apply-settings:" + result)
                }
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
        while (remaining > 0L) {
            val count = minOf(chunk.size.toLong(), remaining).toInt()
            audioMemory.write(chunk, 0, count)
            if (isDiskBufferCacheEnabled(this)) {
                persistentAudioRingStore.append(
                    array = chunk,
                    offset = 0,
                    count = count,
                    capacityBytes = persistentBytes,
                    sampleRate = sampleRate,
                    channelCount = channelMode.channelCount,
                    sampleFormat = pcmSampleFormat,
                )
            }
            remaining -= count.toLong()
        }
        state = STATE_PAUSED
        updateWakeLockState()
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
        val historyBytes = 0L
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
                append(" historySegments=0")
                append(" historyTotalSampleBytes=0")
                append(" historyCurrentSegmentBytes=0")
                append(" historyNextSegmentStart=0")
                append(" historyFiles=")
                append(" debugOperations=")
                append(" exportDir=").append(describeConfiguredOutputDirectory(this@TimeTravelService))
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
        return File(directory, DEBUG_REPORT_FILE_NAME)
    }

    private fun storeDebugStatus(status: String) {
        runCatching {
            val clazz = Class.forName(DEBUG_STATUS_STORE_CLASS_NAME)
            val method = clazz.getMethod("write", Context::class.java, String::class.java)
            method.invoke(null, this, status)
        }.onFailure { Log.w(TAG, "storeDebugStatus failed — class $DEBUG_STATUS_STORE_CLASS_NAME not available", it) }
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun adoptPersistedBufferConfigurationIfNeeded() {
        val snapshot = persistentAudioRingStore.peekSnapshot() ?: return
        sampleRate = snapshot.sampleRate
        channelMode = if (snapshot.channelCount >= 2) ChannelMode.STEREO else ChannelMode.MONO
        pcmSampleFormat = PcmSampleFormat.entries.firstOrNull { it.bytesPerSample == snapshot.bytesPerSample } ?: PcmSampleFormat.PCM_16
        fillRate = sampleRate * channelMode.channelCount * pcmSampleFormat.bytesPerSample
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
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, packageName + WAKE_LOCK_TAG_SUFFIX).apply {
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
        if (!isListeningEnabled()) {
            return
        }
        if (!isAggressiveRestartEnabled(this)) {
            return
        }

        val shouldKeepBuffer =
            state == STATE_PAUSED ||
                state == STATE_LISTENING ||
                state == STATE_RECORDING ||
                audioMemory.countFilled() > 0 ||
                persistentAudioRingStore.hasData()
        if (!shouldKeepBuffer) {
            return
        }

        val restartServiceIntent = Intent(applicationContext, javaClass).apply {
            setPackage(packageName)
        }
        val restartServicePendingIntent = PendingIntent.getService(
            this,
            1,
            restartServiceIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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

        fun fileCancelled() = Unit
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

    private data class ExportCancellationToken(
        val id: Long,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        // `started` flips in FutureTask.run() before any export work begins so queued
        // cancellations can be completed synchronously without racing the worker body.
        val started: AtomicBoolean = AtomicBoolean(false),
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
            writeDebugReport("export-ready:" + recording.displayName + ":" + recording.sizeBytes)
        }

        override fun fileFailed(message: String) {
            Log.e(TAG, "debug-export-failed $message")
            writeDebugReport("export-failed:" + message)
        }
    }

    companion object {
        val TAG: String = TimeTravelService::class.java.simpleName
        const val NOTIFICATION_CHANNEL_ID = "TimeTravelRecorderChannel"
        const val FOREGROUND_NOTIFICATION_ID = 458
        const val MIN_AUDIO_RECORD_BUFFER_SIZE = 16 * 1024
        const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        const val HISTORY_COMPACTION_MIN_IDLE_DELAY_MS = 1_500L
        const val HISTORY_COMPACTION_MAX_IDLE_DELAY_MS = 30_000L
        const val DEBUG_ACTION_PREFIX = TimeTravelConfig.DEBUG_ACTION_PREFIX
        val nextExportTokenId = AtomicLong(1L)
        const val ACTION_DEBUG_ENABLE_LISTENING = "${DEBUG_ACTION_PREFIX}ENABLE_LISTENING"
        const val ACTION_DEBUG_DISABLE_LISTENING = "${DEBUG_ACTION_PREFIX}DISABLE_LISTENING"
        const val ACTION_DEBUG_CLEAR_BUFFER = "${DEBUG_ACTION_PREFIX}CLEAR_BUFFER"
        const val ACTION_DEBUG_INJECT_BUFFER = "${DEBUG_ACTION_PREFIX}INJECT_BUFFER"
        const val ACTION_DEBUG_FORCE_APP_STORAGE_EXPORTS = "${DEBUG_ACTION_PREFIX}FORCE_APP_STORAGE_EXPORTS"
        const val ACTION_DEBUG_EXPORT_FULL = "${DEBUG_ACTION_PREFIX}EXPORT_FULL"
        const val ACTION_DEBUG_EXPORT_SECONDS = "${DEBUG_ACTION_PREFIX}EXPORT_SECONDS"
        const val ACTION_DEBUG_MERGE_ALL = "${DEBUG_ACTION_PREFIX}MERGE_ALL"
        const val ACTION_DEBUG_SET_OUTPUT_CONFIG = "${DEBUG_ACTION_PREFIX}SET_OUTPUT_CONFIG"
        const val ACTION_DEBUG_APPLY_SETTINGS = "${DEBUG_ACTION_PREFIX}APPLY_SETTINGS"
        const val ACTION_DEBUG_CHECKPOINT = "${DEBUG_ACTION_PREFIX}CHECKPOINT"
        const val ACTION_DEBUG_LOG_STATE = "${DEBUG_ACTION_PREFIX}LOG_STATE"
        const val ACTION_DEBUG_DUMP_REPORT = "${DEBUG_ACTION_PREFIX}DUMP_REPORT"
        const val EXTRA_DEBUG_SECONDS = TimeTravelConfig.EXTRA_SECONDS
        const val EXTRA_DEBUG_FORMAT = TimeTravelConfig.EXTRA_FORMAT
        const val EXTRA_DEBUG_CODEC = TimeTravelConfig.EXTRA_CODEC
        const val EXTRA_DEBUG_BITRATE_KBPS = TimeTravelConfig.EXTRA_BITRATE_KBPS
        const val DEBUG_REPORT_FILE_NAME = "debug-report.txt"
        const val DEBUG_STATUS_STORE_CLASS_NAME = "app.smallthingz.timetravel.DebugStatusStore"
        const val WAKE_LOCK_TAG_SUFFIX = ":timeTravelBuffer"
        const val CHUNK_EXPORT_NAME_SEPARATOR = "-chunk."

        const val STATE_READY = 0
        const val STATE_LISTENING = 1
        const val STATE_RECORDING = 2
        const val STATE_PAUSED = 3
    }

}
