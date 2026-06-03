package app.smallthingz.timetravel

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

private const val HTTPS_SCHEME = "https://"

internal object AboutInfoDialog {
    private const val GITHUB_REPO_URL = "https://github.com/SmallThingz/timetravel"

    fun show(context: Context) {
        val content = LayoutInflater.from(context).inflate(R.layout.dialog_about_info, FrameLayout(context), false)
        content.findViewById<ImageView>(R.id.about_icon).setImageResource(R.drawable.ic_app_icon_preview)
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        content.findViewById<TextView>(R.id.about_version).text =
            context.getString(R.string.about_version, packageInfo.versionName.orEmpty())
        content.findViewById<TextView>(R.id.about_github_value).apply {
            text = GITHUB_REPO_URL.removePrefix(HTTPS_SCHEME)
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { openGithub(context) }
        }
        content.findViewById<ImageButton>(R.id.about_github_open_button).setOnClickListener {
            openGithub(context)
        }

        val handle = ThemedDialog.create(
            context = context,
            title = context.getString(R.string.app_name),
            content = content,
            positiveText = null,
            negativeText = null,
        )
        handle.dialog.show()
    }

    private fun openGithub(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

}
