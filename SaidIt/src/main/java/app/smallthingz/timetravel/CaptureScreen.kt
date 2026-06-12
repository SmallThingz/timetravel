package app.smallthingz.timetravel

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class NotifyFileReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
) : TimeTravelService.AudioFileReceiver {
    private val appContext = context.applicationContext
    override fun fileReady(recording: RecordingEntity) {
        scope.launch(Dispatchers.IO) {
            val saved = RecordingRepository.register(appContext, recording)
            if (
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return@launch
            NotificationManagerCompat.from(context).notify(43, buildCaptureNotification(context, saved))
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(appContext, message.ifBlank { appContext.getString(R.string.save_failed) }, Toast.LENGTH_LONG).show()
        }
    }
}

fun buildCaptureNotification(context: Context, recording: RecordingEntity): Notification {
    val intent = buildOpenRecordingIntent(context, recording)
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(context, TimeTravelService.NOTIFICATION_CHANNEL_ID)
        .setContentTitle(context.getString(R.string.recording_saved))
        .setContentText(recording.displayName)
        .setSmallIcon(R.drawable.ic_notification_saved)
        .setTicker(recording.displayName)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()
}

private data class ExportRange(
    val startSeconds: Float,
    val endSeconds: Float,
    val warningDurationSeconds: Float?,
)

private data class ExportUiConfig(
    val format: ExportFormat,
    val codec: ExportCodec,
    val sampleFormat: PcmSampleFormat,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrateKbps: Int?,
)

@Composable
fun CaptureScreen() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var service by remember { mutableStateOf<TimeTravelService?>(null) }
    var isListening by remember { mutableStateOf(true) }
    var isRecording by remember { mutableStateOf(false) }
    var isSaving by rememberSaveable { mutableStateOf(false) }
    var memorizedSeconds by remember { mutableFloatStateOf(0f) }
    var totalMemorySeconds by remember { mutableFloatStateOf(0f) }
    var recordedSeconds by remember { mutableFloatStateOf(0f) }
    val reencodePendingState = remember { mutableStateOf(false) }
    var historyReencodePending by reencodePendingState
    val reencodingState = remember { mutableStateOf(false) }
    var historyReencoding by reencodingState
    val reencodeProcessedState = remember { mutableStateOf(0L) }
    var historyReencodeProcessedBytes by reencodeProcessedState
    val reencodeTotalState = remember { mutableStateOf(0L) }
    var historyReencodeTotalBytes by reencodeTotalState

    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var showExportRangeDialog by rememberSaveable { mutableStateOf(false) }
    var showExportClampDialog by rememberSaveable { mutableStateOf(false) }
    var clampWarningSeconds by rememberSaveable { mutableFloatStateOf(0f) }
    var pendingExportRange by remember { mutableStateOf<ExportRange?>(null) } // Not saveable — non-serializable
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val stateCallback = remember {
        object : TimeTravelService.StateCallback {
            override fun state(
                listeningEnabled: Boolean,
                recording: Boolean,
                memorized: Float,
                totalMemory: Float,
                recorded: Float,
                historyReencodePending: Boolean,
                historyReencoding: Boolean,
                historyReencodeProcessedBytes: Long,
                historyReencodeTotalBytes: Long,
            ) {
                isListening = listeningEnabled
                isRecording = recording
                memorizedSeconds = memorized
                totalMemorySeconds = totalMemory
                recordedSeconds = recorded
                reencodePendingState.value = historyReencodePending
                reencodingState.value = historyReencoding
                reencodeProcessedState.value = historyReencodeProcessedBytes
                reencodeTotalState.value = historyReencodeTotalBytes
            }
        }
    }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val typedBinder = binder as? TimeTravelService.BackgroundRecorderBinder
                    ?: run {
                        service = null
                        return
                    }
                service = typedBinder.service
                service?.getState(stateCallback)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    context.bindService(
                        Intent(context, TimeTravelService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }

                Lifecycle.Event.ON_STOP -> {
                    context.unbindService(connection)
                    service = null
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try {
                context.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Service was not bound
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val s = service
            if (s != null) {
                s.getState(stateCallback)
                s.consumePendingError()?.let { errorMessage = it }
            }
            delay(150)
        }
    }

    if (showClearDialog) {
        ClearBufferDialog(
            onConfirm = {
                showClearDialog = false
                service?.clearBuffer()
            },
            onDismiss = { showClearDialog = false },
        )
    }

    if (showExportClampDialog) {
        if (pendingExportRange == null) {
            showExportClampDialog = false
        } else {
            ExportClampDialog(
                clampedDurationSeconds = clampWarningSeconds,
                onProceed = {
                    showExportClampDialog = false
                    val range = pendingExportRange ?: return@ExportClampDialog
                    pendingExportRange = null
                    startExport(context, service, range, scope, snackbarHostState, setSaving = { isSaving = it }, onError = { errorMessage = it })
                },
                onDismiss = {
                    showExportClampDialog = false
                    pendingExportRange = null
                },
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isRecording) {
            RecordingOverlay(
                recordedSeconds = recordedSeconds,
                isSaving = isSaving,
                onStopRecording = {
                    val s = service ?: return@RecordingOverlay
                    if (isSaving) return@RecordingOverlay
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isSaving = true
                    s.stopRecording(SaveResultReceiver(context, scope, snackbarHostState, setSaving = { isSaving = it }, onError = { errorMessage = it }))
                },
            )
        } else {
            MainCaptureContent(
                memorizedSeconds = memorizedSeconds,
                totalMemorySeconds = totalMemorySeconds,
                isListening = isListening,
                isRecording = isRecording,
                isSaving = isSaving,
                isHistoryReencoding = historyReencoding,
                historyReencodePending = historyReencodePending,
                historyReencodeProcessedBytes = historyReencodeProcessedBytes,
                historyReencodeTotalBytes = historyReencodeTotalBytes,
                service = service,
                onListenToggle = {
                    val s = service ?: return@MainCaptureContent
                    if (isSaving) return@MainCaptureContent
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    isListening = !isListening
                    if (isListening) s.enableListening() else s.disableListening()
                },
                onClearBuffer = {
                    if (!isSaving && !isRecording) {
                        showClearDialog = true
                    }
                },
                onExportFull = {
                    if (isSaving) return@MainCaptureContent
                    val currentSeconds = memorizedSeconds.coerceAtLeast(0f)
                    if (currentSeconds <= 0f) {
                        Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                        return@MainCaptureContent
                    }
                    handleExport(context, service, currentSeconds, currentSeconds) { range ->
                        if (range.warningDurationSeconds != null) {
                            clampWarningSeconds = range.warningDurationSeconds
                            pendingExportRange = range
                            showExportClampDialog = true
                        } else {
                startExport(context, service, range, scope, snackbarHostState, setSaving = { isSaving = it }, onError = { errorMessage = it })
                        }
                    }
                },
                onExportCustom = {
                    if (isSaving) return@MainCaptureContent
                    val currentSeconds = memorizedSeconds.coerceAtLeast(0f)
                    if (currentSeconds <= 0f) {
                        Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                        return@MainCaptureContent
                    }
                    showExportRangeDialog = true
                },
                onReencode = {
                    if (!historyReencoding) {
                        service?.startHistoryReencode()
                    }
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
        ) { data ->
            Snackbar(snackbarData = data)
        }
    }

    if (showExportRangeDialog) {
        val currentSeconds = memorizedSeconds.coerceAtLeast(0f)
        ExportRangeDialog(
            currentBufferSeconds = currentSeconds,
            currentBufferBytes = currentBufferExportBytes(context, service, memorizedSeconds),
            onExport = { range ->
                showExportRangeDialog = false
                if (range.warningDurationSeconds != null) {
                    clampWarningSeconds = range.warningDurationSeconds
                    pendingExportRange = range
                    showExportClampDialog = true
                } else {
                    startExport(context, service, range, scope, snackbarHostState, setSaving = { isSaving = it }, onError = { errorMessage = it })
                }
            },
            onDismiss = { showExportRangeDialog = false },
        )
    }

    errorMessage?.let { msg ->
        ErrorDialog(
            message = msg,
            onDismiss = { errorMessage = null },
        )
    }
}

@Composable
private fun MainCaptureContent(
    memorizedSeconds: Float,
    totalMemorySeconds: Float,
    isListening: Boolean,
    isRecording: Boolean,
    isSaving: Boolean,
    isHistoryReencoding: Boolean,
    historyReencodePending: Boolean,
    historyReencodeProcessedBytes: Long,
    historyReencodeTotalBytes: Long,
    service: TimeTravelService?,
    onListenToggle: () -> Unit,
    onClearBuffer: () -> Unit,
    onExportFull: () -> Unit,
    onExportCustom: () -> Unit,
    onReencode: () -> Unit,
) {
    val context = LocalContext.current
    val retentionMode = remember(context) { mutableStateOf(getConfiguredRetentionMode(context)) }
    val retentionSeconds = remember(context) { mutableStateOf(getConfiguredRetentionSeconds(context)) }

    val displayedCurrentSeconds = memorizedSeconds.coerceAtLeast(0f).toInt()
    val displayedLimitSeconds =
        when (retentionMode.value) {
            RetentionMode.TIME -> retentionSeconds.value.coerceAtLeast(0L).toInt()
            RetentionMode.SIZE -> totalMemorySeconds.coerceAtLeast(0f).toInt()
        }

    val exportConfig = remember(context, service) { currentExportConfig(context, service) }
    val currentBytes = estimateExportSizeBytes(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, displayedCurrentSeconds.toLong(), exportConfig.bitrateKbps, exportConfig.sampleFormat,
    )
    val limitBytes = estimateExportSizeBytes(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, displayedLimitSeconds.toLong(), exportConfig.bitrateKbps, exportConfig.sampleFormat,
    )
    val configuredLimitBytes =
        when (retentionMode.value) {
            RetentionMode.TIME -> limitBytes
            RetentionMode.SIZE -> getConfiguredRetentionSizeBytes(context)
        }
    val exportLimitBytes = exportFileSizeLimitBytes(exportConfig.format)
    val overExportLimit = currentBytes > exportLimitBytes

    val timerText = when (retentionMode.value) {
        RetentionMode.TIME -> buildString {
            append(formatShortTimer(displayedCurrentSeconds.toFloat()))
            append(" / ")
            append(formatShortTimer(displayedLimitSeconds.toFloat()))
        }

        RetentionMode.SIZE -> buildString {
            append(formatShortFileSize(currentBytes))
            append(" / ")
            append(formatShortFileSize(configuredLimitBytes))
        }
    }
    val summaryText =
        when (retentionMode.value) {
            RetentionMode.TIME ->
                if (overExportLimit) {
                    stringResource(R.string.export_limit_summary, formatShortFileSize(exportLimitBytes))
                } else {
                    formatShortFileSize(currentBytes)
                }

            RetentionMode.SIZE ->
                if (overExportLimit) {
                    stringResource(R.string.export_limit_summary, formatShortFileSize(exportLimitBytes))
                } else {
                    formatShortTimer(displayedCurrentSeconds.toFloat())
                }
        }
    val summaryColor =
        if (overExportLimit) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant

    val exportBlocked = isSaving || isRecording || historyReencodePending || isHistoryReencoding
    val clearEnabled = !isSaving && !isRecording && !historyReencodePending && !isHistoryReencoding

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))

        Text(
            text = timerText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = summaryText,
            style = MaterialTheme.typography.titleMedium,
            color = summaryColor,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        ListenCircle(
            isListening = isListening,
            isRecording = false,
            isSaving = isSaving,
            isHistoryReencoding = isHistoryReencoding,
            onClick = onListenToggle,
        )

        Spacer(Modifier.weight(1f))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (historyReencodePending || isHistoryReencoding) {
                val reencodeText =
                    if (isHistoryReencoding) {
                        val pct = if (historyReencodeTotalBytes <= 0L) 0
                        else ((historyReencodeProcessedBytes * 100L) / historyReencodeTotalBytes).toInt().coerceIn(0, 100)
                        stringResource(R.string.reencode_history_progress, pct)
                    } else {
                        stringResource(R.string.reencode_history)
                    }
                FilledTonalButton(
                    onClick = onReencode,
                    enabled = !isHistoryReencoding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(reencodeText)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    IconButton(
                        onClick = onClearBuffer,
                        enabled = clearEnabled,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.clear_buffer),
                            tint = if (clearEnabled) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onExportFull,
                    enabled = !exportBlocked,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.record_all_memory))
                }

                Spacer(Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = onExportCustom,
                    enabled = !exportBlocked,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(stringResource(R.string.custom_time))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RecordingOverlay(
    recordedSeconds: Float,
    isSaving: Boolean,
    onStopRecording: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatShortTimer(recordedSeconds),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            ListenCircle(
                isListening = true,
                isRecording = true,
                isSaving = isSaving,
                isHistoryReencoding = false,
                showRecordingIcon = true,
                onClick = onStopRecording,
            )
        }
    }
}

@Composable
private fun ListenCircle(
    isListening: Boolean,
    isRecording: Boolean,
    isSaving: Boolean,
    isHistoryReencoding: Boolean,
    showRecordingIcon: Boolean = false,
    onClick: () -> Unit,
) {
    val active = isListening || isRecording
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.965f else 1f,
        animationSpec = tween(110),
        label = "pressScale",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when {
            !active -> 1f
            isRecording -> 1.09f
            else -> 1.065f
        },
        animationSpec = infiniteRepeatable<Float>(
            animation = tween<Float>(
                durationMillis = when {
                    isRecording -> 2200
                    active -> 3400
                    else -> 1
                },
                easing = EaseInOutCubic,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val ringColor = when {
        isSaving || isHistoryReencoding -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        isRecording -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else -> Color.Transparent
    }

    val fillColor = when {
        isSaving || isHistoryReencoding -> MaterialTheme.colorScheme.surfaceContainerHigh
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val contentColor = when {
        active -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val strokeColor = when {
        active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.43f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
    }

    val ringStrokeColor = when {
        active -> MaterialTheme.colorScheme.primary.copy(alpha = if (isRecording) 0.63f else 0.17f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.17f)
    }

    val enabled = !isSaving && !isHistoryReencoding

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(282.dp)
            .graphicsLayer(alpha = if (enabled) 1f else 0.72f),
    ) {
        Canvas(
            modifier = Modifier
                .size(282.dp)
                .graphicsLayer(
                    scaleX = pulseScale,
                    scaleY = pulseScale,
                ),
        ) {
            val canvasSize = size.minDimension
            val ringWidth = canvasSize * 0.002f
            drawCircle(
                color = ringColor,
                radius = canvasSize / 2f - ringWidth,
                center = Offset(canvasSize / 2f, canvasSize / 2f),
                style = Stroke(width = ringWidth * 0.85f),
            )
            if (ringColor != Color.Transparent) {
                drawCircle(
                    color = ringColor,
                    radius = canvasSize / 2f,
                    center = Offset(canvasSize / 2f, canvasSize / 2f),
                    style = Stroke(width = ringWidth),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(232.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(fillColor)
                    drawCircle(strokeColor, style = Stroke(2.dp.toPx()))
                }
                .graphicsLayer(
                    scaleX = pressScale,
                    scaleY = pressScale,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(
                        if (showRecordingIcon) R.drawable.ic_recording else R.drawable.ic_capture_wave,
                    ),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(
                        if (active) R.string.buffer_active_summary else R.string.buffer_inactive_summary,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.error)) },
        text = {
            SelectionContainer {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@TextButton
                clipboard.setPrimaryClip(ClipData.newPlainText("error", message))
            }) {
                Text(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.share)))
            }) {
                Text(stringResource(R.string.share))
            }
        },
    )
}

@Composable
private fun ClearBufferDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.clear_buffer),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.clear_buffer),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clear_buffer))
            }
        },
    )
}

