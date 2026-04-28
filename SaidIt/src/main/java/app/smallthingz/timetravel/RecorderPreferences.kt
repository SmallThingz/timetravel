@file:JvmName("RecorderPreferences")

package app.smallthingz.timetravel

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
import android.util.Range
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val BYTES_PER_PCM_SAMPLE = 2L
private const val WAV_HEADER_BYTES = 44L
private const val MP4_CONTAINER_BASE_OVERHEAD_BYTES = 1536L
private const val MP4_CONTAINER_BYTES_PER_AAC_ACCESS_UNIT = 8L
private const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024L
private const val WAV_MAX_EXPORT_BYTES = 0xFFFF_FFFFL - WAV_HEADER_BYTES
private const val MUXED_MAX_EXPORT_BYTES = (4L * 1024L * 1024L * 1024L) - (8L * 1024L * 1024L)
private const val DEFAULT_HISTORY_CHUNK_SECONDS = 10
private const val MIN_HISTORY_CHUNK_SECONDS = 2
private const val MAX_HISTORY_CHUNK_SECONDS = 300
private const val DEFAULT_AUTO_MERGE_DIVISOR = 60
private const val MIN_AUTO_MERGE_DIVISOR = 2
private const val MAX_AUTO_MERGE_DIVISOR = 600
private const val DEFAULT_AUTO_MERGE_CUSTOM_SECONDS = 60
private const val MIN_AUTO_MERGE_CUSTOM_SECONDS = 10
private const val MAX_AUTO_MERGE_CUSTOM_SECONDS = 3600
private val STANDARD_SAMPLE_RATES =
    intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000)
private val codecSupportCache = ConcurrentHashMap<CodecSupportKey, Boolean>()
private val inputConfigCache = ConcurrentHashMap<InputConfigKey, Boolean>()
private val sampleRatesCache = ConcurrentHashMap<SampleRatesKey, List<Int>>()
private val sourceModesCache = ConcurrentHashMap<SourceModesKey, List<AudioSourceMode>>()
private val channelModesCache = ConcurrentHashMap<ChannelModesKey, List<ChannelMode>>()
private val codecCapabilityCache = ConcurrentHashMap<ExportCodec, CodecCapability>()
private val capabilityWarmLock = Any()
private val capabilityWarmExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "timetravel-capability-warm").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}
private val capabilityWarmScheduled = AtomicBoolean(false)

@Volatile
private var cachedSupportedFormats: List<ExportFormat>? = null
@Volatile
private var cachedSupportedCodecsByFormat: Map<ExportFormat, List<ExportCodec>>? = null

@Volatile
private var capabilityCacheWarm = false

private data class CodecCapability(
    val bitrateRangeKbps: IntRange?,
    val advertisedSampleRates: List<Int>,
)

private data class CodecSupportKey(
    val format: ExportFormat,
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
    val format: ExportFormat,
    val codec: ExportCodec,
    val channelMode: ChannelMode,
    val hasBuiltInMic: Boolean,
)

private data class SourceModesKey(
    val routeMode: InputRouteMode,
    val format: ExportFormat,
    val codec: ExportCodec,
    val hasBuiltInMic: Boolean,
)

