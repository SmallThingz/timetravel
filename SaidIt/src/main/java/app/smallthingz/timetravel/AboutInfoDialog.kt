package app.smallthingz.timetravel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast

internal object AboutInfoDialog {
    private const val GITHUB_PROFILE_URL = "https://github.com/SmallThingz"

    fun show(context: Context) {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_about_info, null, false)
        content.findViewById<ImageView>(R.id.about_icon).setImageResource(R.drawable.ic_app_icon_preview)
        content.findViewById<TextView>(R.id.about_version).text = context.getString(R.string.about_version, appVersionName(context))
        content.findViewById<TextView>(R.id.about_github_value).apply {
            text = GITHUB_PROFILE_URL.removePrefix("https://")
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { openGithub(context) }
        }

        val handle = ThemedDialog.create(
            context = context,
            title = context.getString(R.string.app_name),
            content = content,
            positiveText = context.getString(R.string.about_open_github),
            negativeText = context.getString(R.string.close),
        )
        handle.actionRow.visibility = android.view.View.GONE
        handle.dialog.show()
    }

    private fun openGithub(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROFILE_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun appVersionName(context: Context): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return packageInfo.versionName.orEmpty()
    }
}
