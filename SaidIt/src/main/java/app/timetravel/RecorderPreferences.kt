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
import android.media.MediaRecorder
import androidx.annotation.StringRes
import java.util.Locale
import kotlin.math.max

private const val BYTES_PER_PCM_SAMPLE = 2L
private val DEFAULT_EXPORT_PRESETS = intArrayOf(60, 5 * 60, 30 * 60, 60 * 60)
private val SAMPLE_RATE_CANDIDATES = intArrayOf(48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 11_025, 8_000)

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
) {
    AAC(TimeTravelConfig.OUTPUT_CODEC_AAC, R.string.codec_aac, "m4a"),
    WAV(TimeTravelConfig.OUTPUT_CODEC_WAV, R.string.codec_wav, "wav"),
    ;

    companion object {
        fun fromPrefValue(value: String?): ExportCodec {
            return entries.firstOrNull { it.prefValue == value } ?: WAV
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

fun getConfiguredRetentionSeconds(context: Context): Long {
    return max(60L, getRecorderPreferences(context).getLong(TimeTravelConfig.RETENTION_SECONDS_KEY, 30L * 60))
}

fun getConfiguredExportPresets(context: Context): IntArray {
    val raw = getRecorderPreferences(context).getString(TimeTravelConfig.EXPORT_PRESETS_KEY, null)
        ?: return DEFAULT_EXPORT_PRESETS.copyOf()
    val parsed = raw.split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it > 0 }
        .take(DEFAULT_EXPORT_PRESETS.size)
        .toMutableList()
    while (parsed.size < DEFAULT_EXPORT_PRESETS.size) {
        parsed += DEFAULT_EXPORT_PRESETS[parsed.size]
    }
    return parsed.toIntArray()
}

fun saveConfiguredExportPresets(
    context: Context,
    presets: IntArray,
) {
    val normalized = presets
        .mapIndexed { index, value ->
            if (value > 0) value else DEFAULT_EXPORT_PRESETS.getOrElse(index) { DEFAULT_EXPORT_PRESETS.last() }
        }
        .joinToString(",")
    getRecorderPreferences(context).edit().putString(TimeTravelConfig.EXPORT_PRESETS_KEY, normalized).apply()
}

fun getConfiguredOutputCodec(context: Context): ExportCodec {
    val prefs = getRecorderPreferences(context)
    val preferred = getPreferredOutputCodec()
    return ExportCodec.fromPrefValue(prefs.getString(TimeTravelConfig.OUTPUT_CODEC_KEY, preferred.prefValue))
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

fun getConfiguredSampleRate(
    context: Context,
    sourceMode: AudioSourceMode = getConfiguredAudioSourceMode(context),
    routeMode: InputRouteMode = getConfiguredInputRouteMode(context),
    codec: ExportCodec = getConfiguredOutputCodec(context),
): Int {
    val prefs = getRecorderPreferences(context)
    val preferred = getPreferredSampleRate(context, sourceMode, routeMode, codec)
    val requested = prefs.getInt(TimeTravelConfig.SAMPLE_RATE_KEY, preferred)
    return if (supportedSampleRates(context, sourceMode, routeMode, codec).contains(requested)) {
        requested
    } else {
        preferred
    }
}

fun getConfiguredMemorySizeBytes(
    context: Context,
    sampleRate: Int,
): Long {
    val prefs = getRecorderPreferences(context)
    return when (getConfiguredRetentionMode(context)) {
        RetentionMode.SIZE -> prefs.getLong(
            TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY,
            Runtime.getRuntime().maxMemory() / 4,
        ).coerceAtMost(getRetentionMemoryCapBytes())

        RetentionMode.TIME -> bytesForRetentionSeconds(getConfiguredRetentionSeconds(context), sampleRate)
    }
}

fun bytesForRetentionSeconds(
    seconds: Long,
    sampleRate: Int,
): Long {
    if (sampleRate <= 0) return 0
    return (seconds * sampleRate * BYTES_PER_PCM_SAMPLE).coerceAtMost(getRetentionMemoryCapBytes())
}

fun retentionSecondsForBytes(
    bytes: Long,
    sampleRate: Int,
): Long {
    if (sampleRate <= 0) return 0
    return bytes / (sampleRate * BYTES_PER_PCM_SAMPLE)
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

fun supportedCodecs(): List<ExportCodec> {
    val codecs = mutableListOf(ExportCodec.WAV)
    if (SAMPLE_RATE_CANDIDATES.any { isCodecSupported(ExportCodec.AAC, it) }) {
        codecs.add(0, ExportCodec.AAC)
    }
    return codecs
}

fun getPreferredOutputCodec(): ExportCodec {
    return if (supportedCodecs().contains(ExportCodec.AAC)) ExportCodec.AAC else ExportCodec.WAV
}

fun supportedSampleRates(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    codec: ExportCodec,
): List<Int> {
    return SAMPLE_RATE_CANDIDATES.filter { sampleRate ->
        isInputConfigSupported(context, sampleRate, sourceMode, routeMode) && isCodecSupported(codec, sampleRate)
    }
}

fun getPreferredSampleRate(
    context: Context,
    sourceMode: AudioSourceMode,
    routeMode: InputRouteMode,
    codec: ExportCodec,
): Int {
    val supported = supportedSampleRates(context, sourceMode, routeMode, codec)
    if (supported.isNotEmpty()) {
        return supported.first()
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    return nativeRate?.takeIf { it > 0 } ?: SAMPLE_RATE_CANDIDATES.first()
}

fun getRetentionMemoryCapBytes(): Long {
    return max(64L * 1024L * 1024L, Runtime.getRuntime().maxMemory() * 3L / 4L)
}

fun supportedAudioSourceModes(
    context: Context,
    routeMode: InputRouteMode,
    codec: ExportCodec,
): List<AudioSourceMode> {
    val modes = AudioSourceMode.availableModes().filter { sourceMode ->
        supportedSampleRates(context, sourceMode, routeMode, codec).isNotEmpty()
    }
    return if (modes.isNotEmpty()) modes else listOf(AudioSourceMode.MIC)
}

fun supportedInputRouteModes(context: Context): List<InputRouteMode> {
    return buildList {
        add(InputRouteMode.AUTO)
        if (hasBuiltInMicrophone(context)) {
            add(InputRouteMode.BUILTIN_MIC)
        }
    }
}

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
): Boolean {
    val minBuffer = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBuffer <= 0) {
        return false
    }

    val preferredDevice = if (routeMode == InputRouteMode.BUILTIN_MIC) findBuiltInMicrophone(context) else null
    if (routeMode == InputRouteMode.BUILTIN_MIC && preferredDevice == null) {
        return false
    }

    return try {
        val record = AudioRecord.Builder()
            .setAudioSource(sourceMode.sourceValue)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
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

fun isCodecSupported(
    codec: ExportCodec,
    sampleRate: Int,
): Boolean {
    return when (codec) {
        ExportCodec.WAV -> true
        ExportCodec.AAC -> {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                }
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
            } catch (_: Throwable) {
                false
            }
        }
    }
}
