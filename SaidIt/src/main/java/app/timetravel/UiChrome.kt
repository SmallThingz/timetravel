package app.timetravel

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors

internal fun Activity.applyTimeTravelSystemBars() {
    val isNightMode =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    window.statusBarColor = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface)
    @Suppress("DEPRECATION")
    window.navigationBarColor = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface)

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !isNightMode
        isAppearanceLightNavigationBars = !isNightMode
    }
}
