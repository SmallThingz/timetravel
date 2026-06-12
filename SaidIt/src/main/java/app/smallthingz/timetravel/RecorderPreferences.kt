@file:JvmName("RecorderPreferences")

package app.smallthingz.timetravel

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Range
import androidx.annotation.StringRes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val TAG = "RecorderPreferences"
private const val WAV_HEADER_BYTES = 44L
private const val MP4_CONTAINER_BASE_OVERHEAD_BYTES = 1536L
private const val MP4_CONTAINER_BYTES_PER_AAC_ACCESS_UNIT = 8L
private const val AAC_SAMPLES_PER_ACCESS_UNIT = 1024L
private const val WAV_MAX_EXPORT_BYTES = 0xFFFF_FFFFL - WAV_HEADER_BYTES
private const val MUXED_MAX_EXPORT_BYTES = (4L * 1024L * 1024L * 1024L) - (8L * 1024L * 1024L)
private const val MAX_PERSISTENT_PCM_BUFFER_BYTES = Int.MAX_VALUE.toLong()
private const val PREFERRED_DEFAULT_SAMPLE_RATE = 44_100
private const val CAPABILITY_WARM_THREAD_NAME = "timetravel-capability-warm"
private const val DURATION_SEPARATOR = ":"
private const val SAMPLE_RATE_LABEL_SUFFIX_KHZ = " kHz"

private val STANDARD_SAMPLE_RATES =
    intArrayOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
private val AAC_SAMPLE_RATES =
    setOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)
private val codecSupportCache = ConcurrentHashMap<CodecSupportKey, Boolean>()
private val inputConfigCache = ConcurrentHashMap<InputConfigKey, Boolean>()
private val sampleRatesCache = ConcurrentHashMap<SampleRatesKey, List<Int>>()
private val sourceModesCache = ConcurrentHashMap<SourceModesKey, List<AudioSourceMode>>()
private val channelModesCache = ConcurrentHashMap<ChannelModesKey, List<ChannelMode>>()
private val codecCapabilityCache = ConcurrentHashMap<ExportCodec, CodecCapability>()
private val capabilityWarmLock = Any()
private val capabilityWarmExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, CAPABILITY_WARM_THREAD_NAME).apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}
private val capabilityWarmScheduled = AtomicBoolean(false)

@Volatile
private var cachedSupportedFormats: List<ExportFormat>? = null
private val cachedSupportedCodecsByFormat = ConcurrentHashMap<ExportFormat, List<ExportCodec>>()

@Volatile
private var capabilityCacheWarm = false

