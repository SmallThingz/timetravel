package app.timetravel

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale

private val RECORDING_SUFFIX_REGEX = Regex(" \\(\\d+\\)$")
private val SUPPORTED_RECORDING_EXTENSIONS = setOf("wav", "m4a", "aac")

enum class RecordingStorageType {
    FILE,
    DOCUMENT,
}

data class RecordingOutputTarget(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val storageType: RecordingStorageType,
    val directoryId: String,
    val startedAtMillis: Long,
    val file: File? = null,
    val uri: Uri? = null,
)

fun getSavedRecordingsDirectory(): File {
    val recordingsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC + "/Recordings")
    return File(recordingsDir, TimeTravelConfig.APP_STORAGE_FOLDER_NAME)
}

fun getConfiguredExportTreeUri(context: Context): Uri? {
    val raw = getRecorderPreferences(context).getString(TimeTravelConfig.EXPORT_DIRECTORY_URI_KEY, null) ?: return null
    return raw.takeIf { it.isNotBlank() }?.let(Uri::parse)
}

fun setConfiguredExportTreeUri(
    context: Context,
    treeUri: Uri?,
) {
    getRecorderPreferences(context).edit()
        .putString(TimeTravelConfig.EXPORT_DIRECTORY_URI_KEY, treeUri?.toString())
        .apply()
}

fun getConfiguredOutputDirectoryId(context: Context): String {
    return getOutputDirectoryId(getConfiguredExportTreeUri(context))
}

fun getOutputDirectoryId(treeUri: Uri?): String {
    return treeUri?.toString() ?: getSavedRecordingsDirectory().absolutePath
}

fun describeConfiguredOutputDirectory(context: Context): String {
    return describeOutputDirectory(context, getConfiguredExportTreeUri(context))
}

fun describeOutputDirectory(
    context: Context,
    treeUri: Uri?,
): String {
    if (treeUri == null) {
        return "Music/Recordings/${TimeTravelConfig.APP_STORAGE_FOLDER_NAME}"
    }
    val name = DocumentFile.fromTreeUri(context, treeUri)?.name
    return name ?: treeUri.toString()
}

fun buildRecordingUri(
    context: Context,
    recording: RecordingEntity,
): Uri {
    return when (RecordingStorageType.valueOf(recording.storageType)) {
        RecordingStorageType.FILE -> {
            val file = File(recording.id)
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }

        RecordingStorageType.DOCUMENT -> Uri.parse(recording.id)
    }
}

