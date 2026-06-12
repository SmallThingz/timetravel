package app.smallthingz.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val TAG = "SettingsScreen"
private val BYTES_IN_MEGABYTE = 1024L * 1024L
private val retentionSizeFormatter = DecimalFormat(TimeTravelConfig.FORMAT_RETENTION_SIZE_MIB, DecimalFormatSymbols(Locale.US))

data class SettingsSnapshot(
    var themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    var retentionMode: RetentionMode = RetentionMode.TIME,
    var retentionTime: Int = 0,
    var retentionSizeMb: Double = 0.0,
    var format: ExportFormat? = null,
    var codec: ExportCodec? = null,
    var sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
    var bitrateKbps: Int = 0,
    var source: AudioSourceMode? = null,
    var channelMode: ChannelMode? = null,
    var route: InputRouteMode? = null,
    var sampleRate: Int = 0,
    var historyChunkSeconds: Int = 10,
    var autoMergeMode: AutoMergeMode = AutoMergeMode.RATIO,
    var autoMergeDivisor: Int = defaultAutoMergeDivisor(),
    var autoMergeCustomSeconds: Int = defaultAutoMergeCustomSeconds(),
    var autoMergeCustomSizeMib: Double = defaultAutoMergeCustomSizeMib(),
    var autoMergeEagerEnabled: Boolean = true,
    var exportDirectoryUri: String? = null,
    var persistentBufferEnabled: Boolean = true,
    var aggressiveRestartEnabled: Boolean = true,
    var wakeLockEnabled: Boolean = false,
) {
    fun copyFrom(other: SettingsSnapshot) {
        themeMode = other.themeMode
        retentionMode = other.retentionMode
        retentionTime = other.retentionTime
        retentionSizeMb = other.retentionSizeMb
        format = other.format
        codec = other.codec
        sampleFormat = other.sampleFormat
        bitrateKbps = other.bitrateKbps
        source = other.source
        channelMode = other.channelMode
        route = other.route
        sampleRate = other.sampleRate
        historyChunkSeconds = other.historyChunkSeconds
        autoMergeMode = other.autoMergeMode
        autoMergeDivisor = other.autoMergeDivisor
        autoMergeCustomSeconds = other.autoMergeCustomSeconds
        autoMergeCustomSizeMib = other.autoMergeCustomSizeMib
        autoMergeEagerEnabled = other.autoMergeEagerEnabled
        exportDirectoryUri = other.exportDirectoryUri
        persistentBufferEnabled = other.persistentBufferEnabled
        aggressiveRestartEnabled = other.aggressiveRestartEnabled
        wakeLockEnabled = other.wakeLockEnabled
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onThemeChanged: (AppThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var originalSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var currentSnapshot by remember { mutableStateOf(SettingsSnapshot()) }
    var hasUnsavedChanges by remember { mutableStateOf(false) }

    var service by remember { mutableStateOf<TimeTravelService?>(null) }
    var serviceBound by remember { mutableStateOf(false) }

    var capabilityUiReady by remember { mutableStateOf(false) }
    var capabilityRefreshGeneration by remember { mutableIntStateOf(0) }
    var moveAvailabilityGeneration by remember { mutableIntStateOf(0) }

    // Selected values
    var selectedTheme by remember { mutableStateOf(AppThemeMode.SYSTEM) }
    var selectedFormat by remember { mutableStateOf(supportedFormats().first()) }
    var selectedCodec by remember { mutableStateOf(supportedCodecs(supportedFormats().first()).first()) }
    var selectedSampleFormat by remember { mutableStateOf(PcmSampleFormat.PCM_16) }
    var selectedSource by remember { mutableStateOf(AudioSourceMode.availableModes().first()) }
    var selectedChannelMode by remember { mutableStateOf(ChannelMode.MONO) }
    var selectedRoute by remember { mutableStateOf(InputRouteMode.AUTO) }
    var selectedSampleRate by remember { mutableIntStateOf(48_000) }
    var selectedAutoMergeMode by remember { mutableStateOf(AutoMergeMode.RATIO) }

    var activeRetentionMode by remember { mutableStateOf(RetentionMode.TIME) }
    var retentionTimeSecondsValue by remember { mutableIntStateOf(0) }
    var retentionSizeMbValue by remember { mutableStateOf(0.0) }
    var historyChunkSecondsValue by remember { mutableIntStateOf(10) }
    var autoMergeDivisorValue by remember { mutableIntStateOf(defaultAutoMergeDivisor()) }
    var autoMergeCustomSecondsValue by remember { mutableIntStateOf(defaultAutoMergeCustomSeconds()) }
    var autoMergeCustomSizeMibValue by remember { mutableStateOf(defaultAutoMergeCustomSizeMib()) }
    var autoMergeEagerEnabled by remember { mutableStateOf(true) }
    var selectedExportTreeUri by remember { mutableStateOf<Uri?>(null) }

    // Available options lists (recomputed on changes)
    var availableFormats by remember { mutableStateOf(supportedFormats()) }
    var availableCodecs by remember { mutableStateOf(supportedCodecs(supportedFormats().first())) }
    var availableSourceModes by remember { mutableStateOf(AudioSourceMode.availableModes()) }
    var availableChannelModes by remember { mutableStateOf<List<ChannelMode>>(ChannelMode.entries.toList()) }
    var availableRouteModes by remember { mutableStateOf<List<InputRouteMode>>(InputRouteMode.entries.toList()) }
    var availableSampleRates by remember { mutableStateOf(standardSampleRates()) }

    // Text inputs
    var retentionTimeText by remember { mutableStateOf("") }
    var retentionSizeText by remember { mutableStateOf("") }
    var historyChunkText by remember { mutableStateOf("") }
    var autoMergeValueText by remember { mutableStateOf("") }

    // Errors
    var retentionTimeError by remember { mutableStateOf<String?>(null) }
    var retentionSizeError by remember { mutableStateOf<String?>(null) }
    var historyChunkError by remember { mutableStateOf<String?>(null) }
    var autoMergeValueError by remember { mutableStateOf<String?>(null) }
    var sampleRateUnsupported by remember { mutableStateOf(false) }

    var bitrateKbps by remember { mutableIntStateOf(128) }
    var bitrateRange by remember { mutableStateOf(Int.MIN_VALUE..Int.MIN_VALUE) }

    var exportPathText by remember { mutableStateOf("") }
    var canMove by remember { mutableStateOf(false) }
    var batteryOptimizationRestricted by remember { mutableStateOf(true) }

    // Pre-computed label lists
    val themeLabels = remember { AppThemeMode.entries.map { context.getString(it.labelRes) } }
    var formatLabels by remember { mutableStateOf(availableFormats.map { context.getString(it.labelRes) }) }
    var codecLabels by remember { mutableStateOf(availableCodecs.map { context.getString(it.labelRes) }) }
    var sampleFormatLabels by remember { mutableStateOf(PcmSampleFormat.entries.map { context.getString(it.labelRes) }) }
    var sourceLabels by remember { mutableStateOf(availableSourceModes.map { context.getString(it.labelRes) }) }
    var channelModeLabels by remember { mutableStateOf(ChannelMode.entries.map { context.getString(it.labelRes) }) }
    var routeLabels by remember { mutableStateOf(InputRouteMode.entries.map { context.getString(it.labelRes) }) }
    var autoMergeLabels by remember { mutableStateOf(AutoMergeMode.entries.map { context.getString(it.labelRes) }) }
    var sampleRateLabels by remember { mutableStateOf(emptyList<String>()) }

    // Selection labels
    var selectedThemeLabel by remember { mutableStateOf(context.getString(AppThemeMode.SYSTEM.labelRes)) }
    var selectedFormatLabel by remember { mutableStateOf("") }
    var selectedCodecLabel by remember { mutableStateOf("") }
    var selectedSampleFormatLabel by remember { mutableStateOf(context.getString(PcmSampleFormat.PCM_16.labelRes)) }
    var selectedSourceLabel by remember { mutableStateOf("") }
    var selectedChannelModeLabel by remember { mutableStateOf("") }
    var selectedRouteLabel by remember { mutableStateOf("") }
    var selectedSampleRateLabel by remember { mutableStateOf("") }
    var selectedAutoMergeLabel by remember { mutableStateOf(context.getString(AutoMergeMode.RATIO.labelRes)) }

    fun clearErrors() {
        retentionTimeError = null
        retentionSizeError = null
        historyChunkError = null
        autoMergeValueError = null
        sampleRateUnsupported = false
    }

    fun currentBitrateKbpsOrNull(): Int? {
        val range = codecBitrateRangeKbps(selectedCodec) ?: return null
        return bitrateKbps.coerceIn(range)
    }

    fun effectiveCodecBitrateKbps(): Int? {
        val codec = selectedCodec
        val range = codecBitrateRangeKbps(codec)
            ?: return defaultCodecBitrateKbps(codec, selectedSampleRate, selectedChannelMode.channelCount)
        return currentBitrateKbpsOrNull()
            ?.coerceIn(range)
            ?: getConfiguredCodecBitrateKbps(context, codec, selectedSampleRate, selectedChannelMode.channelCount)
    }

    fun refreshBitrateField(preferredKbps: Int? = null) {
        val codec = selectedCodec
        val range = codecBitrateRangeKbps(codec)
        if (range == null) {
            bitrateRange = Int.MIN_VALUE..Int.MIN_VALUE
            return
        }
        val sampleRate = selectedSampleRate
        val defaultBitrateKbpsVal = defaultCodecBitrateKbps(codec, sampleRate, selectedChannelMode.channelCount) ?: range.first
        val currentBitrate = currentBitrateKbpsOrNull()
        val resolved = (preferredKbps ?: currentBitrate ?: defaultBitrateKbpsVal).coerceIn(range)
        bitrateRange = range
        bitrateKbps = resolved
    }

    fun refreshAutoMergeValueUi() {
        autoMergeValueError = null
        when (selectedAutoMergeMode) {
            AutoMergeMode.RATIO -> autoMergeValueText = autoMergeDivisorValue.toString()
            AutoMergeMode.CUSTOM_TIME -> autoMergeValueText = formatDurationInput(autoMergeCustomSecondsValue)
            AutoMergeMode.CUSTOM_SIZE -> autoMergeValueText = formatRetentionSizeMib(autoMergeCustomSizeMibValue)
            AutoMergeMode.OFF -> {}
        }
    }

    fun refreshExportDirectoryUi() {
        exportPathText = describeOutputDirectory(context, selectedExportTreeUri)
    }

    fun refreshBatteryOptimizationUi() {
        batteryOptimizationRestricted = !isIgnoringBatteryOptimizations(context)
    }

    fun refreshMoveRecordingsAvailability() {
        val gen = ++moveAvailabilityGeneration
        canMove = false
        scope.launch {
            val result = RecordingRepository.hasMovableRecordings(
                context,
                getOutputDirectoryId(context, selectedExportTreeUri),
            )
            if (gen == moveAvailabilityGeneration) canMove = result
        }
    }

    fun refreshSampleRates(preferredRate: Int? = null) {
        availableSampleRates = supportedSampleRates(context, selectedSource, selectedRoute, selectedFormat, selectedCodec, selectedChannelMode)
        sampleRateUnsupported = availableSampleRates.isEmpty()
        if (availableSampleRates.isNotEmpty()) {
            val rate = orderSampleRatesByPreference(availableSampleRates, preferredRate ?: selectedSampleRate).first()
            selectedSampleRate = rate
            sampleRateLabels = availableSampleRates.map { sampleRateLabel(it) }
            selectedSampleRateLabel = sampleRateLabel(rate)
        } else {
            sampleRateLabels = emptyList()
            selectedSampleRateLabel = ""
        }
    }

    fun refreshChannelModes(
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableChannelModes = supportedChannelModes(context, selectedSource, selectedRoute, selectedFormat, selectedCodec)
        val cm = preferredChannelMode?.takeIf { it in availableChannelModes } ?: availableChannelModes.first()
        selectedChannelMode = cm
        channelModeLabels = availableChannelModes.map { context.getString(it.labelRes) }
        selectedChannelModeLabel = context.getString(cm.labelRes)
        refreshSampleRates(preferredRate)
    }

    fun refreshSourceModes(
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        availableSourceModes = supportedAudioSourceModes(context, selectedRoute, selectedFormat, selectedCodec)
        val s = preferredSource?.takeIf { it in availableSourceModes } ?: availableSourceModes.first()
        selectedSource = s
        sourceLabels = availableSourceModes.map { context.getString(it.labelRes) }
        selectedSourceLabel = context.getString(s.labelRes)
        refreshChannelModes(preferredChannelMode, preferredRate)
    }

    fun refreshCodecOptions(
        preferredCodec: ExportCodec? = null,
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
        preferredBitrateKbps: Int? = null,
    ) {
        availableCodecs = supportedCodecs(selectedFormat)
        val codec = preferredCodec?.takeIf { it in availableCodecs } ?: availableCodecs.first()
        selectedCodec = codec
        codecLabels = availableCodecs.map { context.getString(it.labelRes) }
        selectedCodecLabel = context.getString(codec.labelRes)
        refreshSourceModes(preferredSource, preferredChannelMode, preferredRate)
        refreshBitrateField(preferredKbps = preferredBitrateKbps)
    }

    fun saveCurrentToSnapshot(snapshot: SettingsSnapshot) {
        snapshot.themeMode = selectedTheme
        snapshot.retentionMode = activeRetentionMode
        snapshot.retentionTime = retentionTimeSecondsValue
        snapshot.retentionSizeMb = retentionSizeMbValue
        snapshot.format = selectedFormat
        snapshot.codec = selectedCodec
        snapshot.sampleFormat = selectedSampleFormat
        snapshot.bitrateKbps = effectiveCodecBitrateKbps() ?: 0
        snapshot.source = selectedSource
        snapshot.channelMode = selectedChannelMode
        snapshot.route = selectedRoute
        snapshot.sampleRate = selectedSampleRate
        snapshot.historyChunkSeconds = historyChunkSecondsValue
        snapshot.autoMergeMode = selectedAutoMergeMode
        snapshot.autoMergeDivisor = autoMergeDivisorValue
        snapshot.autoMergeCustomSeconds = autoMergeCustomSecondsValue
        snapshot.autoMergeCustomSizeMib = autoMergeCustomSizeMibValue
        snapshot.autoMergeEagerEnabled = autoMergeEagerEnabled
        snapshot.exportDirectoryUri = selectedExportTreeUri?.toString()
    }

    fun pushUndoState() {
        hasUnsavedChanges = originalSnapshot != currentSnapshot
    }

    fun updateRetentionValuesFromActiveInput() {
        when (activeRetentionMode) {
            RetentionMode.TIME -> parseDurationInput(retentionTimeText.trim())?.let { retentionTimeSecondsValue = it }
            RetentionMode.SIZE -> parseRetentionSizeMib(retentionSizeText.trim())?.takeIf { it > 0.0 }?.let { retentionSizeMbValue = it }
        }
    }

    fun refreshRetentionFields(preserveActiveInputs: Boolean = false) {
        val sr = selectedSampleRate
        if (sr <= 0) {
            if (!preserveActiveInputs) { retentionTimeText = ""; retentionSizeText = "" }
            return
        }
        val bitrate = effectiveCodecBitrateKbps()
        val chCount = selectedChannelMode.channelCount
        val exportLimitBytes = exportFileSizeLimitBytes(selectedFormat)
        val exportLimitDurationSeconds = estimateExportDurationSeconds(
            selectedFormat, selectedCodec, sr, chCount, exportLimitBytes, bitrate, selectedSampleFormat,
        )
        val estimatedSizeMb = bytesToMegabytes(
            estimateExportSizeBytes(selectedFormat, selectedCodec, sr, chCount, retentionTimeSecondsValue.toLong(), bitrate, selectedSampleFormat),
        )
        val estimatedDuration = formatDurationInput(
            estimateExportDurationSeconds(
                selectedFormat, selectedCodec, sr, chCount,
                rawMegabytesToBytes(retentionSizeMbValue), bitrate, selectedSampleFormat,
            ),
        )

        if (activeRetentionMode == RetentionMode.TIME) {
            if (!preserveActiveInputs) retentionTimeText = formatDurationInput(retentionTimeSecondsValue)
            retentionSizeText = formatRetentionSizeMib(estimatedSizeMb)
        } else {
            retentionTimeText = estimatedDuration
            if (!preserveActiveInputs) retentionSizeText = formatRetentionSizeMib(retentionSizeMbValue)
        }
    }

    fun refreshHistorySettingsUi() {
        historyChunkText = formatDurationInput(historyChunkSecondsValue)
        refreshAutoMergeValueUi()
    }

    fun activateRetentionMode(mode: RetentionMode) {
        if (activeRetentionMode == mode) return
        activeRetentionMode = mode
        refreshRetentionFields()
        saveCurrentToSnapshot(currentSnapshot)
        pushUndoState()
    }

    fun applyResolvedCapabilityUi(preferred: SettingsSnapshot, resetOriginalSnapshot: Boolean) {
        val shouldResetOriginalSnapshot = resetOriginalSnapshot && !hasUnsavedChanges

        availableRouteModes = supportedInputRouteModes(context)
        val selRoute = preferred.route?.takeIf { it in availableRouteModes } ?: availableRouteModes.first()
        selectedRoute = selRoute
        selectedRouteLabel = context.getString(selRoute.labelRes)
        routeLabels = availableRouteModes.map { context.getString(it.labelRes) }

        refreshCodecOptions(
            preferredCodec = preferred.codec,
            preferredSource = preferred.source,
            preferredChannelMode = preferred.channelMode,
            preferredRate = preferred.sampleRate,
            preferredBitrateKbps = preferred.bitrateKbps,
        )
        capabilityUiReady = true
        refreshRetentionFields(preserveActiveInputs = true)
        refreshMoveRecordingsAvailability()
        saveCurrentToSnapshot(currentSnapshot)
        if (shouldResetOriginalSnapshot) {
            originalSnapshot.copyFrom(currentSnapshot)
            hasUnsavedChanges = false
        } else {
            pushUndoState()
        }
    }

    fun refreshCapabilityUiAsync(resetOriginalSnapshot: Boolean) {
        val generation = ++capabilityRefreshGeneration
        val preferred = SettingsSnapshot().also { saveCurrentToSnapshot(it) }
        scope.launch(Dispatchers.Default) {
            warmRecorderCapabilityCache(context)
            withContext(Dispatchers.Main) {
                if (generation != capabilityRefreshGeneration) return@withContext
                applyResolvedCapabilityUi(preferred, resetOriginalSnapshot)
            }
        }
    }

    fun restorePreviousSettings() {
        if (!hasUnsavedChanges) return
        val prev = originalSnapshot

        activeRetentionMode = prev.retentionMode
        retentionTimeSecondsValue = prev.retentionTime
        retentionSizeMbValue = prev.retentionSizeMb
        historyChunkSecondsValue = prev.historyChunkSeconds
        selectedAutoMergeMode = prev.autoMergeMode
        autoMergeDivisorValue = prev.autoMergeDivisor
        autoMergeCustomSecondsValue = prev.autoMergeCustomSeconds
        autoMergeCustomSizeMibValue = prev.autoMergeCustomSizeMib
        autoMergeEagerEnabled = prev.autoMergeEagerEnabled
        selectedExportTreeUri = prev.exportDirectoryUri?.let(Uri::parse)

        selectedTheme = prev.themeMode
        selectedThemeLabel = context.getString(prev.themeMode.labelRes)
        selectedFormat = prev.format ?: availableFormats.first()
        selectedFormatLabel = context.getString((prev.format ?: availableFormats.first()).labelRes)
        selectedCodec = prev.codec ?: availableCodecs.first()
        selectedCodecLabel = context.getString((prev.codec ?: availableCodecs.first()).labelRes)
        selectedRoute = prev.route ?: availableRouteModes.first()
        selectedRouteLabel = context.getString((prev.route ?: availableRouteModes.first()).labelRes)
        selectedAutoMergeLabel = context.getString(prev.autoMergeMode.labelRes)

        refreshHistorySettingsUi()
        if (capabilityUiReady) {
            refreshCodecOptions(
                preferredCodec = prev.codec,
                preferredSource = prev.source,
                preferredChannelMode = prev.channelMode,
                preferredRate = prev.sampleRate,
                preferredBitrateKbps = prev.bitrateKbps,
            )
        } else {
            refreshBitrateField(preferredKbps = prev.bitrateKbps)
        }

        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSnapshot.copyFrom(prev)
        hasUnsavedChanges = false
        if (!capabilityUiReady) refreshCapabilityUiAsync(false)
    }

    fun persistSettings(showFeedback: Boolean): Boolean {
        clearErrors()

        val format = selectedFormat
        val codec = selectedCodec
        val sampleFormat = selectedSampleFormat
        val channelMode = selectedChannelMode
        val route = selectedRoute
        val source = selectedSource
        val sampleRate = selectedSampleRate
        val bitrateKbpsVal = currentBitrateKbpsOrNull()

        val retentionTime = if (activeRetentionMode == RetentionMode.TIME) {
            parseDurationInput(retentionTimeText.trim())
        } else {
            retentionTimeSecondsValue
        }
        if (retentionTime == null || retentionTime <= 0) {
            retentionTimeError = context.getString(R.string.retention_time_invalid)
            return false
        }

        val sizeMb = if (activeRetentionMode == RetentionMode.SIZE) {
            parseRetentionSizeMib(retentionSizeText.trim())
        } else {
            retentionSizeMbValue
        }
        if (sizeMb == null || sizeMb <= 0.0) {
            retentionSizeError = context.getString(R.string.custom_memory_size_invalid)
            return false
        }

        val requestedSizeBytes = rawMegabytesToBytes(sizeMb)

        val bitrateRangeLocal = codecBitrateRangeKbps(codec)
        if (bitrateRangeLocal != null && (bitrateKbpsVal == null || bitrateKbpsVal !in bitrateRangeLocal)) {
            Toast.makeText(context, context.getString(R.string.codec_bitrate_invalid, bitrateRangeLocal.first, bitrateRangeLocal.last), Toast.LENGTH_SHORT).show()
            return false
        }

        val historyChunkSeconds = parseDurationInput(historyChunkText.trim())
        if (historyChunkSeconds == null) {
            historyChunkError = context.getString(R.string.retention_time_invalid)
            return false
        }
        val historyChunkRange = historyChunkSecondsRange()
        if (historyChunkSeconds !in historyChunkRange) {
            historyChunkError = context.getString(
                R.string.history_chunk_range_invalid,
                formatDurationInput(historyChunkRange.first),
                formatDurationInput(historyChunkRange.last),
            )
            return false
        }

        val autoMergeDivisor = when (selectedAutoMergeMode) {
            AutoMergeMode.RATIO -> autoMergeValueText.trim().toIntOrNull()
            else -> autoMergeDivisorValue
        }
        val autoMergeCustomSeconds = when (selectedAutoMergeMode) {
            AutoMergeMode.CUSTOM_TIME -> parseDurationInput(autoMergeValueText.trim())
            else -> autoMergeCustomSecondsValue
        }
        val autoMergeCustomSizeMib = when (selectedAutoMergeMode) {
            AutoMergeMode.CUSTOM_SIZE -> parseRetentionSizeMib(autoMergeValueText.trim())
            else -> autoMergeCustomSizeMibValue
        }
        val divisorRange = autoMergeDivisorRange()
        if (selectedAutoMergeMode == AutoMergeMode.RATIO) {
            if (autoMergeDivisor == null || autoMergeDivisor !in divisorRange) {
                autoMergeValueError = context.getString(R.string.auto_merge_divisor_invalid, divisorRange.first, divisorRange.last)
                return false
            }
        }
        val customRange = autoMergeCustomSecondsRange()
        if (selectedAutoMergeMode == AutoMergeMode.CUSTOM_TIME) {
            if (autoMergeCustomSeconds == null) { autoMergeValueError = context.getString(R.string.retention_time_invalid); return false }
            if (autoMergeCustomSeconds !in customRange) {
                autoMergeValueError = context.getString(R.string.history_chunk_range_invalid, formatDurationInput(customRange.first), formatDurationInput(customRange.last))
                return false
            }
        }
        val customSizeRange = autoMergeCustomSizeRangeMib()
        if (selectedAutoMergeMode == AutoMergeMode.CUSTOM_SIZE) {
            if (autoMergeCustomSizeMib == null) { autoMergeValueError = context.getString(R.string.auto_merge_custom_size_invalid); return false }
            if (autoMergeCustomSizeMib !in customSizeRange) {
                autoMergeValueError = context.getString(R.string.auto_merge_custom_size_range_invalid, formatRetentionSizeMib(customSizeRange.start), formatRetentionSizeMib(customSizeRange.endInclusive))
                return false
            }
        }

        retentionTimeSecondsValue = retentionTime
        retentionSizeMbValue = sizeMb
        historyChunkSecondsValue = historyChunkSeconds
        autoMergeDivisorValue = autoMergeDivisor ?: autoMergeDivisorValue
        autoMergeCustomSecondsValue = autoMergeCustomSeconds ?: autoMergeCustomSecondsValue
        autoMergeCustomSizeMibValue = autoMergeCustomSizeMib ?: autoMergeCustomSizeMibValue

        setConfiguredThemeMode(context, selectedTheme)
        getRecorderPreferences(context).edit()
            .putInt(PrefKey.RETENTION_MODE, activeRetentionMode.ordinal)
            .putLong(PrefKey.RETENTION_SECONDS, retentionTime.toLong())
            .putLong(PrefKey.AUDIO_MEMORY_SIZE, requestedSizeBytes)
            .putString(PrefKey.OUTPUT_FORMAT, format.prefValue)
            .putString(PrefKey.OUTPUT_CODEC, codec.prefValue)
            .putString(PrefKey.PCM_SAMPLE_FORMAT, sampleFormat.prefValue)
            .putInt(PrefKey.OUTPUT_BITRATE_KBPS, bitrateKbpsVal ?: (effectiveCodecBitrateKbps() ?: 0))
            .putInt(PrefKey.AUDIO_SOURCE, source.sourceValue)
            .putString(PrefKey.CHANNEL_MODE, channelMode.prefValue)
            .putString(PrefKey.INPUT_ROUTE, route.prefValue)
            .putInt(PrefKey.SAMPLE_RATE, sampleRate)
            .putInt(PrefKey.HISTORY_CHUNK_SECONDS, historyChunkSecondsValue)
            .putString(PrefKey.AUTO_MERGE_MODE, selectedAutoMergeMode.prefValue)
            .putInt(PrefKey.AUTO_MERGE_DIVISOR, autoMergeDivisorValue)
            .putInt(PrefKey.AUTO_MERGE_CUSTOM_SECONDS, autoMergeCustomSecondsValue)
            .putString(PrefKey.AUTO_MERGE_CUSTOM_SIZE_MIB, formatRetentionSizeMib(autoMergeCustomSizeMibValue))
            .putBoolean(PrefKey.AUTO_MERGE_EAGER_ENABLED, autoMergeEagerEnabled)
            .putBoolean(PrefKey.BUFFER_DISK_CACHE_ENABLED, currentSnapshot.persistentBufferEnabled)
            .putBoolean(PrefKey.AGGRESSIVE_RESTART_ENABLED, currentSnapshot.aggressiveRestartEnabled)
            .putBoolean(PrefKey.WAKE_LOCK_ENABLED, currentSnapshot.wakeLockEnabled)
            .apply()
        setConfiguredExportTreeUri(context, selectedExportTreeUri)
        onThemeChanged(selectedTheme)

        val currentService = service
        if (currentService == null) {
            if (showFeedback) Toast.makeText(context, R.string.settings_saved_next_start, Toast.LENGTH_SHORT).show()
            return true
        }

        when (currentService.applyUpdatedPreferences()) {
            TimeTravelService.ApplySettingsResult.BLOCKED_RECORDING -> {
                if (showFeedback) Toast.makeText(context, R.string.settings_apply_blocked_recording, Toast.LENGTH_SHORT).show()
            }
            TimeTravelService.ApplySettingsResult.APPLIED_NOW -> {
                if (showFeedback) Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            TimeTravelService.ApplySettingsResult.REENCODE_REQUIRED -> {
                if (showFeedback) Toast.makeText(context, R.string.settings_saved_reencode_history, Toast.LENGTH_SHORT).show()
            }
            TimeTravelService.ApplySettingsResult.DEFERRED_UNTIL_RESTART -> {
                if (showFeedback) Toast.makeText(context, R.string.settings_saved_deferred_input, Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    fun bindUiFromPreferences() {
        val prefs = getRecorderPreferences(context)

        val configuredThemeMode = getConfiguredThemeMode(context)
        val configuredMode = getConfiguredRetentionMode(context)
        val configuredTime = getConfiguredRetentionSeconds(context).toInt()
        val storedSizeBytes = prefs.getLong(PrefKey.AUDIO_MEMORY_SIZE, 512L * BYTES_IN_MEGABYTE)
        val configuredFormat = getConfiguredOutputFormat(context)
        val configuredCodec = getConfiguredOutputCodec(context)
        val configuredSampleFormatVal = getConfiguredPcmSampleFormat(context)
        val configuredRouteVal = getConfiguredInputRouteMode(context)
        val configuredSourceVal = getConfiguredAudioSourceMode(context)
        val configuredChannelModeVal = getConfiguredChannelMode(context)
        val configuredRateVal = prefs.getInt(
            PrefKey.SAMPLE_RATE,
            getPreferredSampleRate(context, configuredSourceVal, configuredRouteVal, configuredFormat, configuredCodec, configuredChannelModeVal),
        ).takeIf { it > 0 }
            ?: getPreferredSampleRate(context, configuredSourceVal, configuredRouteVal, configuredFormat, configuredCodec, configuredChannelModeVal)
        val configuredBitrateKbpsVal = getConfiguredCodecBitrateKbps(context, configuredCodec, configuredRateVal, configuredChannelModeVal.channelCount) ?: 0
        val configuredHistoryChunkSecondsVal = getConfiguredHistoryChunkSeconds(context)
        val configuredAutoMergeModeVal = getConfiguredAutoMergeMode(context)
        val configuredAutoMergeDivisorVal = getConfiguredAutoMergeDivisor(context)
        val configuredAutoMergeCustomSecondsVal = getConfiguredAutoMergeCustomSeconds(context)
        val configuredAutoMergeCustomSizeMibVal = getConfiguredAutoMergeCustomSizeMib(context)
        val configuredAutoMergeEagerEnabledVal = isConfiguredAutoMergeEagerEnabled(context)
        val configuredExportTreeUriVal = getConfiguredExportTreeUri(context)

        activeRetentionMode = configuredMode
        retentionTimeSecondsValue = configuredTime
        retentionSizeMbValue = bytesToMegabytes(storedSizeBytes)
        historyChunkSecondsValue = configuredHistoryChunkSecondsVal
        selectedAutoMergeMode = configuredAutoMergeModeVal
        autoMergeDivisorValue = configuredAutoMergeDivisorVal
        autoMergeCustomSecondsValue = configuredAutoMergeCustomSecondsVal
        autoMergeCustomSizeMibValue = configuredAutoMergeCustomSizeMibVal
        autoMergeEagerEnabled = configuredAutoMergeEagerEnabledVal
        selectedExportTreeUri = configuredExportTreeUriVal

        selectedTheme = configuredThemeMode
        selectedThemeLabel = context.getString(configuredThemeMode.labelRes)

        availableFormats = supportedFormats()
        selectedFormat = configuredFormat.takeIf { it in availableFormats } ?: availableFormats.first()
        formatLabels = availableFormats.map { context.getString(it.labelRes) }
        selectedFormatLabel = context.getString(selectedFormat.labelRes)

        availableRouteModes = InputRouteMode.entries
        selectedRoute = configuredRouteVal
        routeLabels = availableRouteModes.map { context.getString(it.labelRes) }
        selectedRouteLabel = context.getString(configuredRouteVal.labelRes)

        availableCodecs = supportedCodecs(selectedFormat)
        selectedCodec = configuredCodec.takeIf { it in availableCodecs } ?: availableCodecs.first()
        codecLabels = availableCodecs.map { context.getString(it.labelRes) }
        selectedCodecLabel = context.getString(selectedCodec.labelRes)

        selectedSampleFormat = configuredSampleFormatVal
        sampleFormatLabels = PcmSampleFormat.entries.map { context.getString(it.labelRes) }
        selectedSampleFormatLabel = context.getString(configuredSampleFormatVal.labelRes)

        availableSourceModes = AudioSourceMode.availableModes()
        selectedSource = configuredSourceVal
        sourceLabels = availableSourceModes.map { context.getString(it.labelRes) }
        selectedSourceLabel = context.getString(configuredSourceVal.labelRes)

        availableChannelModes = ChannelMode.entries
        selectedChannelMode = configuredChannelModeVal
        channelModeLabels = availableChannelModes.map { context.getString(it.labelRes) }
        selectedChannelModeLabel = context.getString(configuredChannelModeVal.labelRes)

        autoMergeLabels = AutoMergeMode.entries.map { context.getString(it.labelRes) }
        selectedAutoMergeLabel = context.getString(configuredAutoMergeModeVal.labelRes)
        refreshHistorySettingsUi()

        availableSampleRates = buildList {
            add(configuredRateVal)
            addAll(standardSampleRates())
        }.filter { it > 0 }.distinct()
        selectedSampleRate = configuredRateVal
        sampleRateLabels = availableSampleRates.map { sampleRateLabel(it) }
        selectedSampleRateLabel = sampleRateLabel(configuredRateVal)

        refreshBitrateField(preferredKbps = configuredBitrateKbpsVal)
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSnapshot.persistentBufferEnabled = isDiskBufferCacheEnabled(context)
        currentSnapshot.aggressiveRestartEnabled = isAggressiveRestartEnabled(context)
        currentSnapshot.wakeLockEnabled = isWakeLockEnabled(context)

        saveCurrentToSnapshot(currentSnapshot)
        originalSnapshot.copyFrom(currentSnapshot)
        hasUnsavedChanges = false
        refreshCapabilityUiAsync(resetOriginalSnapshot = true)
    }

    val exportDirectoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        selectedExportTreeUri = treeUri
        exportPathText = describeOutputDirectory(context, treeUri)
        saveCurrentToSnapshot(currentSnapshot)
        pushUndoState()
        refreshMoveRecordingsAvailability()
    }

    val connection = remember {
        object : android.content.ServiceConnection {
            override fun onServiceConnected(className: ComponentName, binder: IBinder) {
                val typedBinder = binder as TimeTravelService.BackgroundRecorderBinder
                service = typedBinder.service
                serviceBound = true
            }
            override fun onServiceDisconnected(arg0: ComponentName) {
                service = null
                serviceBound = false
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, TimeTravelService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (bound) {
                context.unbindService(connection)
            }
        }
    }

    fun openBatteryOptimizationSettings() {
        val intents = buildList {
            if (!isIgnoringBatteryOptimizations(context)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            }
            add(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
                data = Uri.parse("package:${context.packageName}")
                putExtra("package_name", context.packageName)
                putExtra("packageName", context.packageName)
            })
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            })
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
        val launched = intents.any { intent ->
            if (intent.resolveActivity(context.packageManager) == null) return@any false
            runCatching { context.startActivity(intent); true }.getOrDefault(false)
        }
        if (!launched) Toast.makeText(context, R.string.no_app_available, Toast.LENGTH_SHORT).show()
    }

    fun moveExistingRecordings() {
        if (!persistSettings(showFeedback = false)) return
        canMove = false
        scope.launch {
            val result = RecordingRepository.moveAllToConfiguredDirectory(context)
            val message = when {
                result.moved == 0 && result.removedMissing == 0 -> context.getString(R.string.move_recordings_none)
                result.removedMissing > 0 -> {
                    val movedMessage = context.resources.getQuantityString(R.plurals.move_recordings_done, result.moved, result.moved)
                    val removedMessage = context.resources.getQuantityString(R.plurals.move_recordings_removed_missing, result.removedMissing, result.removedMissing)
                    "$movedMessage $removedMessage"
                }
                else -> context.resources.getQuantityString(R.plurals.move_recordings_done, result.moved, result.moved)
            }
            refreshMoveRecordingsAvailability()
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { bindUiFromPreferences() }

    val bitrateVisible = bitrateRange.first <= bitrateRange.last
    val estimatePrefixVal = if (selectedFormat.isPcmContainer) TimeTravelConfig.ESTIMATE_EXACT_PREFIX else TimeTravelConfig.ESTIMATE_APPROX_PREFIX

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        if (hasUnsavedChanges) persistSettings(showFeedback = false)
                        onBack()
                    }) {
                        Icon(
                            painter = painterResource(if (hasUnsavedChanges) R.drawable.ic_check else R.drawable.ic_close),
                            contentDescription = stringResource(if (hasUnsavedChanges) R.string.done else R.string.close),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { restorePreviousSettings() },
                        enabled = hasUnsavedChanges,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_undo),
                            contentDescription = stringResource(R.string.undo),
                            tint = if (hasUnsavedChanges) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.theme_title))
            SettingsDropdown(
                label = stringResource(R.string.theme_title),
                selectedValue = selectedThemeLabel,
                options = themeLabels,
                onOptionSelected = { label ->
                    selectedThemeLabel = label
                    selectedTheme = AppThemeMode.entries.first { context.getString(it.labelRes) == label }
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
            )

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SectionTitle(stringResource(R.string.retention_mode_title))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextField(
                    label = stringResource(R.string.retention_time_label),
                    value = retentionTimeText,
                    onValueChange = { v ->
                        retentionTimeText = v
                        activateRetentionMode(RetentionMode.TIME)
                        updateRetentionValuesFromActiveInput()
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    error = retentionTimeError,
                    prefix = if (activeRetentionMode == RetentionMode.TIME) null else estimatePrefixVal,
                    modifier = Modifier.weight(1f),
                )
                SettingsTextField(
                    label = stringResource(R.string.retention_size_label),
                    value = retentionSizeText,
                    onValueChange = { v ->
                        retentionSizeText = v
                        activateRetentionMode(RetentionMode.SIZE)
                        updateRetentionValuesFromActiveInput()
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    error = retentionSizeError,
                    prefix = if (activeRetentionMode == RetentionMode.SIZE) null else estimatePrefixVal,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SectionTitle(stringResource(R.string.recording_settings_title))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsDropdown(
                    label = stringResource(R.string.format_label),
                    selectedValue = selectedFormatLabel,
                    options = formatLabels,
                    onOptionSelected = { label ->
                        selectedFormatLabel = label
                        selectedFormat = availableFormats.first { context.getString(it.labelRes) == label }
                        refreshCodecOptions()
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsDropdown(
                    label = stringResource(R.string.channel_mode_label),
                    selectedValue = selectedChannelModeLabel,
                    options = channelModeLabels,
                    onOptionSelected = { label ->
                        selectedChannelModeLabel = label
                        selectedChannelMode = availableChannelModes.first { context.getString(it.labelRes) == label }
                        refreshSampleRates()
                        refreshBitrateField()
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsDropdown(
                    label = stringResource(R.string.codec_label),
                    selectedValue = selectedCodecLabel,
                    options = codecLabels,
                    onOptionSelected = { label ->
                        selectedCodecLabel = label
                        selectedCodec = availableCodecs.first { context.getString(it.labelRes) == label }
                        refreshSourceModes()
                        refreshBitrateField()
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsDropdown(
                    label = stringResource(R.string.sample_rate_label),
                    selectedValue = selectedSampleRateLabel,
                    options = sampleRateLabels,
                    onOptionSelected = { label ->
                        selectedSampleRateLabel = label
                        availableSampleRates.firstOrNull { sampleRateLabel(it) == label }?.let { selectedSampleRate = it }
                        refreshBitrateField()
                        refreshRetentionFields(preserveActiveInputs = true)
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (bitrateVisible) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.codec_bitrate_label), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.codec_bitrate_value, bitrateKbps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = bitrateKbps.toFloat(),
                        onValueChange = { bitrateKbps = it.roundToInt(); saveCurrentToSnapshot(currentSnapshot); pushUndoState() },
                        valueRange = bitrateRange.first.toFloat()..bitrateRange.last.toFloat(),
                    )
                }
            }
            SettingsDropdown(
                label = stringResource(R.string.sample_format_label),
                selectedValue = selectedSampleFormatLabel,
                options = sampleFormatLabels,
                onOptionSelected = { label ->
                    selectedSampleFormatLabel = label
                    selectedSampleFormat = PcmSampleFormat.entries.first { context.getString(it.labelRes) == label }
                    refreshRetentionFields(preserveActiveInputs = true)
                    saveCurrentToSnapshot(currentSnapshot)
                    pushUndoState()
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsDropdown(
                    label = stringResource(R.string.audio_source_label),
                    selectedValue = selectedSourceLabel,
                    options = sourceLabels,
                    onOptionSelected = { label ->
                        selectedSourceLabel = label
                        selectedSource = availableSourceModes.first { context.getString(it.labelRes) == label }
                        refreshChannelModes()
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
                SettingsDropdown(
                    label = stringResource(R.string.input_route_label),
                    selectedValue = selectedRouteLabel,
                    options = routeLabels,
                    onOptionSelected = { label ->
                        selectedRouteLabel = label
                        selectedRoute = availableRouteModes.first { context.getString(it.labelRes) == label }
                        refreshCodecOptions()
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SectionTitle(stringResource(R.string.history_settings_title))
            Text(
                text = stringResource(R.string.history_chunk_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsTextField(
                    label = stringResource(R.string.history_chunk_label),
                    value = historyChunkText,
                    onValueChange = { v ->
                        historyChunkText = v
                        parseDurationInput(v.trim())?.let { historyChunkSecondsValue = it }
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    error = historyChunkError,
                    modifier = Modifier.weight(1f),
                )
                SettingsDropdown(
                    label = stringResource(R.string.auto_merge_label),
                    selectedValue = selectedAutoMergeLabel,
                    options = autoMergeLabels,
                    onOptionSelected = { label ->
                        selectedAutoMergeLabel = label
                        selectedAutoMergeMode = AutoMergeMode.entries.first { context.getString(it.labelRes) == label }
                        refreshAutoMergeValueUi()
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (selectedAutoMergeMode != AutoMergeMode.OFF) {
                SettingsTextField(
                    label = when (selectedAutoMergeMode) {
                        AutoMergeMode.RATIO -> stringResource(R.string.auto_merge_ratio_value_label)
                        AutoMergeMode.CUSTOM_TIME -> stringResource(R.string.auto_merge_custom_time_value_label)
                        AutoMergeMode.CUSTOM_SIZE -> stringResource(R.string.auto_merge_custom_size_value_label)
                        AutoMergeMode.OFF -> ""
                    },
                    value = autoMergeValueText,
                    onValueChange = { v ->
                        autoMergeValueText = v
                        when (selectedAutoMergeMode) {
                            AutoMergeMode.RATIO -> v.trim().toIntOrNull()?.let { autoMergeDivisorValue = it }
                            AutoMergeMode.CUSTOM_TIME -> parseDurationInput(v.trim())?.let { autoMergeCustomSecondsValue = it }
                            AutoMergeMode.CUSTOM_SIZE -> parseRetentionSizeMib(v.trim())?.let { autoMergeCustomSizeMibValue = it }
                            AutoMergeMode.OFF -> {}
                        }
                        saveCurrentToSnapshot(currentSnapshot)
                        pushUndoState()
                    },
                    error = autoMergeValueError,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                )
            }
            SwitchRow(
                label = stringResource(R.string.auto_merge_eager_label),
                summary = stringResource(R.string.auto_merge_eager_summary),
                checked = autoMergeEagerEnabled,
                onCheckedChange = { autoMergeEagerEnabled = it; saveCurrentToSnapshot(currentSnapshot); pushUndoState() },
            )

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SectionTitle(stringResource(R.string.storage_settings_title))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = exportPathText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedExportTreeUri != null) {
                        IconButton(onClick = {
                            selectedExportTreeUri = null
                            refreshExportDirectoryUi()
                            refreshMoveRecordingsAvailability()
                            saveCurrentToSnapshot(currentSnapshot)
                            pushUndoState()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_reset),
                                contentDescription = stringResource(R.string.default_folder),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { exportDirectoryLauncher.launch(selectedExportTreeUri) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = stringResource(R.string.choose_folder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { moveExistingRecordings() },
                    enabled = canMove,
                ) {
                    Text(stringResource(R.string.move_recordings))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 24.dp))

            SectionTitle(stringResource(R.string.background_persistence_title))
            SwitchRow(
                label = stringResource(R.string.persistent_buffer_cache_label),
                summary = stringResource(R.string.persistent_buffer_cache_summary),
                checked = currentSnapshot.persistentBufferEnabled,
                onCheckedChange = { currentSnapshot.persistentBufferEnabled = it; saveCurrentToSnapshot(currentSnapshot); pushUndoState() },
            )
            SwitchRow(
                label = stringResource(R.string.aggressive_restart_label),
                summary = stringResource(R.string.aggressive_restart_summary),
                checked = currentSnapshot.aggressiveRestartEnabled,
                onCheckedChange = { currentSnapshot.aggressiveRestartEnabled = it; saveCurrentToSnapshot(currentSnapshot); pushUndoState() },
            )
            SwitchRow(
                label = stringResource(R.string.wake_lock_label),
                summary = stringResource(R.string.wake_lock_summary),
                checked = currentSnapshot.wakeLockEnabled,
                onCheckedChange = { currentSnapshot.wakeLockEnabled = it; saveCurrentToSnapshot(currentSnapshot); pushUndoState() },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (batteryOptimizationRestricted) R.string.battery_optimization_status_limited
                        else R.string.battery_optimization_status_ok,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (batteryOptimizationRestricted) {
                    TextButton(onClick = { openBatteryOptimizationSettings() }) {
                        Text(stringResource(R.string.battery_optimization_button))
                    }
                }
            }

        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun SettingsDropdown(
    label: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().clickable(onClick = { expanded = true }),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    prefix: String? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        prefix = if (prefix != null) {{ Text(prefix) }} else null,
        modifier = modifier,
    )
}

@Composable
private fun SwitchRow(
    label: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun bytesToMegabytes(bytes: Long): Double {
    return (bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE.toDouble())
}

private fun rawMegabytesToBytes(memoryInMegabytes: Double): Long {
    if (memoryInMegabytes <= 0.0) return 0L
    if (memoryInMegabytes >= Long.MAX_VALUE / BYTES_IN_MEGABYTE.toDouble()) return Long.MAX_VALUE
    return (memoryInMegabytes * BYTES_IN_MEGABYTE.toDouble()).roundToLong()
}

private fun parseRetentionSizeMib(value: String): Double? {
    return value.trim().replace(',', '.').toDoubleOrNull()
}

private fun formatRetentionSizeMib(value: Double): String {
    return retentionSizeFormatter.format(value.coerceAtLeast(0.0))
}