@Composable
private fun ExportClampDialog(
    clampedDurationSeconds: Float,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.export_limit_dialog_title), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Text(
                text = stringResource(R.string.export_limit_dialog_message, formatShortTimer(clampedDurationSeconds)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onProceed) {
                Text(stringResource(R.string.export))
            }
        },
    )
}

@Composable
private fun ExportRangeDialog(
    currentBufferSeconds: Float,
    currentBufferBytes: Long,
    onExport: (ExportRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { getRecorderPreferences(context) }

    var scopeMode by remember { mutableStateOf(getConfiguredCustomExportMode(context)) }
    var unitMode by remember { mutableStateOf(getConfiguredCustomExportUnit(context)) }

    var startTimeText by remember { mutableStateOf("0:00") }
    var endTimeText by remember(currentBufferSeconds) { mutableStateOf(formatDurationInput(currentBufferSeconds.toInt())) }
    var startSizeText by remember { mutableStateOf(formatSizeInputMib(0L)) }
    var endSizeText by remember(currentBufferBytes) { mutableStateOf(formatSizeInputMib(currentBufferBytes)) }
    var pastTimeText by remember(currentBufferSeconds) {
        mutableStateOf(formatDurationInput(currentBufferSeconds.toInt().coerceAtLeast(1)))
    }
    var pastSizeText by remember(currentBufferBytes) {
        mutableStateOf(prefs.getString(PrefKey.CUSTOM_EXPORT_PAST_SIZE_MIB, formatSizeInputMib(currentBufferBytes)) ?: formatSizeInputMib(currentBufferBytes))
    }

    var startTimeError by remember { mutableStateOf<String?>(null) }
    var endTimeError by remember { mutableStateOf<String?>(null) }
    var startSizeError by remember { mutableStateOf<String?>(null) }
    var endSizeError by remember { mutableStateOf<String?>(null) }
    var pastTimeError by remember { mutableStateOf<String?>(null) }
    var pastSizeError by remember { mutableStateOf<String?>(null) }

    fun clampExportRange(startSeconds: Float, endSeconds: Float): ExportRange {
        val exportConfig = currentExportConfig(context, null)
        val maxDurationSeconds = exportDurationLimitSeconds(
            exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
            exportConfig.channelCount, exportConfig.bitrateKbps,
        ).toFloat().coerceAtLeast(1f)
        val boundedEnd = endSeconds.coerceAtLeast(startSeconds)
        val requestedDuration = boundedEnd - startSeconds
        if (requestedDuration <= maxDurationSeconds) {
            return ExportRange(startSeconds, boundedEnd, null)
        }
        return ExportRange(
            startSeconds = (boundedEnd - maxDurationSeconds).coerceAtLeast(0f),
            endSeconds = boundedEnd,
            warningDurationSeconds = maxDurationSeconds,
        )
    }

    val firstFieldFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { firstFieldFocusRequester.requestFocus() }

    fun submit() {
        val currentSeconds = currentBufferSeconds
        if (currentSeconds <= 0f) {
            Toast.makeText(context, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val rangeMode = scopeMode == CustomExportMode.RANGE
        val timeUnit = unitMode == CustomExportUnit.TIME

        if (rangeMode && timeUnit) {
            val startSec = parseDurationInput(startTimeText)?.toFloat()
            val endSec = parseDurationInput(endTimeText)?.toFloat()
            if (startSec == null || startSec < 0f) { startTimeError = context.getString(R.string.retention_time_invalid); return } else startTimeError = null
            if (endSec == null || endSec <= 0f) { endTimeError = context.getString(R.string.retention_time_invalid); return } else endTimeError = null
            if (startSec > currentSeconds) { startTimeError = context.getString(R.string.custom_export_range_invalid); return } else startTimeError = null
            if (endSec <= startSec || endSec > currentSeconds) { endTimeError = context.getString(R.string.custom_export_range_invalid); return } else endTimeError = null
            onExport(clampExportRange(startSec, endSec))
            return
        }

        if (rangeMode && !timeUnit) {
            val startBytes = parseSizeInputMib(startSizeText)
            val endBytes = parseSizeInputMib(endSizeText)
            if (startBytes == null || startBytes < 0L) { startSizeError = context.getString(R.string.custom_export_size_invalid); return } else startSizeError = null
            if (endBytes == null || endBytes <= 0L) { endSizeError = context.getString(R.string.custom_export_size_invalid); return } else endSizeError = null
            if (startBytes > currentBufferBytes) { startSizeError = context.getString(R.string.custom_export_range_invalid); return } else startSizeError = null
            if (endBytes <= startBytes || endBytes > currentBufferBytes) { endSizeError = context.getString(R.string.custom_export_range_invalid); return } else endSizeError = null
            onExport(clampExportRange(sizeBytesToExportSeconds(startBytes, context), sizeBytesToExportSeconds(endBytes, context)))
            return
        }

        if (!rangeMode && timeUnit) {
            val pastSec = parseDurationInput(pastTimeText)?.toFloat()
            if (pastSec == null || pastSec <= 0f) { pastTimeError = context.getString(R.string.retention_time_invalid); return } else pastTimeError = null
            prefs.edit().putInt(PrefKey.CUSTOM_EXPORT_PAST_SECONDS, pastSec.roundToInt()).apply()
            onExport(clampExportRange((currentSeconds - pastSec).coerceAtLeast(0f), currentSeconds))
            return
        }

        val pastBytes = parseSizeInputMib(pastSizeText)
        if (pastBytes == null || pastBytes <= 0L) { pastSizeError = context.getString(R.string.custom_export_size_invalid); return } else pastSizeError = null
        if (pastBytes > currentBufferBytes) { pastSizeError = context.getString(R.string.custom_export_range_invalid); return } else pastSizeError = null
        prefs.edit().putString(PrefKey.CUSTOM_EXPORT_PAST_SIZE_MIB, pastSizeText).apply()
        onExport(clampExportRange((currentSeconds - sizeBytesToExportSeconds(pastBytes, context)).coerceAtLeast(0f), currentSeconds))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.export), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            BoxWithConstraints {
                val pad = if (maxWidth > 360.dp) 16.dp else 0.dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = pad)
                ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = scopeMode == CustomExportMode.PAST,
                        onClick = {
                            scopeMode = CustomExportMode.PAST
                            setConfiguredCustomExportMode(context, CustomExportMode.PAST)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.custom_export_mode_past)) }
                    SegmentedButton(
                        selected = scopeMode == CustomExportMode.RANGE,
                        onClick = {
                            scopeMode = CustomExportMode.RANGE
                            setConfiguredCustomExportMode(context, CustomExportMode.RANGE)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.custom_export_mode_range)) }
                }

                Spacer(Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SegmentedButton(
                        selected = unitMode == CustomExportUnit.TIME,
                        onClick = {
                            unitMode = CustomExportUnit.TIME
                            setConfiguredCustomExportUnit(context, CustomExportUnit.TIME)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.retention_time_label)) }
                    SegmentedButton(
                        selected = unitMode == CustomExportUnit.SIZE,
                        onClick = {
                            unitMode = CustomExportUnit.SIZE
                            setConfiguredCustomExportUnit(context, CustomExportUnit.SIZE)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.custom_export_unit_size)) }
                }

                Spacer(Modifier.height(16.dp))

                when {
                    scopeMode == CustomExportMode.PAST && unitMode == CustomExportUnit.TIME -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = pastTimeText,
                                onValueChange = { pastTimeText = it; pastTimeError = null },
                                label = { Text(stringResource(R.string.custom_export_past_label)) },
                                isError = pastTimeError != null,
                                supportingText = pastTimeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    scopeMode == CustomExportMode.PAST && unitMode == CustomExportUnit.SIZE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = pastSizeText,
                                onValueChange = { pastSizeText = it; pastSizeError = null },
                                label = { Text(stringResource(R.string.custom_export_past_label)) },
                                isError = pastSizeError != null,
                                supportingText = pastSizeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    scopeMode == CustomExportMode.RANGE && unitMode == CustomExportUnit.TIME -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = startTimeText,
                                onValueChange = { startTimeText = it; startTimeError = null },
                                label = { Text(stringResource(R.string.custom_export_start_label)) },
                                isError = startTimeError != null,
                                supportingText = startTimeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            OutlinedTextField(
                                value = endTimeText,
                                onValueChange = { endTimeText = it; endTimeError = null },
                                label = { Text(stringResource(R.string.custom_export_end_label)) },
                                isError = endTimeError != null,
                                supportingText = endTimeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    scopeMode == CustomExportMode.RANGE && unitMode == CustomExportUnit.SIZE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = startSizeText,
                                onValueChange = { startSizeText = it; startSizeError = null },
                                label = { Text(stringResource(R.string.custom_export_start_label)) },
                                isError = startSizeError != null,
                                supportingText = startSizeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f).focusRequester(firstFieldFocusRequester),
                            )
                            OutlinedTextField(
                                value = endSizeText,
                                onValueChange = { endSizeText = it; endSizeError = null },
                                label = { Text(stringResource(R.string.custom_export_end_label)) },
                                isError = endSizeError != null,
                                supportingText = endSizeError?.let { { Text(it) } },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { submit() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.export),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {},
    )
}

