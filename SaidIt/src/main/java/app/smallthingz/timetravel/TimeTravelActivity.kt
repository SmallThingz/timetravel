package app.smallthingz.timetravel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.Menu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TimeTravelActivity : AppCompatActivity() {
    private var permissionDeniedDialog: AlertDialog? = null
    private lateinit var container: android.view.View
    private lateinit var bottomNavigation: BottomNavigationView
    private var selectedTabId = R.id.navigation_capture

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.isNotEmpty() && grants.values.all { it }) {
                val existingCapture = supportFragmentManager.findFragmentByTag(CAPTURE_FRAGMENT_TAG)
                val existingFiles = supportFragmentManager.findFragmentByTag(FILES_FRAGMENT_TAG)
                if (existingCapture == null || existingFiles == null) {
                    val captureFragment = existingCapture ?: TimeTravelFragment()
                    val filesFragment = existingFiles ?: SavedRecordingsFragment()
                    supportFragmentManager.beginTransaction().apply {
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
                    }.commitNow()
                }
                container.isVisible = true
                refreshBottomNavigation()
                bottomNavigation.isVisible = true
                if (bottomNavigation.selectedItemId != selectedTabId) {
                    bottomNavigation.selectedItemId = selectedTabId
                } else {
                    selectTab(selectedTabId)
                }
            } else {
                showPermissionDeniedDialog()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPhonePortraitOnly()
        applyConfiguredThemeMode(this)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_background_recorder)
        applyTimeTravelSystemBars()
        selectedTabId = savedInstanceState?.getInt(KEY_SELECTED_TAB, R.id.navigation_capture) ?: R.id.navigation_capture
        container = findViewById(R.id.container)
        bottomNavigation = findViewById(R.id.bottom_navigation)
        refreshBottomNavigation()
        bottomNavigation.setOnItemSelectedListener { item ->
            selectTab(item.itemId)
            true
        }
        bottomNavigation.post {
            val menuView = bottomNavigation.getChildAt(0) as? ViewGroup ?: return@post
            for (index in 0 until menuView.childCount) {
                menuView.getChildAt(index).apply {
                    tooltipText = null
                    setOnLongClickListener { true }
                }
            }
        }
        scheduleRecorderCapabilityCacheWarm(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        permissionDeniedDialog?.dismiss()
        permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_TAB, selectedTabId)
    }

    override fun onResume() {
        super.onResume()
        refreshBottomNavigation()
    }

    private fun selectTab(itemId: Int) {
        selectedTabId = if (itemId == NAVIGATION_CHUNKS_ID && !isDebugChunksTabEnabled(this)) R.id.navigation_capture else itemId
        val captureFragment = supportFragmentManager.findFragmentByTag(CAPTURE_FRAGMENT_TAG) ?: return
        val filesFragment = supportFragmentManager.findFragmentByTag(FILES_FRAGMENT_TAG) ?: return
        val chunksFragment = supportFragmentManager.findFragmentByTag(CHUNKS_FRAGMENT_TAG)

        supportFragmentManager.beginTransaction().apply {
            setReorderingAllowed(true)
            when (selectedTabId) {
                R.id.navigation_capture -> {
                    show(captureFragment)
                    hide(filesFragment)
                    chunksFragment?.let(::hide)
                }
                R.id.navigation_files -> {
                    show(filesFragment)
                    hide(captureFragment)
                    chunksFragment?.let(::hide)
                }
                NAVIGATION_CHUNKS_ID -> {
                    var activeChunksFragment = chunksFragment
                    if (activeChunksFragment == null) {
                        activeChunksFragment = DebugChunksFragment()
                        add(R.id.container, activeChunksFragment, CHUNKS_FRAGMENT_TAG)
                    }
                    show(activeChunksFragment)
                    hide(captureFragment)
                    hide(filesFragment)
                }
            }
        }.commit()

        if (selectedTabId == R.id.navigation_files) {
            (filesFragment as? SavedRecordingsFragment)?.refreshRecordings()
        }
    }

    private fun refreshBottomNavigation() {
        val menu = bottomNavigation.menu
        val chunksItem = menu.findItem(NAVIGATION_CHUNKS_ID)
        if (isDebugChunksTabEnabled(this)) {
            if (chunksItem == null) {
                menu.add(Menu.NONE, NAVIGATION_CHUNKS_ID, MENU_ORDER_CHUNKS, "").setIcon(R.drawable.ic_tab_chunks)
            }
        } else if (chunksItem != null) {
            menu.removeItem(NAVIGATION_CHUNKS_ID)
            if (selectedTabId == NAVIGATION_CHUNKS_ID) {
                selectedTabId = R.id.navigation_capture
                bottomNavigation.selectedItemId = selectedTabId
            }
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

    private companion object {
        const val KEY_SELECTED_TAB = "selected_tab"
        const val CAPTURE_FRAGMENT_TAG = "capture-fragment"
        const val FILES_FRAGMENT_TAG = "files-fragment"
        const val CHUNKS_FRAGMENT_TAG = "chunks-fragment"
        const val NAVIGATION_CHUNKS_ID = 0x7f0b0c01
        const val MENU_ORDER_CHUNKS = 2
    }
}
