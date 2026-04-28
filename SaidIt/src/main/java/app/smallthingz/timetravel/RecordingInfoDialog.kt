package app.smallthingz.timetravel

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun showRecordingInfoDialog(
    context: Context,
    recording: RecordingEntity,
) {
    val content = LayoutInflater.from(context).inflate(R.layout.dialog_recording_details, null, false)
    content.findViewById<TextView>(R.id.recording_details_text).text = buildRecordingDetailsText(context, recording)

    ThemedDialog.create(
        context = context,
        title = context.getString(R.string.recording_info),
        content = content,
        positiveText = null,
        negativeText = null,
    ).dialog.show()
}

internal fun buildRecordingDetailsText(
    context: Context,
    recording: RecordingEntity,
): String {
    val startedAt = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z", Locale.getDefault())
        .format(Instant.ofEpochMilli(recording.startedAtMillis).atZone(ZoneId.systemDefault()))
    return buildString {
        appendLine("${context.getString(R.string.recording_details_name)} ${recording.displayName}")
        appendLine("${context.getString(R.string.recording_details_started)} $startedAt")
        appendLine("${context.getString(R.string.recording_details_duration)} ${formatSavedRecordingDuration(recording.durationMillis)}")
        appendLine("${context.getString(R.string.recording_details_size)} ${formatShortFileSize(recording.sizeBytes)}")
        appendLine("${context.getString(R.string.recording_details_codec)} ${recording.codecSummary}")
        appendLine("${context.getString(R.string.recording_details_mime)} ${recording.mimeType}")
        appendLine("${context.getString(R.string.recording_details_storage)} ${recording.storageType}")
        append("${context.getString(R.string.recording_details_location)} ${describeRecordingLocation(context, recording)}")
    }.trim()
}