private data class CodecCapability(
    val bitrateRangeKbps: IntRange?,
    val advertisedSampleRates: List<Int>,
    val maxChannelCount: Int,
    val supportedAacProfiles: Set<Int>,
    val hasEncoder: Boolean,
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

private const val LEGACY_RETENTION_TIME = "time"
private const val LEGACY_EXPORT_MODE_RANGE = "range"
private const val LEGACY_EXPORT_UNIT_SIZE = "size"

enum class RetentionMode {
    SIZE,
    TIME,
    ;

    companion object {
        fun fromStorage(value: Int): RetentionMode = entries.getOrElse(value) { SIZE }
    }
}

enum class ExportFormat(
    @param:StringRes @field:StringRes val labelRes: Int,
    val muxerOutputFormat: Int? = null,
    val minApi: Int = Build.VERSION_CODES.JELLY_BEAN_MR2,
) {
    WAV(R.string.format_wav),
    M4A(R.string.format_m4a, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
    THREE_GPP(R.string.format_3gp, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP, Build.VERSION_CODES.O),
    OGG(R.string.format_ogg, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, Build.VERSION_CODES.Q),
    WEBM(R.string.format_webm, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, Build.VERSION_CODES.LOLLIPOP),
    AAC_ADTS(R.string.format_aac_adts),
    AMR_NB_FILE(R.string.codec_amr_nb),
    AMR_WB_FILE(R.string.codec_amr_wb),
    MPEG_2_TS(R.string.format_mpeg_2_ts),
    ;

    val prefValue: String get() = name.lowercase()

    val extension: String get() = when (this) {
        THREE_GPP -> EXTENSION_3GP
        AAC_ADTS -> EXTENSION_AAC
        AMR_NB_FILE -> EXTENSION_AMR
        AMR_WB_FILE -> EXTENSION_AWB
        MPEG_2_TS -> EXTENSION_TS
        else -> name.lowercase()
    }

    val outputMimeType: String get() = when (this) {
        M4A -> MIME_AUDIO_MP4
        THREE_GPP -> MIME_AUDIO_3GPP
        AAC_ADTS -> MIME_AUDIO_AAC
        AMR_NB_FILE -> MIME_AUDIO_AMR
        AMR_WB_FILE -> MIME_AUDIO_AMR_WB
        MPEG_2_TS -> MIME_VIDEO_MP2T
        else -> "$MIME_AUDIO_FALLBACK_PREFIX${name.lowercase()}"
    }

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

    val isRuntimeSupported: Boolean
        get() = Build.VERSION.SDK_INT >= minApi

    companion object {
        const val MIME_AUDIO_MP4 = "audio/mp4"
        const val MIME_AUDIO_3GPP = "audio/3gpp"
        const val MIME_AUDIO_AAC = "audio/aac"
        const val MIME_AUDIO_AMR = "audio/amr"
        const val MIME_AUDIO_AMR_WB = "audio/amr-wb"
        const val MIME_VIDEO_MP2T = "video/mp2t"
        const val MIME_AUDIO_FALLBACK_PREFIX = "audio/"

        const val EXTENSION_3GP = "3gp"
        const val EXTENSION_AAC = "aac"
        const val EXTENSION_AMR = "amr"
        const val EXTENSION_AWB = "awb"
        const val EXTENSION_TS = "ts"

        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): ExportFormat {
            val v = value ?: return WAV
            return byPrefValue[v] ?: WAV
        }
    }
}

enum class ExportCodec(
    @param:StringRes @field:StringRes val labelRes: Int,
    val encoderMimeType: String? = null,
    val aacProfile: Int? = null,
) {
    PCM_16(R.string.codec_pcm_16),
    AAC_LC(R.string.codec_aac_lc, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectLC),
    AAC_ELD(R.string.codec_aac_eld, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectELD),
    HE_AAC(R.string.codec_he_aac, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectHE),
    HE_AAC_V2(R.string.codec_he_aac_v2, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS),
    XHE_AAC(R.string.codec_xhe_aac, MediaFormat.MIMETYPE_AUDIO_AAC, MediaCodecInfo.CodecProfileLevel.AACObjectXHE),
    AMR_WB(R.string.codec_amr_wb, MediaFormat.MIMETYPE_AUDIO_AMR_WB),
    AMR_NB(R.string.codec_amr_nb, MediaFormat.MIMETYPE_AUDIO_AMR_NB),
    OPUS(R.string.codec_opus, MediaFormat.MIMETYPE_AUDIO_OPUS),
    VORBIS(R.string.codec_vorbis, MediaFormat.MIMETYPE_AUDIO_VORBIS),
    FLAC(R.string.codec_flac, MediaFormat.MIMETYPE_AUDIO_FLAC),
    ;

    val prefValue: String get() = name.lowercase()

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
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): ExportCodec {
            val v = value ?: return PCM_16
            return when (v) {
                "aac" -> AAC_LC
                "wav" -> PCM_16
                else -> byPrefValue[v] ?: PCM_16
            }
        }
    }
}

private const val WAVE_FORMAT_PCM: Short = 1
private const val WAVE_FORMAT_IEEE_FLOAT: Short = 3

enum class PcmSampleFormat(
    @param:StringRes @field:StringRes val labelRes: Int,
    val bitsPerSample: Int,
    val bytesPerSample: Int,
    val audioEncoding: Int,
    val wavFormatTag: Short,
) {
    PCM_8(R.string.sample_format_pcm_8, 8, 1, AudioFormat.ENCODING_PCM_8BIT, WAVE_FORMAT_PCM),
    PCM_16(R.string.sample_format_pcm_16, 16, 2, AudioFormat.ENCODING_PCM_16BIT, WAVE_FORMAT_PCM),
    PCM_FLOAT(R.string.sample_format_float_32, 32, 4, AudioFormat.ENCODING_PCM_FLOAT, WAVE_FORMAT_IEEE_FLOAT),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): PcmSampleFormat {
            val v = value ?: return PCM_16
            return byPrefValue[v] ?: PCM_16
        }
    }
}