private data class ChannelModesKey(
    val sourceMode: AudioSourceMode,
    val routeMode: InputRouteMode,
    val format: ExportFormat,
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

enum class ExportFormat(
    val prefValue: String,
    @StringRes val labelRes: Int,
    val extension: String,
    val outputMimeType: String,
    val muxerOutputFormat: Int? = null,
) {
    WAV(TimeTravelConfig.OUTPUT_FORMAT_WAV, R.string.format_wav, "wav", "audio/wav"),
    M4A(TimeTravelConfig.OUTPUT_FORMAT_M4A, R.string.format_m4a, "m4a", "audio/mp4", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
    THREE_GPP(TimeTravelConfig.OUTPUT_FORMAT_3GP, R.string.format_3gp, "3gp", "audio/3gpp", MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP),
    OGG(TimeTravelConfig.OUTPUT_FORMAT_OGG, R.string.format_ogg, "ogg", "audio/ogg", MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG),
    WEBM(TimeTravelConfig.OUTPUT_FORMAT_WEBM, R.string.format_webm, "webm", "audio/webm", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM),
    AAC_ADTS(TimeTravelConfig.OUTPUT_FORMAT_AAC_ADTS, R.string.format_aac_adts, "aac", "audio/aac"),
    AMR_NB_FILE(TimeTravelConfig.OUTPUT_FORMAT_AMR_NB, R.string.format_amr_nb_file, "amr", "audio/amr"),
    AMR_WB_FILE(TimeTravelConfig.OUTPUT_FORMAT_AMR_WB, R.string.format_amr_wb_file, "awb", "audio/amr-wb"),
    MPEG_2_TS(TimeTravelConfig.OUTPUT_FORMAT_MPEG_2_TS, R.string.format_mpeg_2_ts, "ts", "video/mp2t"),
    ;

    val isPcmContainer: Boolean
        get() = this == WAV

    val usesMuxer: Boolean
        get() = muxerOutputFormat != null

    val isRawAacAdts: Boolean
        get() = this == AAC_ADTS

    val isRawAmr: Boolean
        get() = this == AMR_NB_FILE || this == AMR_WB_FILE

    val isTransportStream: Boolean
        get() = this == MPEG_2_TS

    companion object {
        fun fromPrefValue(value: String?): ExportFormat {
            return entries.firstOrNull { it.prefValue == value } ?: WAV
        }
    }
}

enum class ExportCodec(
    val prefValue: String,
    @StringRes val labelRes: Int,
    val encoderMimeType: String? = null,
    val aacProfile: Int? = null,
) {
    PCM_16(TimeTravelConfig.OUTPUT_CODEC_PCM_16, R.string.codec_pcm_16),
    AAC_LC(TimeTravelConfig.OUTPUT_CODEC_AAC_LC, R.string.codec_aac_lc, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectLC),
    AAC_ELD(TimeTravelConfig.OUTPUT_CODEC_AAC_ELD, R.string.codec_aac_eld, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectELD),
    HE_AAC(TimeTravelConfig.OUTPUT_CODEC_HE_AAC, R.string.codec_he_aac, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectHE),
    HE_AAC_V2(TimeTravelConfig.OUTPUT_CODEC_HE_AAC_V2, R.string.codec_he_aac_v2, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS),
    XHE_AAC(TimeTravelConfig.OUTPUT_CODEC_XHE_AAC, R.string.codec_xhe_aac, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectXHE),
    AMR_WB(TimeTravelConfig.OUTPUT_CODEC_AMR_WB, R.string.codec_amr_wb, MediaFormat.MIMETYPE_AUDIO_AMR_WB),
    AMR_NB(TimeTravelConfig.OUTPUT_CODEC_AMR_NB, R.string.codec_amr_nb, MediaFormat.MIMETYPE_AUDIO_AMR_NB),
    OPUS(TimeTravelConfig.OUTPUT_CODEC_OPUS, R.string.codec_opus, MediaFormat.MIMETYPE_AUDIO_OPUS),
    VORBIS(TimeTravelConfig.OUTPUT_CODEC_VORBIS, R.string.codec_vorbis, MediaFormat.MIMETYPE_AUDIO_VORBIS),
    FLAC(TimeTravelConfig.OUTPUT_CODEC_FLAC, R.string.codec_flac, MediaFormat.MIMETYPE_AUDIO_FLAC),
    ;

    val isAacFamily: Boolean
        get() = encoderMimeType == MediaFormat.MIMETYPE_AUDIO_AAC

    val isPcm: Boolean
        get() = this == PCM_16

    val supportedFormats: Set<ExportFormat>
        get() = when (this) {
            PCM_16 -> setOf(ExportFormat.WAV)
            AAC_LC -> setOf(ExportFormat.M4A, ExportFormat.THREE_GPP, ExportFormat.AAC_ADTS, ExportFormat.MPEG_2_TS)
            AAC_ELD -> setOf(ExportFormat.M4A, ExportFormat.THREE_GPP)
            HE_AAC, HE_AAC_V2, XHE_AAC -> setOf(ExportFormat.M4A, ExportFormat.THREE_GPP)
            AMR_WB -> setOf(ExportFormat.THREE_GPP, ExportFormat.AMR_WB_FILE)
            AMR_NB -> setOf(ExportFormat.THREE_GPP, ExportFormat.AMR_NB_FILE)
            OPUS -> setOf(ExportFormat.OGG, ExportFormat.WEBM)
            VORBIS -> setOf(ExportFormat.WEBM)
            FLAC -> emptySet()
        }

    companion object {
        fun fromPrefValue(value: String?): ExportCodec {
            return when (value) {
                TimeTravelConfig.OUTPUT_CODEC_AAC -> AAC_LC
                TimeTravelConfig.OUTPUT_CODEC_WAV -> PCM_16
                else -> entries.firstOrNull { it.prefValue == value } ?: PCM_16
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

enum class AutoMergeMode(
    val prefValue: String,
    @StringRes val labelRes: Int,
) {
    OFF(TimeTravelConfig.AUTO_MERGE_MODE_OFF, R.string.auto_merge_mode_off),
    RATIO(TimeTravelConfig.AUTO_MERGE_MODE_RATIO, R.string.auto_merge_mode_ratio),
    CUSTOM(TimeTravelConfig.AUTO_MERGE_MODE_CUSTOM, R.string.auto_merge_mode_custom),
    ;

    companion object {
        fun fromPrefValue(value: String?): AutoMergeMode {
            return entries.firstOrNull { it.prefValue == value } ?: OFF
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

fun getConfiguredHistoryChunkSeconds(context: Context): Int {
    return getRecorderPreferences(context)
        .getInt(TimeTravelConfig.HISTORY_CHUNK_SECONDS_KEY, DEFAULT_HISTORY_CHUNK_SECONDS)
        .coerceIn(MIN_HISTORY_CHUNK_SECONDS, MAX_HISTORY_CHUNK_SECONDS)
}

fun getConfiguredAutoMergeMode(context: Context): AutoMergeMode {
    return AutoMergeMode.fromPrefValue(
        getRecorderPreferences(context).getString(TimeTravelConfig.AUTO_MERGE_MODE_KEY, TimeTravelConfig.AUTO_MERGE_MODE_OFF),
    )
}

fun getConfiguredAutoMergeDivisor(context: Context): Int {
    return getRecorderPreferences(context)
        .getInt(TimeTravelConfig.AUTO_MERGE_DIVISOR_KEY, DEFAULT_AUTO_MERGE_DIVISOR)
        .coerceIn(MIN_AUTO_MERGE_DIVISOR, MAX_AUTO_MERGE_DIVISOR)
}

fun getConfiguredAutoMergeCustomSeconds(context: Context): Int {
    return getRecorderPreferences(context)
        .getInt(TimeTravelConfig.AUTO_MERGE_CUSTOM_SECONDS_KEY, DEFAULT_AUTO_MERGE_CUSTOM_SECONDS)
        .coerceIn(MIN_AUTO_MERGE_CUSTOM_SECONDS, MAX_AUTO_MERGE_CUSTOM_SECONDS)
}

fun configuredAutoMergeTargetDurationMillis(
    context: Context,
    retentionBytes: Long,
    sampleRate: Int,
    channelCount: Int,
): Long? {
    val retentionMillis = retentionSecondsForBytes(retentionBytes, sampleRate, channelCount) * 1000L
    if (retentionMillis <= 0L) {
        return null
    }
    return when (getConfiguredAutoMergeMode(context)) {
        AutoMergeMode.OFF -> null
        AutoMergeMode.RATIO -> {
            val divisor = getConfiguredAutoMergeDivisor(context).coerceAtLeast(1)
            (retentionMillis / divisor).coerceAtLeast(MIN_AUTO_MERGE_CUSTOM_SECONDS * 1000L)
        }
        AutoMergeMode.CUSTOM -> {
            getConfiguredAutoMergeCustomSeconds(context).toLong().coerceAtLeast(MIN_AUTO_MERGE_CUSTOM_SECONDS.toLong()) * 1000L
        }
    }
}

fun getConfiguredOutputFormat(context: Context): ExportFormat {
    val prefs = getRecorderPreferences(context)
    val storedFormat = prefs.getString(TimeTravelConfig.OUTPUT_FORMAT_KEY, null)
    if (storedFormat != null) {
        return ExportFormat.fromPrefValue(storedFormat)
    }
    val legacyCodec = ExportCodec.fromPrefValue(prefs.getString(TimeTravelConfig.OUTPUT_CODEC_KEY, TimeTravelConfig.OUTPUT_CODEC_WAV))
    return if (legacyCodec != ExportCodec.PCM_16 || prefs.contains(TimeTravelConfig.OUTPUT_CODEC_KEY)) {
        preferredOutputFormatForCodec(legacyCodec)
    } else {
        preferredDefaultOutputFormat()
    }
}

fun getConfiguredOutputCodec(context: Context): ExportCodec {
    val prefs = getRecorderPreferences(context)
    val storedCodec = ExportCodec.fromPrefValue(prefs.getString(TimeTravelConfig.OUTPUT_CODEC_KEY, TimeTravelConfig.OUTPUT_CODEC_WAV))
    val preferredFormat = getConfiguredOutputFormat(context)
    val preferred = getPreferredOutputCodec(preferredFormat)
    return if (isCodecCompatibleWithFormat(preferredFormat, storedCodec)) storedCodec else preferred
}

fun preferredOutputFormatForCodec(codec: ExportCodec): ExportFormat {
    return codec.supportedFormats.firstOrNull() ?: preferredDefaultOutputFormat()
}

fun preferredDefaultOutputFormat(): ExportFormat {
    val formats = supportedFormats()
    return when {
        ExportFormat.OGG in formats && ExportCodec.OPUS in supportedCodecs(ExportFormat.OGG) -> ExportFormat.OGG
        ExportFormat.WEBM in formats && ExportCodec.OPUS in supportedCodecs(ExportFormat.WEBM) -> ExportFormat.WEBM
        ExportFormat.M4A in formats && ExportCodec.AAC_LC in supportedCodecs(ExportFormat.M4A) -> ExportFormat.M4A
        ExportFormat.WAV in formats -> ExportFormat.WAV
        else -> formats.firstOrNull() ?: ExportFormat.WAV
    }
}

fun isCodecCompatibleWithFormat(
    format: ExportFormat,
    codec: ExportCodec,
): Boolean = format in codec.supportedFormats

fun codecBitrateRangeKbps(codec: ExportCodec): IntRange? = codecCapability(codec).bitrateRangeKbps

fun codecSupportsBitrateSelection(codec: ExportCodec): Boolean = codecBitrateRangeKbps(codec) != null

fun codecBitrateStepKbps(codec: ExportCodec): Int = 1

fun defaultCodecBitrateKbps(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Int? {
    return when {
        codec.isAacFamily -> aacBitrateForSampleRate(sampleRate, channelCount) / 1000
        codec == ExportCodec.AMR_WB -> 24
        codec == ExportCodec.AMR_NB -> 12
        codec == ExportCodec.OPUS -> if (channelCount >= 2) 160 else 96
        codec == ExportCodec.VORBIS -> if (channelCount >= 2) 192 else 112
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
    format: ExportFormat = getConfiguredOutputFormat(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
    channelMode: ChannelMode = getConfiguredChannelMode(context),
): Int {
    val prefs = getRecorderPreferences(context)
    val preferred = getPreferredSampleRate(context, sourceMode, routeMode, format, codec, channelMode)
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
    return formatDurationInput(seconds.toLong())
}

fun formatDurationInput(seconds: Long): String {
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

fun getPreferredOutputCodec(format: ExportFormat = preferredDefaultOutputFormat()): ExportCodec {
    val supported = supportedCodecs(format)
    return when {
        ExportCodec.OPUS in supported -> ExportCodec.OPUS
        ExportCodec.AAC_LC in supported -> ExportCodec.AAC_LC
        ExportCodec.VORBIS in supported -> ExportCodec.VORBIS
        ExportCodec.PCM_16 in supported -> ExportCodec.PCM_16
        else -> supported.firstOrNull() ?: ExportCodec.PCM_16
    }
}

fun aacBitrateForSampleRate(
    sampleRate: Int,
    channelCount: Int = 1,
    bitrateKbps: Int? = null,
): Int {
    bitrateKbps?.takeIf { it > 0 }?.let {
        val range = codecBitrateRangeKbps(ExportCodec.AAC_LC) ?: 32..320
        return it.coerceIn(range) * 1000
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
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    durationSeconds: Long,
    bitrateKbps: Int? = null,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || durationSeconds <= 0L) {
        return 0L
    }

    return when {
        format.isPcmContainer -> {
            WAV_HEADER_BYTES + durationSeconds * bytesPerSecond(sampleRate, channelCount)
        }

        else -> {
            val audioBytes = durationSeconds * codecBitrateBitsPerSecond(codec, sampleRate, channelCount, bitrateKbps) / 8L
            if (format == ExportFormat.M4A && codec.isAacFamily) {
                val accessUnits = ((durationSeconds * sampleRate.toLong()) + AAC_SAMPLES_PER_ACCESS_UNIT - 1L) / AAC_SAMPLES_PER_ACCESS_UNIT
                audioBytes + MP4_CONTAINER_BASE_OVERHEAD_BYTES + accessUnits * MP4_CONTAINER_BYTES_PER_AAC_ACCESS_UNIT
            } else {
                audioBytes
            }
        }
    }
}

fun estimateExportDurationSeconds(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    sizeBytes: Long,
    bitrateKbps: Int? = null,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || sizeBytes <= 0L) {
        return 0L
    }

    return when {
        format.isPcmContainer -> {
            ((sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)) /
                bytesPerSecond(sampleRate, channelCount)
        }

        else -> {
            val bitrateBytesPerSecond = codecBitrateBitsPerSecond(codec, sampleRate, channelCount, bitrateKbps) / 8L
            if (bitrateBytesPerSecond <= 0L) {
                0L
            } else if (format == ExportFormat.M4A && codec.isAacFamily) {
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

fun exportFileSizeLimitBytes(format: ExportFormat): Long {
    return when (format) {
        ExportFormat.WAV -> WAV_MAX_EXPORT_BYTES
        ExportFormat.AAC_ADTS,
        ExportFormat.AMR_NB_FILE,
        ExportFormat.AMR_WB_FILE,
        ExportFormat.MPEG_2_TS,
        -> 4L * 1024L * 1024L * 1024L
        else -> MUXED_MAX_EXPORT_BYTES
    }
}

fun exportDurationLimitSeconds(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int? = null,
): Long {
    return estimateExportDurationSeconds(
        format = format,
        codec = codec,
        sampleRate = sampleRate,
        channelCount = channelCount,
        sizeBytes = exportFileSizeLimitBytes(format),
        bitrateKbps = bitrateKbps,
    )
}

fun supportedSampleRates(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    format: ExportFormat,
    codec: ExportCodec,
    channelMode: ChannelMode,
): List<Int> {
    val key = SampleRatesKey(
        sourceMode = sourceMode,
        routeMode = routeMode,
        format = format,
        codec = codec,
        channelMode = channelMode,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    return sampleRatesCache.cached(key) {
        val advertisedRates = codecAdvertisedSampleRates(codec)
        val detected = advertisedRates.filter { sampleRate ->
            isInputConfigSupported(context, sampleRate, sourceMode, routeMode, channelMode) &&
                isCodecSupported(format, codec, sampleRate, channelMode)
        }
        if (detected.isNotEmpty()) {
            detected
        } else if (routeMode == InputRouteMode.AUTO) {
            advertisedRates.filter { sampleRate -> isCodecSupported(format, codec, sampleRate, channelMode) }
        } else {
            emptyList()
        }
    }
}

fun getPreferredSampleRate(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    format: ExportFormat,
    codec: ExportCodec,
    channelMode: ChannelMode,
): Int {
    val supported = supportedSampleRates(context, sourceMode, routeMode, format, codec, channelMode)
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
    return nativeRate?.takeIf { it > 0 } ?: STANDARD_SAMPLE_RATES.first()
}

fun resolveOperationalSampleRate(
    context: Context,
    requestedRate: Int,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    format: ExportFormat,
    codec: ExportCodec,
    channelMode: ChannelMode,
): Int {
    val advertised = supportedSampleRates(context, sourceMode, routeMode, format, codec, channelMode)
    if (advertised.isEmpty()) {
        return requestedRate.takeIf { it > 0 } ?: getPreferredSampleRate(context, sourceMode, routeMode, format, codec, channelMode)
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
    format: ExportFormat,
    codec: ExportCodec,
): List<AudioSourceMode> {
    val key = SourceModesKey(
        routeMode = routeMode,
        format = format,
        codec = codec,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    val modes = sourceModesCache.cached(key) {
        AudioSourceMode.availableModes().filter { sourceMode ->
            ChannelMode.entries.any { channelMode ->
                supportedSampleRates(context, sourceMode, routeMode, format, codec, channelMode).isNotEmpty()
            }
        }
    }
    return if (modes.isNotEmpty()) modes else listOf(AudioSourceMode.MIC)
}

fun supportedChannelModes(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    format: ExportFormat,
    codec: ExportCodec,
): List<ChannelMode> {
    val key = ChannelModesKey(
        sourceMode = sourceMode,
        routeMode = routeMode,
        format = format,
        codec = codec,
        hasBuiltInMic = hasBuiltInMicrophone(context),
    )
    val modes = channelModesCache.cached(key) {
        ChannelMode.entries.filter { channelMode ->
            supportedSampleRates(context, sourceMode, routeMode, format, codec, channelMode).isNotEmpty()
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

fun standardSampleRates(): List<Int> = STANDARD_SAMPLE_RATES.toList()

fun historyChunkSecondsRange(): IntRange = MIN_HISTORY_CHUNK_SECONDS..MAX_HISTORY_CHUNK_SECONDS

fun autoMergeDivisorRange(): IntRange = MIN_AUTO_MERGE_DIVISOR..MAX_AUTO_MERGE_DIVISOR

fun autoMergeCustomSecondsRange(): IntRange = MIN_AUTO_MERGE_CUSTOM_SECONDS..MAX_AUTO_MERGE_CUSTOM_SECONDS

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
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelMode: ChannelMode,
): Boolean {
    return codecSupportCache.cached(CodecSupportKey(format, codec, sampleRate, channelMode)) {
        if (!isCodecCompatibleWithFormat(format, codec)) {
            return@cached false
        }
        when {
            codec.isPcm -> format.isPcmContainer
            else -> {
                try {
                    val encoderFormat = buildEncoderFormat(codec, sampleRate, channelMode.channelCount, defaultCodecBitrateKbps(codec, sampleRate, channelMode.channelCount))
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(encoderFormat) != null
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }
}

fun supportedFormats(): List<ExportFormat> {
    cachedSupportedFormats?.let { return it }
    val formats = ExportFormat.entries.filter { supportedCodecs(it).isNotEmpty() }
    cachedSupportedFormats = formats
    return formats
}

fun supportedCodecs(format: ExportFormat): List<ExportCodec> {
    cachedSupportedCodecsByFormat?.get(format)?.let { return it }
    val all = cachedSupportedCodecsByFormat?.toMutableMap() ?: mutableMapOf()
    val codecs = ExportCodec.entries.filter { codec ->
        isCodecCompatibleWithFormat(format, codec) &&
            codecAdvertisedSampleRates(codec).any { sampleRate ->
                ChannelMode.entries.any { channelMode -> isCodecSupported(format, codec, sampleRate, channelMode) }
            }
    }
    all[format] = codecs
    cachedSupportedCodecsByFormat = all
    return codecs
}

fun warmRecorderCapabilityCache(context: Context) {
    if (capabilityCacheWarm) return
    synchronized(capabilityWarmLock) {
        if (capabilityCacheWarm) return
        val appContext = context.applicationContext
        val routes = supportedInputRouteModes(appContext)
        val formats = supportedFormats()
        formats.forEach { format ->
            supportedCodecs(format).forEach { codec ->
                routes.forEach { route ->
                    AudioSourceMode.availableModes().forEach { source ->
                        ChannelMode.entries.forEach { channelMode ->
                            supportedSampleRates(appContext, source, route, format, codec, channelMode)
                        }
                    }
                }
            }
        }
        capabilityCacheWarm = true
    }
}

fun scheduleRecorderCapabilityCacheWarm(context: Context) {
    if (capabilityCacheWarm || !capabilityWarmScheduled.compareAndSet(false, true)) {
        return
    }
    val appContext = context.applicationContext
    capabilityWarmExecutor.execute {
        try {
            warmRecorderCapabilityCache(appContext)
        } finally {
            capabilityWarmScheduled.set(false)
        }
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
        codec.isPcm -> 0L
        codec.isAacFamily -> aacBitrateForSampleRate(sampleRate, channelCount, bitrateKbps).toLong()
        codec == ExportCodec.FLAC -> bytesPerSecond(sampleRate, channelCount) * 8L
        else -> ((bitrateKbps ?: defaultCodecBitrateKbps(codec, sampleRate, channelCount) ?: 0) * 1000L)
    }
}

fun buildEncoderFormat(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
    bitrateKbps: Int? = null,
): MediaFormat {
    require(!codec.isPcm) { "PCM uses raw WAV writer" }
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

private fun codecCapability(codec: ExportCodec): CodecCapability {
    return codecCapabilityCache.cached(codec) {
        if (codec.isPcm) {
            return@cached CodecCapability(null, standardSampleRates())
        }
        val mimeType = codec.encoderMimeType ?: return@cached CodecCapability(null, standardSampleRates())
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var bitrateRange: IntRange? = null
        val sampleRates = linkedSetOf<Int>()
        codecList.codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .forEach { info ->
                val capabilities =
                    runCatching { info.getCapabilitiesForType(mimeType) }.getOrNull()
                        ?: return@forEach
                val audioCapabilities = capabilities.audioCapabilities ?: return@forEach
                bitrateRange = mergeBitrateRanges(bitrateRange, audioCapabilities.getBitrateRange())
                val discreteRates = audioCapabilities.supportedSampleRates
                if (discreteRates != null) {
                    discreteRates.filterTo(sampleRates) { it in STANDARD_SAMPLE_RATES }
                } else {
                    STANDARD_SAMPLE_RATES.forEach { rate ->
                        if (audioCapabilities.isSampleRateSupported(rate)) {
                            sampleRates += rate
                        }
                    }
                }
            }
        CodecCapability(
            bitrateRangeKbps = bitrateRange,
            advertisedSampleRates = sampleRates.toList().sortedDescending().ifEmpty { standardSampleRates() },
        )
    }
}

private fun mergeBitrateRanges(
    existing: IntRange?,
    range: Range<Int>?,
): IntRange? {
    val normalized = range?.let { (it.lower.coerceAtLeast(1) / 1000)..max(1, it.upper / 1000) } ?: return existing
    return if (existing == null) normalized else minOf(existing.first, normalized.first)..maxOf(existing.last, normalized.last)
}

private fun codecAdvertisedSampleRates(codec: ExportCodec): List<Int> = codecCapability(codec).advertisedSampleRates

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