fun buildOpenRecordingIntent(
    context: Context,
    recording: RecordingEntity,
): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(buildRecordingUri(context, recording), recording.mimeType.ifBlank { "audio/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun buildShareRecordingIntent(
    context: Context,
    recording: RecordingEntity,
): Intent {
    val fileUri = buildRecordingUri(context, recording)
    return Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_STREAM, fileUri)
        type = recording.mimeType.ifBlank { context.contentResolver.getType(fileUri) ?: "audio/*" }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun buildRecordingBaseName(startedAtMillis: Long): String {
    return startedAtMillis.toString()
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
    return resolveRecordingCodecInfo(
        extension = file.extension,
        bitrate = runCatching {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
        }.getOrNull(),
    ).also {
        runCatching { retriever.release() }
    }
}

fun resolveRecordingCodecInfo(
    context: Context,
    uri: Uri,
    displayName: String,
): String {
    val retriever = MediaMetadataRetriever()
    val bitrate = runCatching {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
    }.getOrNull().also {
        runCatching { retriever.release() }
    }
    return resolveRecordingCodecInfo(extension = displayName.substringAfterLast('.', ""), bitrate = bitrate)
}

fun buildCodecSummary(
    codec: ExportCodec,
    sampleRate: Int,
    channelCount: Int,
): String {
    val channelLabel = if (channelCount >= 2) "Stereo" else "Mono"
    return when (codec) {
        ExportCodec.AAC -> "AAC • ${sampleRateLabel(sampleRate)} • $channelLabel • ${aacBitrateForSampleRate(sampleRate, channelCount) / 1000} kbps"
        ExportCodec.WAV -> "WAV • ${sampleRateLabel(sampleRate)} • $channelLabel"
    }
}

fun describeRecordingLocation(recording: RecordingEntity): String {
    return when (RecordingStorageType.valueOf(recording.storageType)) {
        RecordingStorageType.FILE -> recording.id
        RecordingStorageType.DOCUMENT -> recording.id
    }
}

private fun resolveRecordingCodecInfo(
    extension: String,
    bitrate: Int?,
): String {
    val ext = extension.uppercase(Locale.US)
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

fun resolveRecordingStartTimeMillis(
    displayName: String,
    fallbackMillis: Long,
): Long {
    parseRecordingStartTimeMillis(displayName.substringBeforeLast('.', displayName))?.let { return it }
    return fallbackMillis
}

fun resolveRecordingDurationMillis(file: File): Long {
    val retriever = MediaMetadataRetriever()
    val metadataDuration = runCatching {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull().also {
        runCatching { retriever.release() }
    }

    if (metadataDuration != null && metadataDuration > 0L) {
        return metadataDuration
    }

    if (file.extension.equals(TimeTravelConfig.OUTPUT_CODEC_WAV, ignoreCase = true)) {
        return readWavDurationMillis(file)
    }

    return 0L
}

fun resolveRecordingDurationMillis(
    context: Context,
    uri: Uri,
    displayName: String,
): Long {
    val retriever = MediaMetadataRetriever()
    val metadataDuration = runCatching {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }.getOrNull().also {
        runCatching { retriever.release() }
    }

    if (metadataDuration != null && metadataDuration > 0L) {
        return metadataDuration
    }
    if (displayName.endsWith(".wav", ignoreCase = true)) {
        return context.contentResolver.openInputStream(uri)?.use(::readWavDurationMillis).orEmptyDuration()
    }
    return 0L
}

fun createOutputTarget(
    context: Context,
    requestedName: String?,
    startedAtMillis: Long,
    codec: ExportCodec,
): RecordingOutputTarget {
    val baseName = sanitizeBaseName(
        if (requestedName.isNullOrBlank()) buildRecordingBaseName(startedAtMillis) else requestedName.trim(),
    )
    val displayName = "$baseName.${codec.extension}"
    val mimeType = when (codec) {
        ExportCodec.AAC -> "audio/mp4"
        ExportCodec.WAV -> "audio/wav"
    }
    return createOutputTarget(context, displayName, mimeType, startedAtMillis)
}

fun createOutputTarget(
    context: Context,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    val treeUri = getConfiguredExportTreeUri(context)
    return if (treeUri == null) {
        createLocalOutputTarget(requestedDisplayName, mimeType, startedAtMillis)
    } else {
        createDocumentOutputTarget(context, treeUri, requestedDisplayName, mimeType, startedAtMillis)
    }
}

fun openWritableParcelFileDescriptor(
    context: Context,
    target: RecordingOutputTarget,
): ParcelFileDescriptor {
    return when (target.storageType) {
        RecordingStorageType.FILE -> {
            ParcelFileDescriptor.open(
                requireNotNull(target.file),
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            )
        }

        RecordingStorageType.DOCUMENT -> {
            context.contentResolver.openFileDescriptor(requireNotNull(target.uri), "rw")
                ?: throw IOException("Unable to open output document: ${target.id}")
        }
    }
}

fun resolveOutputTargetSize(
    context: Context,
    target: RecordingOutputTarget,
): Long {
    return when (target.storageType) {
        RecordingStorageType.FILE -> target.file?.length() ?: 0L
        RecordingStorageType.DOCUMENT -> DocumentFile.fromSingleUri(context, requireNotNull(target.uri))?.length() ?: 0L
    }
}

fun buildRecordingEntity(
    context: Context,
    target: RecordingOutputTarget,
    durationMillis: Long,
    codecSummary: String,
): RecordingEntity {
    return RecordingEntity(
        id = target.id,
        displayName = target.displayName,
        mimeType = target.mimeType,
        startedAtMillis = target.startedAtMillis,
        durationMillis = durationMillis,
        sizeBytes = resolveOutputTargetSize(context, target),
        codecSummary = codecSummary,
        storageType = target.storageType.name,
        directoryId = target.directoryId,
    )
}

fun recordingExists(
    context: Context,
    recording: RecordingEntity,
): Boolean {
    return when (RecordingStorageType.valueOf(recording.storageType)) {
        RecordingStorageType.FILE -> File(recording.id).isFile
        RecordingStorageType.DOCUMENT -> DocumentFile.fromSingleUri(context, Uri.parse(recording.id))?.exists() == true
    }
}

fun deleteRecordingAsset(
    context: Context,
    recording: RecordingEntity,
): Boolean {
    return when (RecordingStorageType.valueOf(recording.storageType)) {
        RecordingStorageType.FILE -> File(recording.id).delete()
        RecordingStorageType.DOCUMENT -> DocumentFile.fromSingleUri(context, Uri.parse(recording.id))?.delete() == true
    }
}

fun renameRecordingAsset(
    context: Context,
    recording: RecordingEntity,
    requestedBaseName: String,
): RecordingEntity? {
    val displayName = buildRenamedDisplayName(recording.displayName, requestedBaseName)
    return when (RecordingStorageType.valueOf(recording.storageType)) {
        RecordingStorageType.FILE -> renameFileRecording(recording, displayName)
        RecordingStorageType.DOCUMENT -> renameDocumentRecording(context, recording, displayName)
    }
}

fun copyRecordingToConfiguredDirectory(
    context: Context,
    recording: RecordingEntity,
): RecordingEntity? {
    val target = createOutputTarget(
        context = context,
        requestedDisplayName = recording.displayName,
        mimeType = recording.mimeType,
        startedAtMillis = recording.startedAtMillis,
    )

    return try {
        openRecordingInputStream(context, recording)?.use { input ->
            when (target.storageType) {
                RecordingStorageType.FILE -> {
                    FileOutputStream(requireNotNull(target.file)).use { output ->
                        input.copyTo(output)
                    }
                }

                RecordingStorageType.DOCUMENT -> {
                    context.contentResolver.openOutputStream(requireNotNull(target.uri), "w")?.use { output ->
                        input.copyTo(output)
                    } ?: throw IOException("Unable to open target output stream")
                }
            }
        } ?: return null

        recording.copy(
            id = target.id,
            displayName = target.displayName,
            sizeBytes = resolveOutputTargetSize(context, target),
            storageType = target.storageType.name,
            directoryId = target.directoryId,
        )
    } catch (_: IOException) {
        runCatching {
            when (target.storageType) {
                RecordingStorageType.FILE -> target.file?.delete()
                RecordingStorageType.DOCUMENT -> DocumentFile.fromSingleUri(context, requireNotNull(target.uri))?.delete()
            }
        }
        null
    }
}

fun listCurrentOutputDirectoryRecordings(context: Context): List<RecordingEntity> {
    val treeUri = getConfiguredExportTreeUri(context)
    return if (treeUri == null) {
        val directory = getSavedRecordingsDirectory()
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() > 0L && !it.name.startsWith(".") }
            ?.filter { it.extension.lowercase(Locale.US) in SUPPORTED_RECORDING_EXTENSIONS }
            ?.map { file ->
                RecordingEntity(
                    id = file.absolutePath,
                    displayName = file.name,
                    mimeType = guessMimeType(file.name),
                    startedAtMillis = resolveRecordingStartTimeMillis(file),
                    durationMillis = resolveRecordingDurationMillis(file),
                    sizeBytes = file.length(),
                    codecSummary = resolveRecordingCodecInfo(file),
                    storageType = RecordingStorageType.FILE.name,
                    directoryId = directory.absolutePath,
                )
            }
            ?.toList()
            .orEmpty()
    } else {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        tree.listFiles()
            .asSequence()
            .filter { it.isFile && it.length() > 0L }
            .filter { file -> file.name?.substringAfterLast('.', "")?.lowercase(Locale.US) in SUPPORTED_RECORDING_EXTENSIONS }
            .mapNotNull { file ->
                val uri = file.uri
                val name = file.name ?: return@mapNotNull null
                RecordingEntity(
                    id = uri.toString(),
                    displayName = name,
                    mimeType = file.type ?: guessMimeType(name),
                    startedAtMillis = resolveRecordingStartTimeMillis(name, file.lastModified()),
                    durationMillis = resolveRecordingDurationMillis(context, uri, name),
                    sizeBytes = file.length(),
                    codecSummary = resolveRecordingCodecInfo(context, uri, name),
                    storageType = RecordingStorageType.DOCUMENT.name,
                    directoryId = treeUri.toString(),
                )
            }
            .toList()
    }
}

private fun openRecordingInputStream(
    context: Context,
    recording: RecordingEntity,
): InputStream? {
    return when (RecordingStorageType.valueOf(recording.storageType)) {
        RecordingStorageType.FILE -> FileInputStream(File(recording.id))
        RecordingStorageType.DOCUMENT -> context.contentResolver.openInputStream(Uri.parse(recording.id))
    }
}

private fun createLocalOutputTarget(
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    val storageDir = getSavedRecordingsDirectory()
    if (!storageDir.exists()) {
        storageDir.mkdirs()
    }

    val uniqueName = findAvailableDisplayName(requestedDisplayName) { candidate ->
        File(storageDir, candidate).exists()
    }
    val file = File(storageDir, uniqueName)
    return RecordingOutputTarget(
        id = file.absolutePath,
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.FILE,
        directoryId = storageDir.absolutePath,
        startedAtMillis = startedAtMillis,
        file = file,
    )
}

private fun renameFileRecording(
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    val source = File(recording.id)
    val parent = source.parentFile ?: return null
    val uniqueName = findAvailableDisplayName(displayName) { candidate ->
        candidate != source.name && File(parent, candidate).exists()
    }
    if (uniqueName == source.name) {
        return recording
    }
    val target = File(parent, uniqueName)
    if (!source.renameTo(target)) {
        return null
    }
    return recording.copy(
        id = target.absolutePath,
        displayName = uniqueName,
    )
}

private fun renameDocumentRecording(
    context: Context,
    recording: RecordingEntity,
    displayName: String,
): RecordingEntity? {
    val document = DocumentFile.fromSingleUri(context, Uri.parse(recording.id)) ?: return null
    val tree = DocumentFile.fromTreeUri(context, Uri.parse(recording.directoryId)) ?: return null
    val uniqueName = findAvailableDisplayName(displayName) { candidate ->
        candidate != document.name && tree.findFile(candidate) != null
    }
    if (uniqueName == document.name) {
        return recording
    }
    if (!document.renameTo(uniqueName)) {
        return null
    }
    return recording.copy(
        id = document.uri.toString(),
        displayName = document.name ?: uniqueName,
    )
}

private fun createDocumentOutputTarget(
    context: Context,
    treeUri: Uri,
    requestedDisplayName: String,
    mimeType: String,
    startedAtMillis: Long,
): RecordingOutputTarget {
    val tree = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IOException("Unable to access output directory")
    val uniqueName = findAvailableDisplayName(requestedDisplayName) { candidate ->
        tree.findFile(candidate) != null
    }
    val documentUri = DocumentsContract.createDocument(
        context.contentResolver,
        buildTreeDocumentUri(treeUri),
        mimeType,
        uniqueName,
    ) ?: throw IOException("Unable to create output document")

    return RecordingOutputTarget(
        id = documentUri.toString(),
        displayName = uniqueName,
        mimeType = mimeType,
        storageType = RecordingStorageType.DOCUMENT,
        directoryId = treeUri.toString(),
        startedAtMillis = startedAtMillis,
        uri = documentUri,
    )
}

private fun buildTreeDocumentUri(treeUri: Uri): Uri {
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
}

private fun findAvailableDisplayName(
    requestedDisplayName: String,
    exists: (String) -> Boolean,
): String {
    val dotIndex = requestedDisplayName.lastIndexOf('.')
    val name = if (dotIndex > 0) requestedDisplayName.substring(0, dotIndex) else requestedDisplayName
    val extension = if (dotIndex > 0) requestedDisplayName.substring(dotIndex) else ""
    var candidate = requestedDisplayName
    var suffix = 2
    while (exists(candidate)) {
        candidate = "$name ($suffix)$extension"
        suffix++
    }
    return candidate
}

private fun sanitizeBaseName(name: String): String {
    val sanitized = name
        .replace(Regex("[\\\\/*?\"<>|]"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
    return sanitized.ifEmpty { "TimeTravel" }
}

private fun buildRenamedDisplayName(
    existingDisplayName: String,
    requestedBaseName: String,
): String {
    val extension = existingDisplayName.substringAfterLast('.', "")
    val baseName = sanitizeBaseName(requestedBaseName.substringBeforeLast('.', requestedBaseName))
    return if (extension.isBlank()) baseName else "$baseName.$extension"
}

private fun guessMimeType(displayName: String): String {
    return when (displayName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}

private fun parseRecordingStartTimeMillis(value: String): Long? {
    val normalized = value.replace(RECORDING_SUFFIX_REGEX, "")
    normalized.toLongOrNull()?.let { return it }
    return null
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

private fun readWavDurationMillis(input: InputStream): Long {
    return runCatching {
        val header = ByteArray(44)
        var readTotal = 0
        while (readTotal < header.size) {
            val read = input.read(header, readTotal, header.size - readTotal)
            if (read <= 0) break
            readTotal += read
        }
        if (readTotal < 44) return@runCatching 0L

        val channelCount = littleEndianShort(header, 22)
        val sampleRate = littleEndianInt(header, 24)
        val bitsPerSample = littleEndianShort(header, 34)
        val dataSize = littleEndianInt(header, 40).toLong() and 0xFFFF_FFFFL
        val byteRate = sampleRate.toLong() * channelCount.toLong() * bitsPerSample.toLong() / 8L
        if (byteRate <= 0L) 0L else dataSize * 1000L / byteRate
    }.getOrDefault(0L)
}

private fun Long?.orEmptyDuration(): Long = this ?: 0L

private fun littleEndianInt(
    data: ByteArray,
    offset: Int,
): Int {
    return (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)
}

private fun littleEndianShort(
    data: ByteArray,
    offset: Int,
): Int {
    return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
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
