package app.timetravel

import android.app.Activity
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors

internal fun Activity.applyTimeTravelSystemBars() {
    @Suppress("DEPRECATION")
    window.statusBarColor = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface)
    @Suppress("DEPRECATION")
    window.navigationBarColor = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface)

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}
