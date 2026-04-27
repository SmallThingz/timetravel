package app.timetravel

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RECORDING_FILE_NAME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
private val RECORDING_SUFFIX_REGEX = Regex(" \\(\\d+\\)$")
private const val FILE_SAFE_COLON = '：'

fun getSavedRecordingsDirectory(): File {
    val recordingsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC + "/Recordings")
    return File(recordingsDir, TimeTravelConfig.APP_STORAGE_FOLDER_NAME)
}

fun buildRecordingUri(
    context: Context,
    file: File,
): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
}

fun buildOpenRecordingIntent(
    context: Context,
    file: File,
): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(buildRecordingUri(context, file), "audio/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun buildShareRecordingIntent(
    context: Context,
    file: File,
): Intent {
    val fileUri = buildRecordingUri(context, file)
    return Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, fileUri)
        type = context.contentResolver.getType(fileUri) ?: "audio/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun buildRecordingBaseName(startedAtMillis: Long): String {
    return Instant.ofEpochMilli(startedAtMillis)
        .atZone(ZoneId.of("UTC"))
        .format(RECORDING_FILE_NAME_FORMATTER)
        .replace(':', FILE_SAFE_COLON)
}

fun formatRecordingStartTimestamp(context: Context, startedAtMillis: Long): String {
    val date = java.util.Date(startedAtMillis)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    return timeFormat.format(date)
}

fun formatRecordingDateHeader(context: Context, startedAtMillis: Long): String {
    val date = java.util.Date(startedAtMillis)
    val dateFormat = android.text.format.DateFormat.getLongDateFormat(context)
    return dateFormat.format(date)
}

fun resolveRecordingCodecInfo(file: File): String {
    val retriever = MediaMetadataRetriever()
    val bitrate = runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
    }.getOrNull()
    runCatching { retriever.release() }
    
    val ext = file.extension.uppercase(Locale.US)
    if (bitrate != null && bitrate > 0) {
        val kbps = bitrate / 1000
        return "$ext • $kbps kbps"
    }
    return ext
}

fun resolveRecordingStartTimeMillis(file: File): Long {
    parseRecordingStartTimeMillis(file.nameWithoutExtension)?.let { return it }
    return file.lastModified()
}

fun resolveRecordingDurationMillis(file: File): Long {
    val retriever = MediaMetadataRetriever()
    val metadataDuration = runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull()
    runCatching { retriever.release() }

    if (metadataDuration != null && metadataDuration > 0L) {
        return metadataDuration
    }

    if (file.extension.equals(TimeTravelConfig.OUTPUT_CODEC_WAV, ignoreCase = true)) {
        return readWavDurationMillis(file)
    }

    return 0L
}

private fun parseRecordingStartTimeMillis(value: String): Long? {
    val normalized = value
        .replace(RECORDING_SUFFIX_REGEX, "")
        .replace(FILE_SAFE_COLON, ':')
    return runCatching {
        ZonedDateTime.parse(normalized, RECORDING_FILE_NAME_FORMATTER).toInstant().toEpochMilli()
    }.getOrNull()
}

private fun readWavDurationMillis(file: File): Long {
    return runCatching {
        RandomAccessFile(file, "r").use { input ->
            input.seek(22L)
            val channelCount = input.readUnsignedShortLE()
            input.seek(24L)
            val sampleRate = input.readIntLE()
            input.seek(34L)
            val bitsPerSample = input.readUnsignedShortLE()
            input.seek(40L)
            val dataSize = input.readIntLE().toLong() and 0xFFFF_FFFFL

            val byteRate = sampleRate.toLong() * channelCount.toLong() * bitsPerSample.toLong() / 8L
            if (byteRate <= 0L) 0L else dataSize * 1000L / byteRate
        }
    }.getOrDefault(0L)
}

private fun RandomAccessFile.readIntLE(): Int {
    val b0 = read()
    val b1 = read()
    val b2 = read()
    val b3 = read()
    if (b3 < 0) return 0
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun RandomAccessFile.readUnsignedShortLE(): Int {
    val b0 = read()
    val b1 = read()
    if (b1 < 0) return 0
    return b0 or (b1 shl 8)
}
