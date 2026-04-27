@file:JvmName("RecorderPreferences")

package app.timetravel

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.PowerManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val BYTES_PER_PCM_SAMPLE = 2L
private const val WAV_HEADER_BYTES = 44L
private const val MP4_CONTAINER_BASE_OVERHEAD_BYTES = 1536L
private const val MP4_CONTAINER_BYTES_PER_AAC_ACCESS_UNIT = 8L
private const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024L
private const val MIN_AAC_BITRATE_KBPS = 32
private const val MAX_AAC_BITRATE_KBPS = 320
private val SAMPLE_RATE_CANDIDATES = intArrayOf(48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 11_025, 8_000)
private val codecSupportCache = ConcurrentHashMap<CodecSupportKey, Boolean>()
private val inputConfigCache = ConcurrentHashMap<InputConfigKey, Boolean>()
private val sampleRatesCache = ConcurrentHashMap<SampleRatesKey, List<Int>>()
private val sourceModesCache = ConcurrentHashMap<SourceModesKey, List<AudioSourceMode>>()
private val channelModesCache = ConcurrentHashMap<ChannelModesKey, List<ChannelMode>>()
private val capabilityWarmLock = Any()

@Volatile
private var cachedSupportedCodecs: List<ExportCodec>? = null

@Volatile
private var capabilityCacheWarm = false

private data class CodecSupportKey(
    val codec: ExportCodec,
    val sampleRate: Int,
    val channelMode: ChannelMode,
)

private data class InputConfigKey(
    val sampleRate: Int,
    val sourceMode: AudioSourceMode,
    val routeMode: InputRouteMode,
    val channelMode: ChannelMode,
    val hasBuiltInMic: Boolean,
)

private data class SampleRatesKey(
    val sourceMode: AudioSourceMode,
    val routeMode: InputRouteMode,
    val codec: ExportCodec,
    val channelMode: ChannelMode,
    val hasBuiltInMic: Boolean,
)

private data class SourceModesKey(
    val routeMode: InputRouteMode,
    val codec: ExportCodec,
    val hasBuiltInMic: Boolean,
)

private data class ChannelModesKey(
    val sourceMode: AudioSourceMode,
    val routeMode: InputRouteMode,
    val codec: ExportCodec,
    val hasBuiltInMic: Boolean,
)

private inline fun <K : Any, V : Any> ConcurrentHashMap<K, V>.cached(
    key: K,
    producer: () -> V,
): V {
    this[key]?.let { return it }
    val value = producer()
    val existing = putIfAbsent(key, value)
    return existing ?: value
}

enum class RetentionMode(val prefValue: String, @StringRes val labelRes: Int) {
    SIZE(TimeTravelConfig.RETENTION_MODE_SIZE, R.string.retention_mode_size),
    TIME(TimeTravelConfig.RETENTION_MODE_TIME, R.string.retention_mode_time),
    ;

    companion object {
        fun fromPrefValue(value: String?): RetentionMode {
            return entries.firstOrNull { it.prefValue == value } ?: SIZE
        }
    }
}

