package app.timetravel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TimeTravelActivity : AppCompatActivity() {
    private var permissionDeniedDialog: AlertDialog? = null
    private lateinit var container: android.view.View
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var bottomDivider: android.view.View
    private var selectedTabId = R.id.navigation_capture

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.isNotEmpty() && grants.values.all { it }) {
                showFragment()
            } else {
                showPermissionDeniedDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyConfiguredThemeMode(this)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_background_recorder)
        applyTimeTravelSystemBars()
        selectedTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.navigation_capture) ?: R.id.navigation_capture
        container = findViewById(R.id.container)
        bottomDivider = findViewById(R.id.bottom_navigation_divider)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
        bottomNavigation.post { clearBottomNavigationTooltips() }
        scheduleRecorderCapabilityCacheWarm(applicationContext)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTabId)
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
        ensureTabsCreated()
        container.isVisible = true
        bottomDivider.isVisible = true
        bottomNavigation.isVisible = true
        if (bottomNavigation.selectedItemId != selectedTabId) {
            bottomNavigation.selectedItemId = selectedTabId
        } else {
            selectTab(selectedTabId)
        }
    }

    private fun ensureTabsCreated() {
        val existingCapture = supportFragmentManager.findFragmentByTag(CAPTURE_FRAGMENT_TAG)
        val existingFiles = supportFragmentManager.findFragmentByTag(FILES_FRAGMENT_TAG)
        if (existingCapture != null && existingFiles != null) {
            return
        }

        val captureFragment = existingCapture ?: TimeTravelFragment()
        val filesFragment = existingFiles ?: SavedRecordingsFragment()
        supportFragmentManager.commitNow {
            setReorderingAllowed(true)
            if (!captureFragment.isAdded) {
                add(R.id.container, captureFragment, CAPTURE_FRAGMENT_TAG)
            }
            if (!filesFragment.isAdded) {
                add(R.id.container, filesFragment, FILES_FRAGMENT_TAG)
            }
            if (selectedTabId == R.id.navigation_capture) {
                hide(filesFragment)
            } else {
                hide(captureFragment)
            }
        }
    }

    private fun selectTab(itemId: Int) {
        selectedTabId = itemId
        val captureFragment = supportFragmentManager.findFragmentByTag(CAPTURE_FRAGMENT_TAG) ?: return
        val filesFragment = supportFragmentManager.findFragmentByTag(FILES_FRAGMENT_TAG) ?: return

        supportFragmentManager.commit {
            setReorderingAllowed(true)
            if (itemId == R.id.navigation_capture) {
                show(captureFragment)
                hide(filesFragment)
            } else {
                show(filesFragment)
                hide(captureFragment)
            }
        }

        if (itemId == R.id.navigation_files) {
            (filesFragment as? SavedRecordingsFragment)?.refreshRecordings()
        }
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

    private fun clearBottomNavigationTooltips() {
        val menuView = bottomNavigation.getChildAt(0) as? ViewGroup ?: return
        for (index in 0 until menuView.childCount) {
            menuView.getChildAt(index).apply {
                tooltipText = null
                setOnLongClickListener { true }
            }
        }
    }

    private companion object {
        const val KEY_SELECTED_TAB = "selected_tab"
        const val CAPTURE_FRAGMENT_TAG = "capture-fragment"
        const val FILES_FRAGMENT_TAG = "files-fragment"
    }
}
