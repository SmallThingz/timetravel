package app.timetravel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TimeTravelActivity : AppCompatActivity() {
    private var permissionDeniedDialog: AlertDialog? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.isNotEmpty() && grants.values.all { it }) {
                showFragment()
            } else {
                showPermissionDeniedDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_background_recorder)
        applyTimeTravelSystemBars()
    }

    override fun onStart() {
        super.onStart()
        dismissDialogs()
        requestPermissions()
    }

    override fun onRestart() {
        super.onRestart()
        dismissDialogs()
        requestPermissions()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.FOREGROUND_SERVICE,
            )
        }
    }

    private fun showFragment() {
        if (supportFragmentManager.findFragmentByTag(MAIN_FRAGMENT_TAG) != null) {
            return
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, TimeTravelFragment(), MAIN_FRAGMENT_TAG)
            .commit()
    }

    private fun showPermissionDeniedDialog() {
        if (permissionDeniedDialog?.isShowing == true) {
            return
        }

        permissionDeniedDialog = MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_TimeTravel_AlertDialog)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.permission_required_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.exit) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun dismissDialogs() {
        permissionDeniedDialog?.dismiss()
    }

    private companion object {
        const val MAIN_FRAGMENT_TAG = "main-fragment"
    }
}