enum class AudioSourceMode(
    val sourceValue: Int,
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, R.string.audio_source_voice_recognition),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, R.string.audio_source_voice_communication),
    VOICE_PERFORMANCE(MediaRecorder.AudioSource.VOICE_PERFORMANCE, R.string.audio_source_voice_performance),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER, R.string.audio_source_camcorder),
    DEFAULT(MediaRecorder.AudioSource.DEFAULT, R.string.audio_source_default),
    MIC(MediaRecorder.AudioSource.MIC, R.string.audio_source_mic),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, R.string.audio_source_unprocessed),
    VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL, R.string.audio_source_voice_call),
    VOICE_UPLINK(MediaRecorder.AudioSource.VOICE_UPLINK, R.string.audio_source_voice_uplink),
    VOICE_DOWNLINK(MediaRecorder.AudioSource.VOICE_DOWNLINK, R.string.audio_source_voice_downlink),
    REMOTE_SUBMIX(MediaRecorder.AudioSource.REMOTE_SUBMIX, R.string.audio_source_remote_submix),
    ;

    companion object {
        private val preferredOrder = listOf(
            VOICE_RECOGNITION,
            VOICE_COMMUNICATION,
            VOICE_PERFORMANCE,
            CAMCORDER,
            DEFAULT,
            MIC,
            UNPROCESSED,
            VOICE_CALL,
            VOICE_UPLINK,
            VOICE_DOWNLINK,
            REMOTE_SUBMIX,
        )
        private val bySourceValue = entries.associateBy { it.sourceValue }

        fun defaultMode(): AudioSourceMode = preferredOrder.first()

        fun fromSourceValue(value: Int): AudioSourceMode = bySourceValue[value] ?: defaultMode()

        fun availableModes(): List<AudioSourceMode> = preferredOrder
    }
}

enum class InputRouteMode(@param:StringRes @field:StringRes val labelRes: Int) {
    AUTO(R.string.input_route_auto),
    BUILTIN_MIC(R.string.input_route_builtin_mic),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): InputRouteMode {
            val v = value ?: return AUTO
            return byPrefValue[v] ?: AUTO
        }
    }
}

enum class ChannelMode(
    @param:StringRes @field:StringRes val labelRes: Int,
    val channelCount: Int,
    val inputChannelMask: Int,
) {
    MONO(R.string.channel_mode_mono, 1, AudioFormat.CHANNEL_IN_MONO),
    STEREO(R.string.channel_mode_stereo, 2, AudioFormat.CHANNEL_IN_STEREO),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): ChannelMode {
            val v = value ?: return MONO
            return byPrefValue[v] ?: MONO
        }
    }
}

enum class AppThemeMode(
    @param:StringRes @field:StringRes val labelRes: Int,
) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    ;

    val prefValue: String get() = name.lowercase()

    companion object {
        private val byPrefValue = entries.associateBy { it.prefValue }

        fun fromPrefValue(value: String?): AppThemeMode {
            val v = value ?: return SYSTEM
            return byPrefValue[v] ?: SYSTEM
        }
    }
}

enum class CustomExportMode {
    RANGE,
    PAST,
    ;

    companion object {
        fun fromStorage(value: Int): CustomExportMode = entries.getOrElse(value) { PAST }
    }
}

enum class CustomExportUnit {
    TIME,
    SIZE,
    ;

    companion object {
        fun fromStorage(value: Int): CustomExportUnit = entries.getOrElse(value) { TIME }
    }
}

fun getRecorderPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
}

fun getConfiguredRetentionMode(context: Context): RetentionMode {
    val prefs = getRecorderPreferences(context)
    val stored = prefs.getInt(PrefKey.RETENTION_MODE, -1)
    if (stored >= 0 && stored < RetentionMode.entries.size) return RetentionMode.entries[stored]
    val legacy = prefs.getString(PrefKey.RETENTION_MODE, null)
    return when (legacy) {
        LEGACY_RETENTION_TIME -> RetentionMode.TIME
        else -> RetentionMode.SIZE
    }
}