enum class ExportCodec(
    val prefValue: String,
    @StringRes val labelRes: Int,
    val extension: String,
    val outputMimeType: String,
    val encoderMimeType: String? = null,
    val muxerOutputFormat: Int? = null,
    val aacProfile: Int? = null,
    val bitrateRangeKbps: IntRange? = null,
    val bitrateStepKbps: Int = 8,
) {
    AAC_LC(
        TimeTravelConfig.OUTPUT_CODEC_AAC_LC,
        R.string.codec_aac_lc,
        "m4a",
        "audio/mp4",
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
        MIN_AAC_BITRATE_KBPS..MAX_AAC_BITRATE_KBPS,
    ),
    HE_AAC(
        TimeTravelConfig.OUTPUT_CODEC_HE_AAC,
        R.string.codec_he_aac,
        "m4a",
        "audio/mp4",
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        MediaCodecInfo.CodecProfileLevel.AACObjectHE,
        MIN_AAC_BITRATE_KBPS..MAX_AAC_BITRATE_KBPS,
    ),
    HE_AAC_V2(
        TimeTravelConfig.OUTPUT_CODEC_HE_AAC_V2,
        R.string.codec_he_aac_v2,
        "m4a",
        "audio/mp4",
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS,
        MIN_AAC_BITRATE_KBPS..MAX_AAC_BITRATE_KBPS,
    ),
    XHE_AAC(
        TimeTravelConfig.OUTPUT_CODEC_XHE_AAC,
        R.string.codec_xhe_aac,
        "m4a",
        "audio/mp4",
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        MediaCodecInfo.CodecProfileLevel.AACObjectXHE,
        MIN_AAC_BITRATE_KBPS..MAX_AAC_BITRATE_KBPS,
    ),
    AMR_WB(
        TimeTravelConfig.OUTPUT_CODEC_AMR_WB,
        R.string.codec_amr_wb,
        "3gp",
        "audio/3gpp",
        MediaFormat.MIMETYPE_AUDIO_AMR_WB,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP,
    ),
    AMR_NB(
        TimeTravelConfig.OUTPUT_CODEC_AMR_NB,
        R.string.codec_amr_nb,
        "3gp",
        "audio/3gpp",
        MediaFormat.MIMETYPE_AUDIO_AMR_NB,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP,
    ),
    WAV(TimeTravelConfig.OUTPUT_CODEC_WAV, R.string.codec_wav, "wav", "audio/wav"),
    ;

    val isAacFamily: Boolean
        get() = encoderMimeType == MediaFormat.MIMETYPE_AUDIO_AAC

    companion object {
        fun fromPrefValue(value: String?): ExportCodec {
            return when (value) {
                TimeTravelConfig.OUTPUT_CODEC_AAC -> AAC_LC
                else -> entries.firstOrNull { it.prefValue == value } ?: WAV
            }
        }
    }
}

enum class AudioSourceMode(
    val sourceValue: Int,
    @StringRes val labelRes: Int,
) {
    DEFAULT(MediaRecorder.AudioSource.DEFAULT, R.string.audio_source_default),
    MIC(MediaRecorder.AudioSource.MIC, R.string.audio_source_mic),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, R.string.audio_source_voice_recognition),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, R.string.audio_source_camcorder),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, R.string.audio_source_unprocessed),
    VOICE_PERFORMANCE(MediaRecorder.AudioSource.VOICE_PERFORMANCE, R.string.audio_source_voice_performance),
    ;

    companion object {
        fun fromSourceValue(value: Int): AudioSourceMode {
            return entries.firstOrNull { it.sourceValue == value } ?: MIC
        }

        fun availableModes(): List<AudioSourceMode> = entries
    }
}

enum class InputRouteMode(val prefValue: String, @StringRes val labelRes: Int) {
    AUTO(TimeTravelConfig.INPUT_ROUTE_AUTO, R.string.input_route_auto),
    BUILTIN_MIC(TimeTravelConfig.INPUT_ROUTE_BUILTIN_MIC, R.string.input_route_builtin_mic),
    ;

    companion object {
        fun fromPrefValue(value: String?): InputRouteMode {
            return entries.firstOrNull { it.prefValue == value } ?: AUTO
        }
    }
}

enum class ChannelMode(
    val prefValue: String,
    @StringRes val labelRes: Int,
    val channelCount: Int,
    val inputChannelMask: Int,
) {
    MONO(TimeTravelConfig.CHANNEL_MODE_MONO, R.string.channel_mode_mono, 1, AudioFormat.CHANNEL_IN_MONO),
    STEREO(TimeTravelConfig.CHANNEL_MODE_STEREO, R.string.channel_mode_stereo, 2, AudioFormat.CHANNEL_IN_STEREO),
    ;

    companion object {
        fun fromPrefValue(value: String?): ChannelMode {
            return entries.firstOrNull { it.prefValue == value } ?: MONO
        }
    }
}

