package app.smallthingz.timetravel

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.color.MaterialColors

internal fun Activity.applyPhonePortraitOnly() {
    requestedOrientation =
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
}

internal fun Activity.applyTimeTravelSystemBars() {
    val surfaceColor = MaterialColors.getColor(window.decorView, com.google.android.material.R.attr.colorSurface)
    val useDarkSystemIcons = ColorUtils.calculateLuminance(surfaceColor) > 0.5

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        @Suppress("DEPRECATION")
        window.statusBarColor = surfaceColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = surfaceColor
    }

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = useDarkSystemIcons
        isAppearanceLightNavigationBars = useDarkSystemIcons
    }
}

internal fun Activity.applyNoAnimationOpenTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

internal fun Activity.applyNoAnimationCloseTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