fun getConfiguredCustomExportMode(context: Context): CustomExportMode {
    val prefs = getRecorderPreferences(context)
    val stored = prefs.getInt(PrefKey.CUSTOM_EXPORT_MODE, -1)
    if (stored >= 0 && stored < CustomExportMode.entries.size) return CustomExportMode.entries[stored]
    val legacy = prefs.getString(PrefKey.CUSTOM_EXPORT_MODE, null)
    return when (legacy) {
        LEGACY_EXPORT_MODE_RANGE -> CustomExportMode.RANGE
        else -> TimeTravelConfig.DEFAULT_CUSTOM_EXPORT_MODE
    }
}

fun setConfiguredCustomExportMode(context: Context, mode: CustomExportMode) {
    getRecorderPreferences(context).edit().putInt(PrefKey.CUSTOM_EXPORT_MODE, mode.ordinal).apply()
}

fun getConfiguredCustomExportUnit(context: Context): CustomExportUnit {
    val prefs = getRecorderPreferences(context)
    val stored = prefs.getInt(PrefKey.CUSTOM_EXPORT_UNIT, -1)
    if (stored >= 0 && stored < CustomExportUnit.entries.size) return CustomExportUnit.entries[stored]
    val legacy = prefs.getString(PrefKey.CUSTOM_EXPORT_UNIT, null)
    return when (legacy) {
        LEGACY_EXPORT_UNIT_SIZE -> CustomExportUnit.SIZE
        else -> TimeTravelConfig.DEFAULT_CUSTOM_EXPORT_UNIT
    }
}

fun setConfiguredCustomExportUnit(context: Context, unit: CustomExportUnit) {
    getRecorderPreferences(context).edit().putInt(PrefKey.CUSTOM_EXPORT_UNIT, unit.ordinal).apply()
}

fun isDiskBufferCacheEnabled(context: Context): Boolean {
    return true
}

fun isAggressiveRestartEnabled(context: Context): Boolean {
    return getRecorderPreferences(context).getBoolean(PrefKey.AGGRESSIVE_RESTART_ENABLED, true)
}

fun isWakeLockEnabled(context: Context): Boolean {
    return getRecorderPreferences(context).getBoolean(PrefKey.WAKE_LOCK_ENABLED, false)
}

