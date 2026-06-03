package app.smallthingz.timetravel

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val sizeFormatter = DecimalFormat(TimeTravelConfig.FORMAT_SIZE_MIB, DecimalFormatSymbols(Locale.US))

fun formatShortTimer(seconds: Float): String {
    val totalSeconds = seconds.toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60

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

private fun pad2(value: Int): String {
    return if (value < 10) "0$value" else value.toString()
}

fun formatShortFileSize(size: Long): String {
    val mebibytes = size.coerceAtLeast(0L) / (1024.0 * 1024.0)
    return "${sizeFormatter.format(mebibytes)}${TimeTravelConfig.MIB_SUFFIX}"
}

fun formatSavedRecordingDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L && seconds > 0L -> "${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes} min"
        else -> "${seconds} s"
    }
}

fun formatPlaybackTime(durationMillis: Int): String {
    return formatShortTimer(durationMillis.coerceAtLeast(0) / 1000f)
}
