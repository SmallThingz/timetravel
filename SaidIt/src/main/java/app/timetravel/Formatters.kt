package app.timetravel

import android.content.res.Resources
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class NaturalLanguageResult(
    var text: String = "",
    var count: Int = 0,
)

fun formatNaturalLanguage(
    resources: Resources,
    secondsFloat: Float,
    outResult: NaturalLanguageResult,
) {
    val totalSeconds = floor(secondsFloat).toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    outResult.count = if (hours > 0) hours else if (minutes > 0) minutes else seconds

    val text = buildString {
        if (hours > 0) {
            append(String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds))
        } else if (minutes > 0) {
            append(String.format(Locale.US, "%d:%02d", minutes, seconds))
        } else {
            append(String.format(Locale.US, "0:%02d", seconds))
        }
    }

    outResult.text = text
}

fun formatShortTimer(seconds: Float): String {
    val totalSeconds = floor(seconds).toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, secs)
    }
}

fun formatShortFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = size / 1024.0.pow(digitGroups.toDouble())
    return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
}