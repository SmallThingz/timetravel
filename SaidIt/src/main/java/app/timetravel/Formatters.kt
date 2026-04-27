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
    var seconds = floor(secondsFloat).toInt().coerceAtLeast(0)
    val minutes = seconds / 60
    seconds %= 60

    val text = buildString {
        if (minutes != 0) {
            outResult.count = minutes
            append(resources.getQuantityString(R.plurals.minute, minutes, minutes))
            if (seconds != 0) {
                append(resources.getString(R.string.minute_second_join))
                append(resources.getQuantityString(R.plurals.second, seconds, seconds))
            }
        } else {
            outResult.count = seconds
            append(resources.getQuantityString(R.plurals.second, seconds, seconds))
        }
        append('.')
    }

    outResult.text = text
}

fun formatShortTimer(seconds: Float): String {
    val totalSeconds = floor(seconds).toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

fun formatShortFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt().coerceAtMost(units.lastIndex)
    val value = size / 1024.0.pow(digitGroups.toDouble())
    return "${DecimalFormat("#,##0.#").format(value)} ${units[digitGroups]}"
}
