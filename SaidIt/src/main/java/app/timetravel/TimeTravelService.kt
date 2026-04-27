package app.timetravel

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.IOException

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
    private var liveAacExportHistory: LiveAacExportHistory? = null

    override fun onCreate() {
        loadConfiguration()
        audioThread = HandlerThread("timeTravelAudioThread", Process.THREAD_PRIORITY_AUDIO).also { it.start() }
        audioHandler = Handler(audioThread.looper)
        exportThread = HandlerThread("timeTravelExportThread", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        exportHandler = Handler(exportThread.looper)
        configureLiveExportHistory()
        if (isListeningEnabled()) {
            innerStartListening()
        }
    }

    override fun onDestroy() {
        stopRecording(null)
        innerStopListening()
        stopForeground(STOP_FOREGROUND_REMOVE)
        liveAacExportHistory?.close()
        liveAacExportHistory = null
        audioThread.quitSafely()
        exportThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = BackgroundRecorderBinder()

    override fun onUnbind(intent: Intent): Boolean = true

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
        var selectedRouteMode = getConfiguredInputRouteMode(this)
        var selectedCodec = getConfiguredOutputCodec(this)

        if (supportedSampleRates(this, selectedSourceMode, selectedRouteMode, selectedCodec).isEmpty()) {
            if (
                selectedCodec != ExportCodec.WAV &&
                supportedSampleRates(this, selectedSourceMode, selectedRouteMode, ExportCodec.WAV).isNotEmpty()
            ) {
                selectedCodec = ExportCodec.WAV
            } else {
                selectedRouteMode = InputRouteMode.AUTO
                selectedSourceMode = AudioSourceMode.MIC
                selectedCodec = ExportCodec.WAV
            }
        }

        val supportedRates = supportedSampleRates(this, selectedSourceMode, selectedRouteMode, selectedCodec)
        var preferredRate = getConfiguredSampleRate(this, selectedSourceMode, selectedRouteMode, selectedCodec)
        if (supportedRates.isNotEmpty() && preferredRate !in supportedRates) {
            preferredRate = supportedRates.first()
        }

        sampleRate = if (preferredRate > 0) preferredRate else 48_000
        fillRate = 2 * sampleRate
        sourceMode = selectedSourceMode
        audioSource = selectedSourceMode.sourceValue
        outputCodec = if (isCodecSupported(selectedCodec, sampleRate)) selectedCodec else ExportCodec.WAV
        inputRouteMode = selectedRouteMode
        if (this::audioHandler.isInitialized) {
            configureLiveExportHistory()
        }
    }

    private fun innerStartListening() {
        when (state) {
            STATE_LISTENING, STATE_RECORDING -> return
            STATE_READY, STATE_PAUSED -> Unit
            else -> return
        }

        state = STATE_LISTENING
        ContextCompat.startForegroundService(this, Intent(this, javaClass))

        val memorySize = getConfiguredMemorySizeBytes(this, sampleRate)
        audioHandler.post {
            releaseAudioRecord()
            audioMemory.allocate(memorySize)

            audioRecord = createAudioRecord()
            val record = audioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Audio input initialization failed")
                releaseAudioRecord()
                state = STATE_READY
                showToast(getString(R.string.audio_input_init_failed))
                return@post
            }

            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioRecord.startRecording failed", e)
                releaseAudioRecord()
                state = STATE_READY
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
        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopService(Intent(this, javaClass))

        audioHandler.post {
            audioHandler.removeCallbacks(audioReader)
            releaseAudioRecord()
            audioMemory.allocate(0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
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
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
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

            val outTarget = try {
                createOutputTarget(this@TimeTravelService, newFileName, startedAtMillis, effectiveOutputCodec)
            } catch (e: IOException) {
                Log.e(TAG, "Unable to prepare export file", e)
                val message = getString(R.string.cant_create_file_generic)
                showToast(message)
                notifyReceiverFailure(receiver, message)
                return@post
            }

            if (effectiveOutputCodec == ExportCodec.AAC) {
                val snapshot = liveAacExportHistory?.snapshotLastDuration((useBytes * 1_000_000L) / maxOf(fillRate, 1))
                if (snapshot != null && snapshot.frames.isNotEmpty()) {
                    exportHandler.post {
                        try {
                            AacSnapshotExporter.export(this@TimeTravelService, snapshot, outTarget)
                            notifyReceiver(
                                receiver,
                                buildRecordingEntity(
                                    this@TimeTravelService,
                                    outTarget,
                                    snapshot.durationUs / 1000L,
                                    buildCodecSummary(effectiveOutputCodec, sampleRate),
                                ),
                            )
                        } catch (e: IOException) {
                            Log.e(TAG, "Error while writing cached AAC export into ${outTarget.displayName}", e)
                            val message = getString(R.string.error_during_writing_history_into) + outTarget.displayName
                            showToast(message)
                            notifyReceiverFailure(receiver, message)
                            deleteIfEmpty(outTarget)
                        }
                    }
                    return@post
                }
            }

            exportHandler.post {
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
                            buildCodecSummary(effectiveOutputCodec, sampleRate),
                        ),
                    )
                } catch (e: IOException) {
                    Log.e(TAG, "Error while exporting history into ${outTarget.displayName}", e)
                    val message = getString(R.string.error_during_writing_history_into) + outTarget.displayName
                    showToast(message)
                    notifyReceiverFailure(receiver, message)
                    deleteIfEmpty(outTarget)
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
            } catch (e: IOException) {
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
                } catch (e: IOException) {
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
        val newRouteMode = getConfiguredInputRouteMode(this)
        val newCodec = getConfiguredOutputCodec(this)
        val newSampleRate = getConfiguredSampleRate(this, newSourceMode, newRouteMode, newCodec)
        val captureConfigChanged =
            newSampleRate != sampleRate || newSourceMode != sourceMode || newRouteMode != inputRouteMode

        outputCodec = if (isCodecSupported(newCodec, sampleRate)) newCodec else ExportCodec.WAV

        if (state != STATE_LISTENING || !isListeningEnabled()) {
            loadConfiguration()
            return ApplySettingsResult.APPLIED_NOW
        }

        audioHandler.post {
            audioMemory.allocate(getConfiguredMemorySizeBytes(this, sampleRate))
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
                    buildCodecSummary(effectiveOutputCodec, sampleRate),
                ),
            )
        }

        if (!isListeningEnabled()) {
            innerStopListening()
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
        get() = if (isCodecSupported(outputCodec, sampleRate)) outputCodec else ExportCodec.WAV

    @Throws(IOException::class)
    private fun createAudioFileWriter(target: RecordingOutputTarget): AudioFileWriter {
        return when (effectiveOutputCodec) {
            ExportCodec.AAC -> AacAudioFileWriter(this, target, sampleRate)
            ExportCodec.WAV -> WavAudioFileWriter(this, target, sampleRate)
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
            liveAacExportHistory?.appendPcm(array, offset, read)
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
        } catch (e: IOException) {
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
            routeMode = inputRouteMode,
        )
    }

    fun clearBuffer() {
        if (state == STATE_RECORDING) {
            return
        }
        audioHandler.post {
            audioMemory.clear()
            liveAacExportHistory?.clear()
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isListeningEnabled()) {
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

        (getSystemService(ALARM_SERVICE) as? AlarmManager)?.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            restartServicePendingIntent,
        )
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
        val routeMode: InputRouteMode,
    )

    enum class ApplySettingsResult {
        APPLIED_NOW,
        DEFERRED_UNTIL_RESTART,
        BLOCKED_RECORDING,
    }

    companion object {
        val TAG: String = TimeTravelService::class.java.simpleName
        const val NOTIFICATION_CHANNEL_ID = "TimeTravelRecorderChannel"
        const val FOREGROUND_NOTIFICATION_ID = 458
        const val MIN_AUDIO_RECORD_BUFFER_SIZE = 16 * 1024

        const val STATE_READY = 0
        const val STATE_LISTENING = 1
        const val STATE_RECORDING = 2
        const val STATE_PAUSED = 3
    }

    private fun configureLiveExportHistory() {
        val retentionDurationUs = getConfiguredMemorySizeBytes(this, sampleRate) * 1_000_000L / maxOf(fillRate, 1)
        audioHandler.post {
            val shouldUseAacHistory = effectiveOutputCodec == ExportCodec.AAC
            if (!shouldUseAacHistory) {
                liveAacExportHistory?.close()
                liveAacExportHistory = null
                return@post
            }

            val current = liveAacExportHistory
            if (current == null || current.sampleRate != sampleRate) {
                current?.close()
                liveAacExportHistory = LiveAacExportHistory(sampleRate).also {
                    it.setMaxDurationUs(retentionDurationUs)
                }
            } else {
                current.setMaxDurationUs(retentionDurationUs)
            }
        }
    }
}
