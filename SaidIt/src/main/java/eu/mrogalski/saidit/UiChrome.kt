package eu.mrogalski.saidit

import android.app.Activity
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

internal fun Activity.applyEchoSystemBars() {
    val isNightMode =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    window.statusBarColor = ContextCompat.getColor(this, R.color.background_canvas)
    @Suppress("DEPRECATION")
    window.navigationBarColor = ContextCompat.getColor(this, R.color.background_canvas)

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isNightMode
        isAppearanceLightNavigationBars = !isNightMode
    }
}