private fun startExport(
    context: Context,
    service: TimeTravelService?,
    range: ExportRange,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    setSaving: (Boolean) -> Unit,
    onError: (String) -> Unit = {},
) {
    val s = service ?: return
    setSaving(true)
    scope.launch {
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.saving),
            actionLabel = context.getString(R.string.cancel),
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) {
            setSaving(false)
            s.cancelCurrentExport()
        }
    }
    s.dumpRecordingRange(
        range.startSeconds,
        range.endSeconds,
        SaveResultReceiver(context, scope, snackbarHostState, setSaving, onError),
        "",
    )
}

private fun handleExport(
    context: Context,
    service: TimeTravelService?,
    currentSeconds: Float,
    endSeconds: Float,
    onRange: (ExportRange) -> Unit,
) {
    if (currentSeconds <= 0f) return
    val exportConfig = currentExportConfig(context, service)
    val maxDurationSeconds = exportDurationLimitSeconds(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, exportConfig.bitrateKbps,
    ).toFloat().coerceAtLeast(1f)
    val requestedDuration = endSeconds
    val boundedEnd = endSeconds.coerceAtLeast(0f)
    if (requestedDuration <= maxDurationSeconds) {
        onRange(ExportRange(0f, boundedEnd, null))
    } else {
        onRange(
            ExportRange(
                startSeconds = (boundedEnd - maxDurationSeconds).coerceAtLeast(0f),
                endSeconds = boundedEnd,
                warningDurationSeconds = maxDurationSeconds,
            ),
        )
    }
}