fun isDebuggableBuild(context: Context): Boolean {
    return (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

fun getConfiguredThemeMode(context: Context): AppThemeMode {
    return AppThemeMode.fromPrefValue(
        getRecorderPreferences(context).getString(PrefKey.THEME_MODE, AppThemeMode.SYSTEM.prefValue),
    )
}

fun setConfiguredThemeMode(
    context: Context,
    mode: AppThemeMode,
) {
    getRecorderPreferences(context).edit().putString(PrefKey.THEME_MODE, mode.prefValue).apply()
}

fun getConfiguredRetentionSeconds(context: Context): Long {
    return max(60L, getRecorderPreferences(context).getLong(PrefKey.RETENTION_SECONDS, TimeTravelConfig.DEFAULT_RETENTION_SECONDS))
}

fun getConfiguredRetentionSizeBytes(context: Context): Long {
    return getRecorderPreferences(context).getLong(PrefKey.AUDIO_MEMORY_SIZE, TimeTravelConfig.DEFAULT_RETENTION_SIZE_BYTES)
        .coerceAtLeast(1L)
}

fun getConfiguredOutputFormat(context: Context): ExportFormat {
    return ExportFormat.fromPrefValue(getRecorderPreferences(context).getString(PrefKey.OUTPUT_FORMAT, ExportFormat.WAV.prefValue))
}

fun getConfiguredOutputCodec(context: Context): ExportCodec {
    return ExportCodec.fromPrefValue(getRecorderPreferences(context).getString(PrefKey.OUTPUT_CODEC, ExportCodec.PCM_16.prefValue))
}

fun getConfiguredPcmSampleFormat(context: Context): PcmSampleFormat {
    return PcmSampleFormat.fromPrefValue(getRecorderPreferences(context).getString(PrefKey.PCM_SAMPLE_FORMAT, PcmSampleFormat.PCM_16.prefValue))
}

fun preferredOutputFormatForCodec(codec: ExportCodec): ExportFormat {
    return codec.supportedFormats.firstOrNull() ?: preferredDefaultOutputFormat()
}

fun preferredDefaultOutputFormat(): ExportFormat {
    return ExportFormat.WAV
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
    val stored = getRecorderPreferences(context).getInt(PrefKey.OUTPUT_BITRATE_KBPS, fallback)
    return stored.coerceIn(range)
}

fun getConfiguredAudioSourceMode(context: Context): AudioSourceMode {
    return AudioSourceMode.fromSourceValue(
        getRecorderPreferences(context).getInt(
            PrefKey.AUDIO_SOURCE,
            AudioSourceMode.defaultMode().sourceValue,
        ),
    )
}

fun getConfiguredInputRouteMode(context: Context): InputRouteMode {
    return InputRouteMode.fromPrefValue(
        getRecorderPreferences(context).getString(PrefKey.INPUT_ROUTE, InputRouteMode.AUTO.prefValue),
    )
}

fun getConfiguredChannelMode(context: Context): ChannelMode {
    return ChannelMode.fromPrefValue(
        getRecorderPreferences(context).getString(PrefKey.CHANNEL_MODE, TimeTravelConfig.DEFAULT_CHANNEL_MODE.prefValue),
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
    if (prefs.contains(PrefKey.SAMPLE_RATE)) {
        val requested = prefs.getInt(PrefKey.SAMPLE_RATE, 0)
        if (requested > 0) return requested
    }
    return getPreferredSampleRate(context, sourceMode, routeMode, format, codec, channelMode)
}

fun getConfiguredMemorySizeBytes(
    context: Context,
    sampleRate: Int,
    channelMode: ChannelMode = getConfiguredChannelMode(context),
    format: ExportFormat = getConfiguredOutputFormat(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
    bitrateKbps: Int? = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
    sampleFormat: PcmSampleFormat = getConfiguredPcmSampleFormat(context),
): Long {
    val prefs = getRecorderPreferences(context)
    return when (getConfiguredRetentionMode(context)) {
        RetentionMode.SIZE -> {
            val configuredSizeBytes = getConfiguredRetentionSizeBytes(context)
            val retentionSeconds = estimateExportDurationSeconds(
                format = format,
                codec = codec,
                sampleRate = sampleRate,
                channelCount = channelMode.channelCount,
                sizeBytes = configuredSizeBytes,
                bitrateKbps = bitrateKbps,
            )
            bytesForRetentionSeconds(retentionSeconds, sampleRate, channelMode.channelCount, sampleFormat)
        }

        RetentionMode.TIME -> bytesForRetentionSeconds(getConfiguredRetentionSeconds(context), sampleRate, channelMode.channelCount, sampleFormat)
    }
}

fun getConfiguredWorkingMemorySizeBytes(
    context: Context,
    sampleRate: Int,
    channelMode: ChannelMode = getConfiguredChannelMode(context),
    format: ExportFormat = getConfiguredOutputFormat(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
    bitrateKbps: Int? = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
    sampleFormat: PcmSampleFormat = getConfiguredPcmSampleFormat(context),
): Long {
    return minOf(
        getConfiguredMemorySizeBytes(context, sampleRate, channelMode, format, codec, bitrateKbps, sampleFormat),
        getWorkingMemoryCapBytes(),
    )
}

fun getConfiguredPersistentPcmSizeBytes(
    context: Context,
    sampleRate: Int,
    channelMode: ChannelMode = getConfiguredChannelMode(context),
    format: ExportFormat = getConfiguredOutputFormat(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
    bitrateKbps: Int? = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
    sampleFormat: PcmSampleFormat = getConfiguredPcmSampleFormat(context),
): Long {
    return minOf(
        getConfiguredMemorySizeBytes(context, sampleRate, channelMode, format, codec, bitrateKbps, sampleFormat),
        getPersistentPcmBufferCapBytes(),
    )
}

fun bytesForRetentionSeconds(
    seconds: Long,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0) return 0
    val bytesPerSecond = bytesPerSecond(sampleRate, channelCount, sampleFormat)
    if (bytesPerSecond <= 0L || seconds <= 0L) return 0L
    if (seconds > Long.MAX_VALUE / bytesPerSecond) {
        return Long.MAX_VALUE
    }
    return seconds * bytesPerSecond
}

fun retentionSecondsForBytes(
    bytes: Long,
    sampleRate: Int,
    channelCount: Int,
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    val bytesPerSecond = bytesPerSecond(sampleRate, channelCount, sampleFormat)
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
        buildString {
            append(hours); append(':'); append(pad2(minutes)); append(':'); append(pad2(secs))
        }
    } else {
        buildString {
            append(minutes); append(':'); append(pad2(secs))
        }
    }
}

private fun pad2(value: Long): String {
    return if (value < 10) "0$value" else value.toString()
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
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || durationSeconds <= 0L) {
        return 0L
    }

    return when {
        format.isPcmContainer -> {
            WAV_HEADER_BYTES + durationSeconds * bytesPerSecond(sampleRate, channelCount, sampleFormat)
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
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0 || sizeBytes <= 0L) {
        return 0L
    }

    return when {
        format.isPcmContainer -> {
            ((sizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)) /
                bytesPerSecond(sampleRate, channelCount, sampleFormat)
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
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    return estimateExportDurationSeconds(
        format = format,
        codec = codec,
        sampleRate = sampleRate,
        channelCount = channelCount,
        sizeBytes = exportFileSizeLimitBytes(format),
        bitrateKbps = bitrateKbps,
        sampleFormat = sampleFormat,
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
        val advertisedRates = codecAdvertisedSampleRates(format, codec)
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
        return orderSampleRatesByPreference(supported, PREFERRED_DEFAULT_SAMPLE_RATE).first()
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    return nativeRate?.takeIf { it > 0 } ?: orderSampleRatesByPreference(standardSampleRates(), PREFERRED_DEFAULT_SAMPLE_RATE).first()
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
    val operationalCandidates = buildList {
        addAll(advertised)
        addAll(standardSampleRates())
    }
        .distinct()
        .filter { rate ->
            isCodecSupported(format, codec, rate, channelMode) &&
                isInputConfigSupported(context, rate, sourceMode, routeMode, channelMode)
        }
    return orderSampleRatesByPreference(operationalCandidates, requestedRate).firstOrNull() ?: 0
}

fun getWorkingMemoryCapBytes(): Long {
    return max(64L * 1024L * 1024L, Runtime.getRuntime().maxMemory() * 3L / 4L)
}

fun getRetentionMemoryCapBytes(): Long = getWorkingMemoryCapBytes()

fun getPersistentPcmBufferCapBytes(): Long = MAX_PERSISTENT_PCM_BUFFER_BYTES

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
    return if (modes.isNotEmpty()) modes else listOf(AudioSourceMode.defaultMode())
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

private val STANDARD_SAMPLE_RATES_LIST = STANDARD_SAMPLE_RATES.toList()

fun standardSampleRates(): List<Int> = STANDARD_SAMPLE_RATES_LIST

fun isRecorderCapabilityCacheWarm(): Boolean = capabilityCacheWarm

fun sampleRateLabel(sampleRate: Int): String {
    if (sampleRate % 1000 == 0) return "${sampleRate / 1000} kHz"
    val fracDigits = (sampleRate % 1000).toString().padStart(3, '0').dropLastWhile { it == '0' }
    return buildString {
        append(sampleRate / 1000)
        append('.')
        append(fracDigits); append(" kHz")
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
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Boolean {
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
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
            sampleFormat.audioEncoding,
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
                        .setEncoding(sampleFormat.audioEncoding)
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
        if (!isExportConfigurationSupported(format, codec, sampleRate, channelMode.channelCount)) {
            return@cached false
        }
        when {
            codec.isPcm -> format.isPcmContainer
            else -> {
                try {
                    val mimeType = requireNotNull(codec.encoderMimeType)
                    val encoderFormat = buildEncoderFormat(codec, sampleRate, channelMode.channelCount, defaultCodecBitrateKbps(codec, sampleRate, channelMode.channelCount))
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                        .asSequence()
                        .filter { it.isEncoder }
                        .filter { info -> info.supportedTypes.any { type -> type.equals(mimeType, ignoreCase = true) } }
                        .any { info ->
                            val capabilities = runCatching { info.getCapabilitiesForType(mimeType) }.onFailure { Log.w(TAG, "getCapabilitiesForType failed for $mimeType", it) }.getOrNull() ?: return@any false
                            val audioCapabilities = capabilities.audioCapabilities ?: return@any false
                            if (!supportsRequestedChannelCount(audioCapabilities, channelMode.channelCount)) return@any false
                            if (!supportsRequestedSampleRate(audioCapabilities, sampleRate)) return@any false
                            if (!supportsRequestedAacProfile(capabilities, codec)) return@any false
                            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(encoderFormat) != null
                        }
                } catch (_: Throwable) {
                    false
                }
            }
        }
    }
}

fun supportedFormats(): List<ExportFormat> {
    return listOf(ExportFormat.WAV)
}

fun supportedCodecs(format: ExportFormat): List<ExportCodec> {
    return if (format == ExportFormat.WAV) listOf(ExportCodec.PCM_16) else emptyList()
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
    sampleFormat: PcmSampleFormat = PcmSampleFormat.PCM_16,
): Long {
    if (sampleRate <= 0 || channelCount <= 0) return 0L
    return sampleRate.toLong() * channelCount.toLong() * sampleFormat.bytesPerSample.toLong()
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

internal fun isExportConfigurationSupported(
    format: ExportFormat,
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): Boolean {
    if (sampleRate <= 0 || channelCount <= 0) return false
    if (!isCodecCompatibleWithFormat(format, codec)) return false
    exactSupportedSampleRatesFor(format, codec)?.let { if (sampleRate !in it) return false }
    exactSupportedChannelCountsFor(format, codec)?.let { if (channelCount !in it) return false }
    return true
}

private fun supportsRequestedChannelCount(
    audioCapabilities: MediaCodecInfo.AudioCapabilities,
    requestedChannelCount: Int,
): Boolean {
    return requestedChannelCount in 1..audioCapabilities.maxInputChannelCount.coerceAtLeast(1)
}

private fun supportsRequestedSampleRate(
    audioCapabilities: MediaCodecInfo.AudioCapabilities,
    sampleRate: Int,
): Boolean {
    return runCatching { audioCapabilities.isSampleRateSupported(sampleRate) }.onFailure { Log.w(TAG, "isSampleRateSupported($sampleRate) failed", it) }.getOrDefault(false)
}

private fun supportsRequestedAacProfile(
    capabilities: MediaCodecInfo.CodecCapabilities,
    codec: ExportCodec,
): Boolean {
    val requestedProfile = codec.aacProfile ?: return true
    val supportedProfiles = capabilities.profileLevels.map { it.profile }.toSet()
    return when {
        requestedProfile in supportedProfiles -> true
        supportedProfiles.isEmpty() -> codec == ExportCodec.AAC_LC
        else -> false
    }
}

private fun fallbackAdvertisedSampleRates(
    codec: ExportCodec,
    hasEncoder: Boolean,
): List<Int> {
    if (!hasEncoder) return emptyList()
    return when (codec) {
        ExportCodec.AMR_NB -> listOf(8_000)
        ExportCodec.AMR_WB -> listOf(16_000)
        ExportCodec.AAC_LC,
        ExportCodec.AAC_ELD,
        ExportCodec.HE_AAC,
        ExportCodec.HE_AAC_V2,
        ExportCodec.XHE_AAC,
        -> AAC_SAMPLE_RATES.toList().sortedDescending()
        else -> standardSampleRates()
    }
}

private fun exactSupportedSampleRatesFor(
    format: ExportFormat,
    codec: ExportCodec,
): Set<Int>? {
    return when {
        format.isRawAacAdts || format.isTransportStream -> AAC_SAMPLE_RATES
        codec == ExportCodec.AMR_NB -> setOf(8_000)
        codec == ExportCodec.AMR_WB -> setOf(16_000)
        else -> null
    }
}

private fun exactSupportedChannelCountsFor(
    format: ExportFormat,
    codec: ExportCodec,
): Set<Int>? {
    return when {
        codec == ExportCodec.AMR_NB || codec == ExportCodec.AMR_WB -> setOf(1)
        else -> null
    }
}

private fun codecCapability(codec: ExportCodec): CodecCapability {
    return codecCapabilityCache.cached(codec) {
        if (codec.isPcm) {
            return@cached CodecCapability(
                bitrateRangeKbps = null,
                advertisedSampleRates = standardSampleRates(),
                maxChannelCount = ChannelMode.entries.maxOf { it.channelCount },
                supportedAacProfiles = emptySet(),
                hasEncoder = true,
            )
        }
        val mimeType = codec.encoderMimeType
            ?: return@cached CodecCapability(null, emptyList(), 0, emptySet(), false)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var bitrateRange: IntRange? = null
        val sampleRates = linkedSetOf<Int>()
        var maxChannelCount = 0
        val supportedAacProfiles = linkedSetOf<Int>()
        var hasEncoder = false
        codecList.codecInfos
            .asSequence()
            .filter { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) } }
            .forEach { info ->
                hasEncoder = true
                val capabilities =
                    runCatching { info.getCapabilitiesForType(mimeType) }.onFailure { Log.w(TAG, "codecCapability: getCapabilitiesForType($mimeType) failed", it) }.getOrNull()
                        ?: return@forEach
                val audioCapabilities = capabilities.audioCapabilities ?: return@forEach
                bitrateRange = mergeBitrateRanges(bitrateRange, audioCapabilities.getBitrateRange())
                maxChannelCount = maxOf(maxChannelCount, audioCapabilities.maxInputChannelCount.coerceAtLeast(1))
                val discreteRates = audioCapabilities.supportedSampleRates
                if (discreteRates != null) {
                    discreteRates.filterTo(sampleRates) { it in codecProbeSampleRates(codec) }
                } else {
                    codecProbeSampleRates(codec).forEach { rate ->
                        if (audioCapabilities.isSampleRateSupported(rate)) {
                            sampleRates += rate
                        }
                    }
                }
                if (codec.isAacFamily) {
                    capabilities.profileLevels.forEach { supportedAacProfiles += it.profile }
                }
            }
        val advertisedSampleRates =
            sampleRates.toList()
                .sortedDescending()
                .ifEmpty { fallbackAdvertisedSampleRates(codec, hasEncoder) }
        CodecCapability(
            bitrateRangeKbps = bitrateRange,
            advertisedSampleRates = advertisedSampleRates,
            maxChannelCount = maxChannelCount.coerceAtLeast(1),
            supportedAacProfiles = supportedAacProfiles,
            hasEncoder = hasEncoder,
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

private fun codecAdvertisedSampleRates(
    format: ExportFormat,
    codec: ExportCodec,
): List<Int> {
    val constrainedRates = exactSupportedSampleRatesFor(format, codec)
    val codecRates = codecCapability(codec).advertisedSampleRates
    return when {
        constrainedRates == null -> codecRates
        codecRates.isEmpty() -> constrainedRates.toList().sortedDescending()
        else -> codecRates.filter { it in constrainedRates }
    }
}

private fun codecProbeSampleRates(codec: ExportCodec): IntArray {
    return when {
        codec.isAacFamily -> AAC_SAMPLE_RATES.toIntArray()
        codec == ExportCodec.AMR_NB -> intArrayOf(8_000)
        codec == ExportCodec.AMR_WB -> intArrayOf(16_000)
        else -> STANDARD_SAMPLE_RATES
    }
}

fun orderSampleRatesByPreference(
    sampleRates: List<Int>,
    requestedRate: Int,
): List<Int> {
    if (sampleRates.isEmpty()) return emptyList()
    if (requestedRate <= 0) return sampleRates.sortedDescending()
    val exact = mutableListOf<Int>()
    val higher = mutableListOf<Int>()
    val lower = mutableListOf<Int>()
    val seen = HashSet<Int>(sampleRates.size)
    for (rate in sampleRates) {
        if (!seen.add(rate)) continue
        when {
            rate == requestedRate -> exact.add(rate)
            rate > requestedRate -> higher.add(rate)
            else -> lower.add(rate)
        }
    }
    higher.sortBy { it - requestedRate }
    lower.sortByDescending { it }
    return exact + higher + lower
}