enum class AppThemeMode(
    val prefValue: String,
    @StringRes val labelRes: Int,
    val nightMode: Int,
) {
    SYSTEM(TimeTravelConfig.THEME_MODE_SYSTEM, R.string.theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(TimeTravelConfig.THEME_MODE_LIGHT, R.string.theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK(TimeTravelConfig.THEME_MODE_DARK, R.string.theme_dark, AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun fromPrefValue(value: String?): AppThemeMode {
            return entries.firstOrNull { it.prefValue == value } ?: SYSTEM
        }
    }
}

fun getRecorderPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(TimeTravelConfig.PACKAGE_NAME, Context.MODE_PRIVATE)
}

fun getConfiguredRetentionMode(context: Context): RetentionMode {
    return RetentionMode.fromPrefValue(
        getRecorderPreferences(context).getString(
            TimeTravelConfig.RETENTION_MODE_KEY,
            TimeTravelConfig.RETENTION_MODE_SIZE,
        ),
    )
}

fun isDiskBufferCacheEnabled(context: Context): Boolean {
    return getRecorderPreferences(context).getBoolean(TimeTravelConfig.BUFFER_DISK_CACHE_ENABLED_KEY, true)
}

fun isAggressiveRestartEnabled(context: Context): Boolean {
    return getRecorderPreferences(context).getBoolean(TimeTravelConfig.AGGRESSIVE_RESTART_ENABLED_KEY, true)
}

fun isWakeLockEnabled(context: Context): Boolean {
    return getRecorderPreferences(context).getBoolean(TimeTravelConfig.WAKE_LOCK_ENABLED_KEY, false)
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

fun getConfiguredThemeMode(context: Context): AppThemeMode {
    return AppThemeMode.fromPrefValue(
        getRecorderPreferences(context).getString(TimeTravelConfig.THEME_MODE_KEY, TimeTravelConfig.THEME_MODE_SYSTEM),
    )
}

fun setConfiguredThemeMode(
    context: Context,
    mode: AppThemeMode,
) {
    getRecorderPreferences(context).edit().putString(TimeTravelConfig.THEME_MODE_KEY, mode.prefValue).apply()
}

fun applyConfiguredThemeMode(context: Context) {
    AppCompatDelegate.setDefaultNightMode(getConfiguredThemeMode(context).nightMode)
}

fun getConfiguredRetentionSeconds(context: Context): Long {
    return max(60L, getRecorderPreferences(context).getLong(TimeTravelConfig.RETENTION_SECONDS_KEY, 30L * 60))
}

fun getConfiguredOutputCodec(context: Context): ExportCodec {
    val prefs = getRecorderPreferences(context)
    val preferred = getPreferredOutputCodec()
    return ExportCodec.fromPrefValue(prefs.getString(TimeTravelConfig.OUTPUT_CODEC_KEY, preferred.prefValue))
}

fun aacBitrateRangeKbps(): IntRange = MIN_AAC_BITRATE_KBPS..MAX_AAC_BITRATE_KBPS

fun codecBitrateRangeKbps(codec: ExportCodec): IntRange? = codec.bitrateRangeKbps

fun codecSupportsBitrateSelection(codec: ExportCodec): Boolean = codec.bitrateRangeKbps != null

fun codecBitrateStepKbps(codec: ExportCodec): Int = codec.bitrateStepKbps

fun defaultCodecBitrateKbps(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Int? {
    return when {
        codec.isAacFamily -> aacBitrateForSampleRate(sampleRate, channelCount) / 1000
        codec == ExportCodec.AMR_WB -> 24
        codec == ExportCodec.AMR_NB -> 12
        else -> null
    }
}

fun getConfiguredCodecBitrateKbps(
    context: Context,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Int? {
    val range = codecBitrateRangeKbps(codec) ?: return defaultCodecBitrateKbps(codec, sampleRate, channelCount)
    val fallback = defaultCodecBitrateKbps(codec, sampleRate, channelCount) ?: range.first
    val stored = getRecorderPreferences(context).getInt(TimeTravelConfig.OUTPUT_BITRATE_KBPS_KEY, fallback)
    return stored.coerceIn(range)
}

fun getConfiguredAudioSourceMode(context: Context): AudioSourceMode {
    return AudioSourceMode.fromSourceValue(
        getRecorderPreferences(context).getInt(
            TimeTravelConfig.AUDIO_SOURCE_KEY,
            AudioSourceMode.MIC.sourceValue,
        ),
    )
}

fun getConfiguredInputRouteMode(context: Context): InputRouteMode {
    val defaultValue = TimeTravelConfig.INPUT_ROUTE_AUTO
    return InputRouteMode.fromPrefValue(
        getRecorderPreferences(context).getString(TimeTravelConfig.INPUT_ROUTE_KEY, defaultValue),
    )
}

fun getConfiguredChannelMode(context: Context): ChannelMode {
    return ChannelMode.fromPrefValue(
        getRecorderPreferences(context).getString(TimeTravelConfig.CHANNEL_MODE_KEY, TimeTravelConfig.CHANNEL_MODE_MONO),
    )
}

fun getConfiguredSampleRate(
    context: Context,
    sourceMode: AudioSourceMode = getConfiguredAudioSourceMode(context),
    routeMode: InputRouteMode = getConfiguredInputRouteMode(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
    channelMode: ChannelMode = getConfiguredChannelMode(context),
): Int {
    val prefs = getRecorderPreferences(context)
    val preferred = getPreferredSampleRate(context, sourceMode, routeMode, codec, channelMode)
    val requested = prefs.getInt(TimeTravelConfig.SAMPLE_RATE_KEY, preferred)
    return requested.takeIf { it > 0 } ?: preferred
}

fun getConfiguredMemorySizeBytes(
    context: Context,
    sampleRate: Int,
    channelMode: ChannelMode = getConfiguredChannelMode(context),
): Long {
    val prefs = getRecorderPreferences(context)
    return when (getConfiguredRetentionMode(context)) {
        RetentionMode.SIZE -> prefs.getLong(
            TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY,
            Runtime.getRuntime().maxMemory() / 4,
        ).coerceAtMost(getRetentionMemoryCapBytes())

        RetentionMode.TIME -> bytesForRetentionSeconds(getConfiguredRetentionSeconds(context), sampleRate, channelMode.channelCount)
    }
}

fun bytesForRetentionSeconds(
    seconds: Long,
    sampleRate: Int,
    channelCount: Int,
): Long {
    if (sampleRate <= 0 || channelCount <= 0) return 0
    return (seconds * bytesPerSecond(sampleRate, channelCount)).coerceAtMost(getRetentionMemoryCapBytes())
}

fun retentionSecondsForBytes(
    bytes: Long,
    sampleRate: Int,
    channelCount: Int,
): Long {
    val bytesPerSecond = bytesPerSecond(sampleRate, channelCount)
    if (bytesPerSecond <= 0L) return 0
    return bytes / bytesPerSecond
}

fun parseDurationInput(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(":")
    if (parts.size == 1) {
        val minutes = parts[0].toIntOrNull() ?: return null
        return if (minutes > 0) minutes * 60 else null
    }
    if (parts.size !in 2..3) return null

    var seconds = 0
    parts.forEachIndexed { index, part ->
        val unit = part.toIntOrNull() ?: return null
        if (unit < 0) return null
        if (index > 0 && unit >= 60) return null
        seconds = seconds * 60 + unit
    }
    return if (seconds > 0) seconds else null
}

fun formatDurationInput(seconds: Int): String {
    val total = max(0, seconds)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

fun getPreferredOutputCodec(): ExportCodec {
    return supportedCodecs().firstOrNull() ?: ExportCodec.WAV
}

fun aacBitrateForSampleRate(
    sampleRate: Int,
    channelCount: Int = 1,
    bitrateKbps: Int? = null,
): Int {
    bitrateKbps?.takeIf { it > 0 }?.let {
        return it.coerceIn(aacBitrateRangeKbps()) * 1000
    }
    return when {
        channelCount >= 2 && sampleRate >= 48_000 -> 192_000
        channelCount >= 2 && sampleRate >= 24_000 -> 160_000
        channelCount >= 2 -> 128_000
        sampleRate >= 48_000 -> 128_000
        sampleRate >= 24_000 -> 96_000
        else -> 64_000
    }
}

fun estimateExportSizeBytes(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    durationSeconds: Long,
    aacBitrateKbps: Int? = null,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || durationSeconds <= 0L) {
        return 0L
    }

    return when (codec) {
        ExportCodec.WAV -> {
            WAV_HEADER_BYTES + durationSeconds * bytesPerSecond(sampleRate, channelCount)
        }

        else -> {
            val audioBytes = durationSeconds * codecBitrateBitsPerSecond(codec, sampleRate, channelCount, aacBitrateKbps) / 8L
            if (codec.isAacFamily) {
                val accessUnits = ((durationSeconds * sampleRate.toLong()) + AAC_SAMPLES_PER_ACCESS_UNIT - 1L) / AAC_SAMPLES_PER_ACCESS_UNIT
                audioBytes + MP4_CONTAINER_BASE_OVERHEAD_BYTES + accessUnits * MP4_CONTAINER_BYTES_PER_AAC_ACCESS_UNIT
            } else {
                audioBytes
            }
        }
    }
}

fun estimateExportDurationSeconds(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    sizeBytes: Long,
    aacBitrateKbps: Int? = null,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || sizeBytes <= 0L) {
        return 0L
    }

    return when (codec) {
        ExportCodec.WAV -> {
            ((sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)) /
                bytesPerSecond(sampleRate, channelCount)
        }

        else -> {
            val bitrateBytesPerSecond = codecBitrateBitsPerSecond(codec, sampleRate, channelCount, aacBitrateKbps) / 8L
            if (bitrateBytesPerSecond <= 0L) {
                0L
            } else if (codec.isAacFamily) {
                // MP4 container bytes remain a close AAC-family heuristic.
                val estimatedContainerlessBytes = (sizeBytes - MP4_CONTAINER_BASE_OVERHEAD_BYTES).coerceAtLeast(0L)
                val denominator = bitrateBytesPerSecond + sampleRate.toLong() * MP4_CONTAINER_BYTES_PER_AAC_ACCESS_UNIT / AAC_SAMPLES_PER_ACCESS_UNIT
                if (denominator <= 0L) 0L else estimatedContainerlessBytes / denominator
            } else {
                sizeBytes / bitrateBytesPerSecond
            }
        }
    }
}

fun supportedSampleRates(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    codec: ExportCodec,
    channelMode: ChannelMode,
): List<Int> {
    val key = SampleRatesKey(
        sourceMode = sourceMode,
        routeMode = routeMode,
        codec = codec,
        channelMode = channelMode,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    return sampleRatesCache.cached(key) {
        val detected = SAMPLE_RATE_CANDIDATES.filter { sampleRate ->
            isInputConfigSupported(context, sampleRate, sourceMode, routeMode, channelMode) &&
                isCodecSupported(codec, sampleRate, channelMode)
        }
        if (detected.isNotEmpty()) {
            detected
        } else if (routeMode == InputRouteMode.AUTO) {
            SAMPLE_RATE_CANDIDATES.filter { sampleRate -> isCodecSupported(codec, sampleRate, channelMode) }
        } else {
            emptyList()
        }
    }
}

fun getPreferredSampleRate(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    codec: ExportCodec,
    channelMode: ChannelMode,
): Int {
    val supported = supportedSampleRates(context, sourceMode, routeMode, codec, channelMode)
    if (supported.isNotEmpty()) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        if (nativeRate != null && nativeRate in supported) {
            return nativeRate
        }
        return supported.first()
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    return nativeRate?.takeIf { it > 0 } ?: SAMPLE_RATE_CANDIDATES.first()
}

fun resolveOperationalSampleRate(
    context: Context,
    requestedRate: Int,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    codec: ExportCodec,
    channelMode: ChannelMode,
): Int {
    val advertised = supportedSampleRates(context, sourceMode, routeMode, codec, channelMode)
    if (advertised.isEmpty()) {
        return requestedRate.takeIf { it > 0 } ?: getPreferredSampleRate(context, sourceMode, routeMode, codec, channelMode)
    }
    val ordered = orderSampleRatesByPreference(advertised, requestedRate)
    if (routeMode != InputRouteMode.AUTO) {
        return ordered.first()
    }
    return ordered.firstOrNull { isInputConfigSupported(context, it, sourceMode, routeMode, channelMode) } ?: ordered.first()
}

fun getRetentionMemoryCapBytes(): Long {
    return max(64L * 1024L * 1024L, Runtime.getRuntime().maxMemory() * 3L / 4L)
}

fun supportedAudioSourceModes(
    context: Context,
    routeMode: InputRouteMode,
    codec: ExportCodec,
): List<AudioSourceMode> {
    val key = SourceModesKey(
        routeMode = routeMode,
        codec = codec,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    val modes = sourceModesCache.cached(key) {
        AudioSourceMode.availableModes().filter { sourceMode ->
            ChannelMode.entries.any { channelMode ->
                supportedSampleRates(context, sourceMode, routeMode, codec, channelMode).isNotEmpty()
            }
        }
    }
    return if (modes.isNotEmpty()) modes else listOf(AudioSourceMode.MIC)
}

fun supportedChannelModes(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    codec: ExportCodec,
): List<ChannelMode> {
    val key = ChannelModesKey(
        sourceMode = sourceMode,
        routeMode = routeMode,
        codec = codec,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    val modes = channelModesCache.cached(key) {
        ChannelMode.entries.filter { channelMode ->
            supportedSampleRates(context, sourceMode, routeMode, codec, channelMode).isNotEmpty()
        }
    }
    return if (modes.isNotEmpty()) modes else listOf(ChannelMode.MONO)
}

fun supportedInputRouteModes(context: Context): List<InputRouteMode> {
    return buildList {
        add(InputRouteMode.AUTO)
        if (hasBuiltInMicrophone(context)) {
            add(InputRouteMode.BUILTIN_MIC)
        }
    }
}

fun standardSampleRates(): List<Int> = SAMPLE_RATE_CANDIDATES.toList()

fun isRecorderCapabilityCacheWarm(): Boolean = capabilityCacheWarm

fun sampleRateLabel(sampleRate: Int): String {
    return if (sampleRate % 1000 == 0) {
        "${sampleRate / 1000} kHz"
    } else {
        String.format(Locale.US, "%.2f kHz", sampleRate / 1000f)
    }
}

fun hasBuiltInMicrophone(context: Context): Boolean = findBuiltInMicrophone(context) != null

fun findBuiltInMicrophone(context: Context): AudioDeviceInfo? {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
}

fun isInputConfigSupported(
    context: Context,
    sampleRate: Int,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    channelMode: ChannelMode,
): Boolean {
    val key = InputConfigKey(
        sampleRate = sampleRate,
        sourceMode = sourceMode,
        routeMode = routeMode,
        channelMode = channelMode,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    return inputConfigCache.cached(key) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMode.inputChannelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            return@cached false
        }

        val preferredDevice = if (routeMode == InputRouteMode.BUILTIN_MIC) findBuiltInMicrophone(context) else null
        if (routeMode == InputRouteMode.BUILTIN_MIC && preferredDevice == null) {
            return@cached false
        }

        try {
            val record = AudioRecord.Builder()
                .setAudioSource(sourceMode.sourceValue)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMode.inputChannelMask)
                        .setSampleRate(sampleRate)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuffer * 2, 16 * 1024))
                .build()
            if (preferredDevice != null) {
                record.preferredDevice = preferredDevice
            }
            val initialized = record.state == AudioRecord.STATE_INITIALIZED
            record.release()
            initialized
        } catch (_: Throwable) {
            false
        }
    }
}

fun isCodecSupported(
    codec: ExportCodec,
    sampleRate: Int,
    channelMode: ChannelMode,
): Boolean {
    return codecSupportCache.cached(CodecSupportKey(codec, sampleRate, channelMode)) {
        when (codec) {
            ExportCodec.WAV -> true
            else -> {
                try {
                    val format = buildEncoderFormat(codec, sampleRate, channelMode.channelCount, defaultCodecBitrateKbps(codec, sampleRate, channelMode.channelCount))
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }
}

fun supportedCodecs(): List<ExportCodec> {
    cachedSupportedCodecs?.let { return it }
    val codecs = ExportCodec.entries.filter { codec ->
        codec == ExportCodec.WAV ||
            SAMPLE_RATE_CANDIDATES.any { sampleRate ->
                ChannelMode.entries.any { channelMode -> isCodecSupported(codec, sampleRate, channelMode) }
            }
    }
    cachedSupportedCodecs = codecs
    return codecs
}

fun warmRecorderCapabilityCache(context: Context) {
    if (capabilityCacheWarm) return
    synchronized(capabilityWarmLock) {
        if (capabilityCacheWarm) return
        val appContext = context.applicationContext
        val routes = supportedInputRouteModes(appContext)
        val codecs = supportedCodecs()
        codecs.forEach { codec ->
            routes.forEach { route ->
                AudioSourceMode.availableModes().forEach { source ->
                    ChannelMode.entries.forEach { channelMode ->
                        supportedSampleRates(appContext, source, route, codec, channelMode)
                    }
                }
            }
        }
        capabilityCacheWarm = true
    }
}

private fun bytesPerSecond(
    sampleRate: Int,
    channelCount: Int,
): Long {
    if (sampleRate <= 0 || channelCount <= 0) return 0L
    return sampleRate.toLong() * channelCount.toLong() * BYTES_PER_PCM_SAMPLE
}

fun codecBitrateBitsPerSecond(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int? = null,
): Long {
    return when {
        codec.isAacFamily -> aacBitrateForSampleRate(sampleRate, channelCount, bitrateKbps).toLong()
        else -> ((bitrateKbps ?: defaultCodecBitrateKbps(codec, sampleRate, channelCount) ?: 0) * 1000L)
    }
}

fun buildEncoderFormat(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int? = null,
): MediaFormat {
    require(codec != ExportCodec.WAV) { "WAV uses raw PCM writer" }
    val mimeType = requireNotNull(codec.encoderMimeType)
    return MediaFormat.createAudioFormat(mimeType, sampleRate, channelCount).apply {
        codec.aacProfile?.let { setInteger(MediaFormat.KEY_AAC_PROFILE, it) }
        val bitrateBitsPerSecond = codecBitrateBitsPerSecond(codec, sampleRate, channelCount, bitrateKbps)
        if (bitrateBitsPerSecond > 0) {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBitsPerSecond.toInt())
        }
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    }
}

fun orderSampleRatesByPreference(
    sampleRates: List<Int>,
    requestedRate: Int,
): List<Int> {
    if (sampleRates.isEmpty()) return emptyList()
    if (requestedRate <= 0) return sampleRates.sortedDescending()
    val distinct = sampleRates.distinct()
    val exact = distinct.filter { it == requestedRate }
    val higher = distinct.filter { it > requestedRate }.sortedBy { it - requestedRate }
    val lower = distinct.filter { it < requestedRate }.sortedByDescending { it }
    return exact + higher + lower
}