private class SaveResultReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState,
    private val setSaving: (Boolean) -> Unit,
    private val onError: (String) -> Unit = {},
) : TimeTravelService.AudioFileReceiver {
    private val appContext = context.applicationContext
    override fun fileReady(recording: RecordingEntity) {
        setSaving(false)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            runCatching { RecordingRepository.register(appContext, recording) }
            snackbarHostState.showSnackbar(
                message = appContext.getString(R.string.saved_snackbar),
                duration = SnackbarDuration.Short,
            )
        }
    }

    override fun fileFailed(message: String, error: Throwable?) {
        setSaving(false)
        val text = if (message.isBlank()) appContext.getString(R.string.save_failed) else message
        onError(text)
    }

    override fun fileCancelled() {
        setSaving(false)
    }
}

private fun currentExportConfig(context: Context, recorder: TimeTravelService?): ExportUiConfig {
    val activeConfig = recorder?.getConfigurationSnapshot()
    val format = activeConfig?.format ?: getConfiguredOutputFormat(context)
    val codec = activeConfig?.codec ?: getConfiguredOutputCodec(context)
    val sampleFormat = activeConfig?.sampleFormat ?: getConfiguredPcmSampleFormat(context)
    val sourceMode = activeConfig?.sourceMode ?: getConfiguredAudioSourceMode(context)
    val channelMode = activeConfig?.channelMode ?: getConfiguredChannelMode(context)
    val routeMode = activeConfig?.routeMode ?: getConfiguredInputRouteMode(context)
    val sampleRate = activeConfig?.sampleRate
        ?: getConfiguredSampleRate(context, sourceMode, routeMode, format, codec, channelMode)
    return ExportUiConfig(
        format = format,
        codec = codec,
        sampleFormat = sampleFormat,
        sampleRate = sampleRate,
        channelCount = channelMode.channelCount,
        bitrateKbps = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
    )
}

private fun currentBufferExportBytes(
    context: Context,
    recorder: TimeTravelService?,
    memorizedSeconds: Float,
): Long {
    val exportConfig = currentExportConfig(context, recorder)
    return estimateExportSizeBytes(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, memorizedSeconds.coerceAtLeast(0f).toLong(), exportConfig.bitrateKbps, exportConfig.sampleFormat,
    )
}

private fun sizeBytesToExportSeconds(sizeBytes: Long, context: Context): Float {
    val exportConfig = currentExportConfig(context, null)
    return estimateExportDurationSeconds(
        exportConfig.format, exportConfig.codec, exportConfig.sampleRate,
        exportConfig.channelCount, sizeBytes, exportConfig.bitrateKbps, exportConfig.sampleFormat,
    ).toFloat()
}

private fun parseSizeInputMib(value: String): Long? {
    val mib = value.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (mib < 0.0) return null
    return (mib * 1024.0 * 1024.0).roundToLong()
}

private fun formatSizeInputMib(sizeBytes: Long): String {
    val mebibytes = sizeBytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return "%.1f".format(mebibytes)
}
