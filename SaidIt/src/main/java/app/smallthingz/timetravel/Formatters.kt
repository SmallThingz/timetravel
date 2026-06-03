package app.smallthingz.timetravel

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.floor


fun formatShortTimer(seconds: Float): String {
    val totalSeconds = floor(seconds).toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, TimeTravelConfig.FORMAT_DURATION_HMS, hours, minutes, secs)
    } else {
        String.format(Locale.US, TimeTravelConfig.FORMAT_DURATION_MS, minutes, secs)
    }
}

fun formatShortFileSize(size: Long): String {
    val mebibytes = size.coerceAtLeast(0L) / (1024.0 * 1024.0)
    val formatter = DecimalFormat(TimeTravelConfig.FORMAT_SIZE_MIB, DecimalFormatSymbols(Locale.US))
    return "${formatter.format(mebibytes)}${TimeTravelConfig.MIB_SUFFIX}"
}

fun formatSavedRecordingDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L && seconds > 0L -> "${minutes}m ${seconds}s"
        minutes > 0L -> "$minutes min"
        else -> "$seconds s"
    }
}

fun formatPlaybackTime(durationMillis: Int): String {
    return formatShortTimer(durationMillis.coerceAtLeast(0) / 1000f)
}
