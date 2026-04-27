package app.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {
    private lateinit var themeLayout: TextInputLayout
    private lateinit var retentionTimeLayout: TextInputLayout
    private lateinit var retentionSizeLayout: TextInputLayout
    private lateinit var codecLayout: TextInputLayout
    private lateinit var sampleRateLayout: TextInputLayout
    private lateinit var audioSourceLayout: TextInputLayout
    private lateinit var channelModeLayout: TextInputLayout
    private lateinit var inputRouteLayout: TextInputLayout
    private lateinit var bitrateGroup: View
    private lateinit var bitrateValue: TextView
    private lateinit var bitrateSlider: Slider
    private lateinit var themeDropdown: MaterialAutoCompleteTextView
    private lateinit var codecDropdown: MaterialAutoCompleteTextView
    private lateinit var sampleRateDropdown: MaterialAutoCompleteTextView
    private lateinit var audioSourceDropdown: MaterialAutoCompleteTextView
    private lateinit var channelModeDropdown: MaterialAutoCompleteTextView
    private lateinit var inputRouteDropdown: MaterialAutoCompleteTextView
    private lateinit var retentionTimeInput: EditText
    private lateinit var retentionSizeInput: EditText
    private lateinit var exportPathValue: TextView
    private lateinit var chooseFolderButton: MaterialButton
    private lateinit var defaultFolderButton: MaterialButton
    private lateinit var moveRecordingsButton: MaterialButton
    private lateinit var batteryOptimizationButton: MaterialButton
    private lateinit var batteryOptimizationStatus: TextView
    private lateinit var persistentBufferRow: View
    private lateinit var aggressiveRestartRow: View
    private lateinit var wakeLockRow: View
    private lateinit var persistentBufferSwitch: MaterialSwitch
    private lateinit var aggressiveRestartSwitch: MaterialSwitch
    private lateinit var wakeLockSwitch: MaterialSwitch
    private lateinit var undoButton: View
    private lateinit var applyButton: View

    private var service: TimeTravelService? = null
    private var serviceBound = false
    private var bindingUi = false
    private var capabilityUiReady = false
    private var hasUnsavedChanges = false
    private var capabilityRefreshGeneration = 0
    private var moveAvailabilityGeneration = 0

    private val originalSettings = SettingsSnapshot()
    private val currentSettings = SettingsSnapshot()

    private var availableThemes: List<AppThemeMode> = emptyList()
    private var availableCodecs: List<ExportCodec> = emptyList()
    private var availableSourceModes: List<AudioSourceMode> = emptyList()
    private var availableChannelModes: List<ChannelMode> = emptyList()
    private var availableRouteModes: List<InputRouteMode> = emptyList()
    private var availableSampleRates: List<Int> = emptyList()
    private var activeRetentionMode = RetentionMode.TIME
    private var retentionTimeSecondsValue = 0
    private var retentionSizeMbValue = 0L
    private var selectedExportTreeUri: Uri? = null

    data class SettingsSnapshot(
        var themeMode: AppThemeMode = AppThemeMode.SYSTEM,
        var retentionMode: RetentionMode = RetentionMode.TIME,
        var retentionTime: Int = 0,
        var retentionSizeMb: Long = 0,
        var codec: ExportCodec? = null,
        var bitrateKbps: Int = 0,
        var source: AudioSourceMode? = null,
        var channelMode: ChannelMode? = null,
        var route: InputRouteMode? = null,
        var sampleRate: Int = 0,
        var exportDirectoryUri: String? = null,
        var persistentBufferEnabled: Boolean = true,
        var aggressiveRestartEnabled: Boolean = true,
        var wakeLockEnabled: Boolean = false,
    ) {
        fun copyFrom(other: SettingsSnapshot) {
            themeMode = other.themeMode
            retentionMode = other.retentionMode
            retentionTime = other.retentionTime
            retentionSizeMb = other.retentionSizeMb
            codec = other.codec
            bitrateKbps = other.bitrateKbps
            source = other.source
            channelMode = other.channelMode
            route = other.route
            sampleRate = other.sampleRate
            exportDirectoryUri = other.exportDirectoryUri
            persistentBufferEnabled = other.persistentBufferEnabled
            aggressiveRestartEnabled = other.aggressiveRestartEnabled
            wakeLockEnabled = other.wakeLockEnabled
        }
    }

    private val exportDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            selectedExportTreeUri = treeUri
            refreshExportDirectoryUi()
            refreshMoveRecordingsAvailability()
            saveCurrentToSnapshot(currentSettings)
            pushUndoState()
        }

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(
            className: ComponentName,
            binder: IBinder,
        ) {
            val typedBinder = binder as TimeTravelService.BackgroundRecorderBinder
            service = typedBinder.service
            serviceBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            service = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyConfiguredThemeMode(this)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        applyTimeTravelSystemBars()
        setContentView(R.layout.activity_settings)

        val root = findViewById<View>(R.id.settings_layout)
        applyWindowInsets(root)
        installFocusClear(root)
        installFocusClear(findViewById(R.id.settings_scroll))
        installFocusClear(findViewById(R.id.settings_content))

        themeLayout = findViewById(R.id.theme_layout)
        retentionTimeLayout = findViewById(R.id.retention_time_layout)
        retentionSizeLayout = findViewById(R.id.retention_size_layout)
        codecLayout = findViewById(R.id.codec_layout)
        sampleRateLayout = findViewById(R.id.sample_rate_layout)
        audioSourceLayout = findViewById(R.id.audio_source_layout)
        channelModeLayout = findViewById(R.id.channel_mode_layout)
        inputRouteLayout = findViewById(R.id.input_route_layout)
        bitrateGroup = findViewById(R.id.bitrate_group)
        bitrateValue = findViewById(R.id.bitrate_value)
        bitrateSlider = findViewById(R.id.bitrate_slider)
        themeDropdown = findViewById(R.id.theme_dropdown)
        codecDropdown = findViewById(R.id.codec_dropdown)
        sampleRateDropdown = findViewById(R.id.sample_rate_dropdown)
        audioSourceDropdown = findViewById(R.id.audio_source_dropdown)
        channelModeDropdown = findViewById(R.id.channel_mode_dropdown)
        inputRouteDropdown = findViewById(R.id.input_route_dropdown)
        retentionTimeInput = findViewById(R.id.retention_time_input)
        retentionSizeInput = findViewById(R.id.retention_size_input)
        exportPathValue = findViewById(R.id.export_path_value)
        chooseFolderButton = findViewById(R.id.choose_folder_button)
        defaultFolderButton = findViewById(R.id.default_folder_button)
        moveRecordingsButton = findViewById(R.id.move_recordings_button)
        batteryOptimizationButton = findViewById(R.id.battery_optimization_button)
        batteryOptimizationStatus = findViewById(R.id.battery_optimization_status)
        persistentBufferRow = findViewById(R.id.persistent_buffer_row)
        aggressiveRestartRow = findViewById(R.id.aggressive_restart_row)
        wakeLockRow = findViewById(R.id.wake_lock_row)
        persistentBufferSwitch = findViewById(R.id.persistent_buffer_switch)
        aggressiveRestartSwitch = findViewById(R.id.aggressive_restart_switch)
        wakeLockSwitch = findViewById(R.id.wake_lock_switch)
        undoButton = findViewById(R.id.settings_undo_button)
        applyButton = findViewById(R.id.settings_apply_button)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges) {
                    saveSettingsSilently()
                }
                finish()
                overridePendingTransition(0, 0)
            }
        })

        undoButton.setOnClickListener { restorePreviousSettings() }
        applyButton.setOnClickListener {
            if (persistSettings(showFeedback = false)) {
                finish()
                overridePendingTransition(0, 0)
            }
        }
        chooseFolderButton.setOnClickListener { exportDirectoryLauncher.launch(selectedExportTreeUri) }
        defaultFolderButton.setOnClickListener {
            selectedExportTreeUri = null
            refreshExportDirectoryUi()
            refreshMoveRecordingsAvailability()
            saveCurrentToSnapshot(currentSettings)
            pushUndoState()
        }
        moveRecordingsButton.setOnClickListener { moveExistingRecordings() }
        batteryOptimizationButton.setOnClickListener { openBatteryOptimizationSettings() }
        setupListeners()
        bindUiFromPreferences()
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(Intent(this, TimeTravelService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryOptimizationUi()
    }

    private fun applyWindowInsets(content: View) {
        val start = content.paddingStart
        val top = content.paddingTop
        val end = content.paddingEnd
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(
                left = start + bars.left,
                top = top + bars.top,
                right = end + bars.right,
                bottom = bottom + bars.bottom,
            )
            insets
        }
    }

    private fun installFocusClear(target: View) {
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        target.isClickable = true
        target.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                clearCurrentInputFocus(target)
            }
            false
        }
        target.setOnClickListener {
            clearCurrentInputFocus(target)
        }
    }

    private fun clearCurrentInputFocus(target: View) {
        currentFocus?.let { focused ->
            focused.clearFocus()
            (getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
        target.requestFocus()
    }

    private fun setupListeners() {
        bindSwitchRow(persistentBufferRow, persistentBufferSwitch)
        bindSwitchRow(aggressiveRestartRow, aggressiveRestartSwitch)
        bindSwitchRow(wakeLockRow, wakeLockSwitch)

        retentionTimeInput.setOnClickListener { activateRetentionMode(RetentionMode.TIME) }
        retentionSizeInput.setOnClickListener { activateRetentionMode(RetentionMode.SIZE) }
        retentionTimeInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) activateRetentionMode(RetentionMode.TIME) }
        retentionSizeInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) activateRetentionMode(RetentionMode.SIZE) }

        retentionTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (bindingUi) return
                updateRetentionValuesFromActiveInput()
                refreshRetentionFields(preserveActiveInputs = true)
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        })

        retentionSizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (bindingUi) return
                updateRetentionValuesFromActiveInput()
                refreshRetentionFields(preserveActiveInputs = true)
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        })

        bitrateSlider.addOnChangeListener { _, value, fromUser ->
            if (bindingUi) return@addOnChangeListener
            updateBitrateValueLabel(value.roundToInt())
            if (!fromUser) return@addOnChangeListener
            refreshRetentionFields(preserveActiveInputs = true)
            saveCurrentToSnapshot(currentSettings)
            pushUndoState()
        }

        themeDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(themeDropdown)
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        codecDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(codecDropdown)
                if (capabilityUiReady) {
                    refreshSourceModes(
                        preferredSource = currentSourceMode(),
                        preferredChannelMode = currentChannelMode(),
                        preferredRate = currentSampleRate(),
                    )
                    refreshBitrateField()
                } else {
                    refreshCapabilityUiAsync(resetOriginalSnapshot = false)
                }
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        inputRouteDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(inputRouteDropdown)
                if (capabilityUiReady) {
                    refreshCodecOptions(
                        preferredCodec = currentCodec(),
                        preferredSource = currentSourceMode(),
                        preferredChannelMode = currentChannelMode(),
                        preferredRate = currentSampleRate(),
                        preferredBitrateKbps = currentBitrateKbpsOrNull(),
                    )
                } else {
                    refreshCapabilityUiAsync(resetOriginalSnapshot = false)
                }
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        audioSourceDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(audioSourceDropdown)
                if (capabilityUiReady) {
                    refreshChannelModes(
                        preferredChannelMode = currentChannelMode(),
                        preferredRate = currentSampleRate(),
                    )
                } else {
                    refreshCapabilityUiAsync(resetOriginalSnapshot = false)
                }
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        channelModeDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(channelModeDropdown)
                if (capabilityUiReady) {
                    refreshSampleRates(preferredRate = currentSampleRate())
                    refreshBitrateField()
                } else {
                    refreshCapabilityUiAsync(resetOriginalSnapshot = false)
                }
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        sampleRateDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(sampleRateDropdown)
                refreshBitrateField()
                refreshRetentionFields(preserveActiveInputs = true)
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        persistentBufferSwitch.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        aggressiveRestartSwitch.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        wakeLockSwitch.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
    }

    private fun bindSwitchRow(
        row: View,
        toggle: MaterialSwitch,
    ) {
        row.setOnClickListener {
            toggle.performClick()
        }
    }

    private fun bindUiFromPreferences() {
        bindingUi = true
        capabilityUiReady = false

        val prefs = getRecorderPreferences(this)
        val configuredThemeMode = getConfiguredThemeMode(this)
        val configuredMode = getConfiguredRetentionMode(this)
        val configuredTime = getConfiguredRetentionSeconds(this).toInt()
        val storedSizeBytes = prefs.getLong(
            TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY,
            Runtime.getRuntime().maxMemory() / 4,
        ).coerceAtMost(getRetentionMemoryCapBytes())
        val configuredCodec = getConfiguredOutputCodec(this)
        val configuredRoute = getConfiguredInputRouteMode(this)
        val configuredSource = getConfiguredAudioSourceMode(this)
        val configuredChannelMode = getConfiguredChannelMode(this)
        val configuredRate = prefs.getInt(TimeTravelConfig.SAMPLE_RATE_KEY, standardSampleRates().first())
            .takeIf { it > 0 }
            ?: standardSampleRates().first()
        val configuredBitrateKbps = getConfiguredCodecBitrateKbps(this, configuredCodec, configuredRate, configuredChannelMode.channelCount) ?: 0
        val configuredExportTreeUri = getConfiguredExportTreeUri(this)
        val configuredPersistentBuffer = isDiskBufferCacheEnabled(this)
        val configuredAggressiveRestart = isAggressiveRestartEnabled(this)
        val configuredWakeLock = isWakeLockEnabled(this)

        activeRetentionMode = configuredMode
        retentionTimeSecondsValue = configuredTime
        retentionSizeMbValue = bytesToMegabytes(storedSizeBytes)
        selectedExportTreeUri = configuredExportTreeUri

        availableThemes = AppThemeMode.entries
        setDropdownItems(
            themeDropdown,
            availableThemes.map { getString(it.labelRes) },
            getString(configuredThemeMode.labelRes),
        )

        availableRouteModes = InputRouteMode.entries
        setDropdownItems(
            inputRouteDropdown,
            availableRouteModes.map { getString(it.labelRes) },
            getString(configuredRoute.labelRes),
        )

        availableCodecs = ExportCodec.entries
        setDropdownItems(
            codecDropdown,
            availableCodecs.map { getString(it.labelRes) },
            getString(configuredCodec.labelRes),
        )

        availableSourceModes = AudioSourceMode.availableModes()
        setDropdownItems(
            audioSourceDropdown,
            availableSourceModes.map { getString(it.labelRes) },
            getString(configuredSource.labelRes),
        )

        availableChannelModes = ChannelMode.entries
        setDropdownItems(
            channelModeDropdown,
            availableChannelModes.map { getString(it.labelRes) },
            getString(configuredChannelMode.labelRes),
        )

        availableSampleRates = buildList {
            add(configuredRate)
            addAll(standardSampleRates())
        }.filter { it > 0 }.distinct()
        setDropdownItems(
            sampleRateDropdown,
            availableSampleRates.map(::sampleRateLabel),
            sampleRateLabel(configuredRate),
        )
        sampleRateDropdown.isEnabled = true
        refreshBitrateField(preferredKbps = configuredBitrateKbps)

        persistentBufferSwitch.isChecked = configuredPersistentBuffer
        aggressiveRestartSwitch.isChecked = configuredAggressiveRestart
        wakeLockSwitch.isChecked = configuredWakeLock

        bindingUi = false
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()
        saveCurrentToSnapshot(currentSettings)
        originalSettings.copyFrom(currentSettings)
        hasUnsavedChanges = false
        updateUndoButton(false)
        refreshCapabilityUiAsync(resetOriginalSnapshot = true)
    }

    private fun snapshotCurrentUi(): SettingsSnapshot {
        return SettingsSnapshot().also(::saveCurrentToSnapshot)
    }

    private fun refreshCapabilityUiAsync(resetOriginalSnapshot: Boolean) {
        val generation = ++capabilityRefreshGeneration
        val preferred = snapshotCurrentUi()
        lifecycleScope.launch(Dispatchers.Default) {
            warmRecorderCapabilityCache(applicationContext)
            withContext(Dispatchers.Main) {
                if (generation != capabilityRefreshGeneration || isFinishing || isDestroyed) {
                    return@withContext
                }
                applyResolvedCapabilityUi(preferred, resetOriginalSnapshot)
            }
        }
    }

    private fun applyResolvedCapabilityUi(
        preferred: SettingsSnapshot,
        resetOriginalSnapshot: Boolean,
    ) {
        val shouldResetOriginalSnapshot = resetOriginalSnapshot && !hasUnsavedChanges
        bindingUi = true
        availableRouteModes = supportedInputRouteModes(this)
        val selectedRoute = preferred.route?.takeIf { it in availableRouteModes } ?: availableRouteModes.first()
        setDropdownItems(
            inputRouteDropdown,
            availableRouteModes.map { getString(it.labelRes) },
            getString(selectedRoute.labelRes),
        )
        refreshCodecOptions(
            preferredCodec = preferred.codec,
            preferredSource = preferred.source,
            preferredChannelMode = preferred.channelMode,
            preferredRate = preferred.sampleRate,
            preferredBitrateKbps = preferred.bitrateKbps,
        )
        bindingUi = false
        capabilityUiReady = true
        refreshRetentionFields(preserveActiveInputs = true)
        refreshMoveRecordingsAvailability()
        saveCurrentToSnapshot(currentSettings)
        if (shouldResetOriginalSnapshot) {
            originalSettings.copyFrom(currentSettings)
            hasUnsavedChanges = false
            updateUndoButton(false)
        } else {
            pushUndoState()
        }
    }

    private fun saveCurrentToSnapshot(snapshot: SettingsSnapshot) {
        snapshot.themeMode = currentThemeMode()
        snapshot.retentionMode = activeRetentionMode
        snapshot.retentionTime = retentionTimeSecondsValue
        snapshot.retentionSizeMb = retentionSizeMbValue
        snapshot.codec = currentCodec()
        snapshot.bitrateKbps = effectiveCodecBitrateKbps() ?: 0
        snapshot.source = currentSourceMode()
        snapshot.channelMode = currentChannelMode()
        snapshot.route = currentRouteMode()
        snapshot.sampleRate = currentSampleRate() ?: 0
        snapshot.exportDirectoryUri = selectedExportTreeUri?.toString()
        snapshot.persistentBufferEnabled = persistentBufferSwitch.isChecked
        snapshot.aggressiveRestartEnabled = aggressiveRestartSwitch.isChecked
        snapshot.wakeLockEnabled = wakeLockSwitch.isChecked
    }

    private fun pushUndoState() {
        hasUnsavedChanges = originalSettings != currentSettings
        updateUndoButton(hasUnsavedChanges)
    }

    private fun restorePreviousSettings() {
        if (!hasUnsavedChanges) return
        val previous = originalSettings

        bindingUi = true
        activeRetentionMode = previous.retentionMode
        retentionTimeSecondsValue = previous.retentionTime
        retentionSizeMbValue = previous.retentionSizeMb
        selectedExportTreeUri = previous.exportDirectoryUri?.let(Uri::parse)
        persistentBufferSwitch.isChecked = previous.persistentBufferEnabled
        aggressiveRestartSwitch.isChecked = previous.aggressiveRestartEnabled
        wakeLockSwitch.isChecked = previous.wakeLockEnabled

        setDropdownItems(
            themeDropdown,
            availableThemes.map { getString(it.labelRes) },
            getString(previous.themeMode.labelRes),
        )
        setDropdownItems(
            codecDropdown,
            availableCodecs.map { getString(it.labelRes) },
            getString(previous.codec?.labelRes ?: availableCodecs.first().labelRes),
        )
        setDropdownItems(
            inputRouteDropdown,
            availableRouteModes.map { getString(it.labelRes) },
            getString(previous.route?.labelRes ?: availableRouteModes.first().labelRes),
        )

        if (capabilityUiReady) {
            refreshCodecOptions(
                preferredCodec = previous.codec,
                preferredSource = previous.source,
                preferredChannelMode = previous.channelMode,
                preferredRate = previous.sampleRate,
                preferredBitrateKbps = previous.bitrateKbps,
            )
        } else {
            refreshBitrateField(preferredKbps = previous.bitrateKbps)
        }

        bindingUi = false
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
        refreshBatteryOptimizationUi()

        currentSettings.copyFrom(previous)
        hasUnsavedChanges = false
        updateUndoButton(false)
        if (!capabilityUiReady) {
            refreshCapabilityUiAsync(resetOriginalSnapshot = false)
        }
    }

    private fun refreshCodecOptions(
        preferredCodec: ExportCodec? = null,
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
        preferredBitrateKbps: Int? = null,
    ) {
        availableCodecs = supportedCodecs()
        val selectedCodec = preferredCodec?.takeIf { it in availableCodecs } ?: availableCodecs.first()
        setDropdownItems(
            codecDropdown,
            availableCodecs.map { getString(it.labelRes) },
            getString(selectedCodec.labelRes),
        )
        refreshSourceModes(preferredSource, preferredChannelMode, preferredRate)
        refreshBitrateField(preferredKbps = preferredBitrateKbps)
    }

    private fun refreshSourceModes(
        preferredSource: AudioSourceMode? = null,
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        val codec = currentCodec()
        val route = currentRouteMode()
        availableSourceModes = supportedAudioSourceModes(this, route, codec)
        val selectedSource = preferredSource?.takeIf { it in availableSourceModes } ?: availableSourceModes.first()
        setDropdownItems(
            audioSourceDropdown,
            availableSourceModes.map { getString(it.labelRes) },
            getString(selectedSource.labelRes),
        )
        refreshChannelModes(preferredChannelMode, preferredRate)
    }

    private fun refreshChannelModes(
        preferredChannelMode: ChannelMode? = null,
        preferredRate: Int? = null,
    ) {
        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        availableChannelModes = supportedChannelModes(this, source, route, codec)
        val selectedChannelMode =
            preferredChannelMode?.takeIf { it in availableChannelModes } ?: availableChannelModes.first()
        setDropdownItems(
            channelModeDropdown,
            availableChannelModes.map { getString(it.labelRes) },
            getString(selectedChannelMode.labelRes),
        )
        refreshSampleRates(preferredRate)
    }

    private fun refreshSampleRates(preferredRate: Int? = null) {
        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        val channelMode = currentChannelMode()
        availableSampleRates = supportedSampleRates(this, source, route, codec, channelMode)

        sampleRateLayout.error = if (availableSampleRates.isEmpty()) getString(R.string.unsupported_config_message) else null

        if (availableSampleRates.isEmpty()) {
            setDropdownItems(sampleRateDropdown, emptyList(), "")
            sampleRateDropdown.isEnabled = false
        } else {
            sampleRateDropdown.isEnabled = true
            val selectedRate = orderSampleRatesByPreference(availableSampleRates, preferredRate ?: 0).first()
            setDropdownItems(
                sampleRateDropdown,
                availableSampleRates.map(::sampleRateLabel),
                sampleRateLabel(selectedRate),
            )
        }

        refreshRetentionFields(preserveActiveInputs = true)
    }

    private fun refreshBitrateField(preferredKbps: Int? = null) {
        val codec = currentCodec()
        val range = codecBitrateRangeKbps(codec)
        bitrateGroup.isVisible = range != null
        if (range == null) {
            return
        }

        val sampleRate = currentSampleRate() ?: availableSampleRates.firstOrNull() ?: 48_000
        val defaultBitrateKbps = defaultCodecBitrateKbps(codec, sampleRate, currentChannelMode().channelCount) ?: range.first
        val currentBitrateKbps = currentBitrateKbpsOrNull()
        val resolvedBitrateKbps = (preferredKbps ?: currentBitrateKbps ?: defaultBitrateKbps).coerceIn(range)

        val previousBindingUi = bindingUi
        bindingUi = true
        bitrateSlider.valueFrom = range.first.toFloat()
        bitrateSlider.valueTo = range.last.toFloat()
        bitrateSlider.stepSize = 0f
        bitrateSlider.value = resolvedBitrateKbps.toFloat()
        updateBitrateValueLabel(resolvedBitrateKbps)
        bindingUi = previousBindingUi
        refreshRetentionFields(preserveActiveInputs = true)
    }

    private fun activateRetentionMode(mode: RetentionMode) {
        if (bindingUi || activeRetentionMode == mode) return
        updateRetentionValuesFromActiveInput()
        activeRetentionMode = mode
        refreshRetentionFields()
        saveCurrentToSnapshot(currentSettings)
        pushUndoState()
    }

    private fun updateRetentionValuesFromActiveInput() {
        when (activeRetentionMode) {
            RetentionMode.TIME -> {
                parseDurationInput(retentionTimeInput.text?.toString().orEmpty())?.let { retentionTimeSecondsValue = it }
            }

            RetentionMode.SIZE -> {
                retentionSizeInput.text?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let {
                    retentionSizeMbValue = it
                }
            }
        }
    }

    private fun refreshRetentionFields(preserveActiveInputs: Boolean = false) {
        val sampleRate = currentSampleRate()
        if (sampleRate == null || sampleRate <= 0) {
            val previousBindingUi = bindingUi
            bindingUi = true
            if (!preserveActiveInputs) {
                retentionTimeInput.setText("")
                retentionSizeInput.setText("")
            }
            retentionTimeLayout.prefixText = null
            retentionSizeLayout.prefixText = null
            bindingUi = previousBindingUi
            return
        }

        val codec = currentCodec()
        val channelMode = currentChannelMode()
        val bitrateKbps = effectiveCodecBitrateKbps()
        val estimatePrefix = if (codec == ExportCodec.WAV) "=" else "~"
        val estimatedSizeMb = bytesToMegabytes(
            estimateExportSizeBytes(codec, sampleRate, channelMode.channelCount, retentionTimeSecondsValue.toLong(), bitrateKbps),
        ).toString()
        val estimatedDuration = formatDurationInput(
            estimateExportDurationSeconds(codec, sampleRate, channelMode.channelCount, megabytesToBytes(retentionSizeMbValue), bitrateKbps).toInt(),
        )

        val previousBindingUi = bindingUi
        bindingUi = true
        if (activeRetentionMode == RetentionMode.TIME) {
            retentionTimeLayout.prefixText = null
            retentionSizeLayout.prefixText = estimatePrefix
            if (!preserveActiveInputs) {
                retentionTimeInput.setText(formatDurationInput(retentionTimeSecondsValue))
            }
            retentionSizeInput.setText(estimatedSizeMb)
        } else {
            retentionTimeLayout.prefixText = estimatePrefix
            retentionSizeLayout.prefixText = null
            retentionTimeInput.setText(estimatedDuration)
            if (!preserveActiveInputs) {
                retentionSizeInput.setText(retentionSizeMbValue.toString())
            }
        }
        retentionTimeLayout.alpha = if (activeRetentionMode == RetentionMode.TIME) 1f else 0.82f
        retentionSizeLayout.alpha = if (activeRetentionMode == RetentionMode.SIZE) 1f else 0.82f
        bindingUi = previousBindingUi
    }

    private fun persistSettings(showFeedback: Boolean): Boolean {
        clearErrors()

        val themeMode = currentThemeMode()
        val codec = currentCodec()
        val channelMode = currentChannelMode()
        val route = currentRouteMode()
        val source = currentSourceMode()
        val sampleRate = currentSampleRate()
        val bitrateKbps = currentBitrateKbpsOrNull()
        val persistentBufferEnabled = persistentBufferSwitch.isChecked
        val aggressiveRestartEnabled = aggressiveRestartSwitch.isChecked
        val wakeLockEnabled = wakeLockSwitch.isChecked

        if (sampleRate == null) {
            sampleRateLayout.error = getString(R.string.unsupported_config_message)
            if (showFeedback) {
                Toast.makeText(this, R.string.unsupported_config_message, Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val retentionTime = if (activeRetentionMode == RetentionMode.TIME) {
            parseDurationInput(retentionTimeInput.text?.toString().orEmpty())
        } else {
            retentionTimeSecondsValue
        }
        if (retentionTime == null || retentionTime <= 0) {
            retentionTimeLayout.error = getString(R.string.retention_time_invalid)
            return false
        }

        val sizeMb = if (activeRetentionMode == RetentionMode.SIZE) {
            retentionSizeInput.text?.toString()?.trim()?.toLongOrNull()
        } else {
            retentionSizeMbValue
        }
        if (sizeMb == null || sizeMb <= 0) {
            retentionSizeLayout.error = getString(R.string.custom_memory_size_invalid)
            return false
        }

        val bitrateRange = codecBitrateRangeKbps(codec)
        if (bitrateRange != null && (bitrateKbps == null || bitrateKbps !in bitrateRange)) {
            Toast.makeText(this, getString(R.string.codec_bitrate_invalid, bitrateRange.first, bitrateRange.last), Toast.LENGTH_SHORT).show()
            return false
        }

        retentionTimeSecondsValue = retentionTime
        retentionSizeMbValue = sizeMb
        val sizeBytes = megabytesToBytes(sizeMb)

        setConfiguredThemeMode(this, themeMode)
        getRecorderPreferences(this).edit()
            .putString(TimeTravelConfig.RETENTION_MODE_KEY, activeRetentionMode.prefValue)
            .putLong(TimeTravelConfig.RETENTION_SECONDS_KEY, retentionTime.toLong())
            .putLong(TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY, sizeBytes)
            .putString(TimeTravelConfig.OUTPUT_CODEC_KEY, codec.prefValue)
            .putInt(TimeTravelConfig.OUTPUT_BITRATE_KBPS_KEY, bitrateKbps ?: (effectiveCodecBitrateKbps() ?: 0))
            .putInt(TimeTravelConfig.AUDIO_SOURCE_KEY, source.sourceValue)
            .putString(TimeTravelConfig.CHANNEL_MODE_KEY, channelMode.prefValue)
            .putString(TimeTravelConfig.INPUT_ROUTE_KEY, route.prefValue)
            .putInt(TimeTravelConfig.SAMPLE_RATE_KEY, sampleRate)
            .putBoolean(TimeTravelConfig.BUFFER_DISK_CACHE_ENABLED_KEY, persistentBufferEnabled)
            .putBoolean(TimeTravelConfig.AGGRESSIVE_RESTART_ENABLED_KEY, aggressiveRestartEnabled)
            .putBoolean(TimeTravelConfig.WAKE_LOCK_ENABLED_KEY, wakeLockEnabled)
            .apply()
        setConfiguredExportTreeUri(this, selectedExportTreeUri)
        applyConfiguredThemeMode(this)

        bindUiFromPreferences()

        val currentService = service
        if (currentService == null) {
            if (showFeedback) {
                Toast.makeText(this, R.string.settings_saved_next_start, Toast.LENGTH_SHORT).show()
            }
            return true
        }

        when (currentService.applyUpdatedPreferences()) {
            TimeTravelService.ApplySettingsResult.BLOCKED_RECORDING -> {
                if (showFeedback) {
                    Toast.makeText(this, R.string.settings_apply_blocked_recording, Toast.LENGTH_SHORT).show()
                }
            }
            TimeTravelService.ApplySettingsResult.APPLIED_NOW -> {
                if (showFeedback) {
                    Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                }
            }
            TimeTravelService.ApplySettingsResult.DEFERRED_UNTIL_RESTART -> {
                if (showFeedback) {
                    Toast.makeText(this, R.string.settings_saved_deferred_input, Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun clearErrors() {
        retentionTimeLayout.error = null
        retentionSizeLayout.error = null
        themeLayout.error = null
        sampleRateLayout.error = null
        codecLayout.error = null
        audioSourceLayout.error = null
        channelModeLayout.error = null
        inputRouteLayout.error = null
    }

    private fun setDropdownItems(
        view: MaterialAutoCompleteTextView,
        items: List<String>,
        selectedValue: String,
    ) {
        val adapter = view.adapter as? DropdownHighlightAdapter
        if (adapter == null) {
            view.setAdapter(DropdownHighlightAdapter(this, items, selectedValue))
        } else {
            adapter.replaceItems(items, selectedValue)
        }
        view.setText(selectedValue, false)
    }

    private fun updateDropdownSelection(view: MaterialAutoCompleteTextView) {
        (view.adapter as? DropdownHighlightAdapter)?.updateSelectedValue(view.text?.toString().orEmpty())
    }

    private fun currentThemeMode(): AppThemeMode {
        val selected = themeDropdown.text?.toString().orEmpty()
        return availableThemes.firstOrNull { getString(it.labelRes) == selected } ?: AppThemeMode.SYSTEM
    }

    private fun currentCodec(): ExportCodec {
        val selected = codecDropdown.text?.toString().orEmpty()
        return availableCodecs.firstOrNull { getString(it.labelRes) == selected } ?: availableCodecs.first()
    }

    private fun currentBitrateKbpsOrNull(): Int? {
        val range = codecBitrateRangeKbps(currentCodec()) ?: return null
        return bitrateSlider.value.roundToInt().coerceIn(range)
    }

    private fun updateBitrateValueLabel(valueKbps: Int) {
        bitrateValue.text = getString(R.string.codec_bitrate_value, valueKbps)
    }

    private fun effectiveCodecBitrateKbps(): Int? {
        val codec = currentCodec()
        val range = codecBitrateRangeKbps(codec) ?: return defaultCodecBitrateKbps(
            codec,
            currentSampleRate() ?: availableSampleRates.firstOrNull() ?: 48_000,
            currentChannelMode().channelCount,
        )
        val sampleRate = currentSampleRate() ?: availableSampleRates.firstOrNull() ?: 48_000
        return currentBitrateKbpsOrNull()
            ?.coerceIn(range)
            ?: getConfiguredCodecBitrateKbps(this, codec, sampleRate, currentChannelMode().channelCount)
    }

    private fun currentRouteMode(): InputRouteMode {
        val selected = inputRouteDropdown.text?.toString().orEmpty()
        return availableRouteModes.firstOrNull { getString(it.labelRes) == selected } ?: availableRouteModes.first()
    }

    private fun currentChannelMode(): ChannelMode {
        val selected = channelModeDropdown.text?.toString().orEmpty()
        return availableChannelModes.firstOrNull { getString(it.labelRes) == selected } ?: availableChannelModes.first()
    }

    private fun currentSourceMode(): AudioSourceMode {
        val selected = audioSourceDropdown.text?.toString().orEmpty()
        return availableSourceModes.firstOrNull { getString(it.labelRes) == selected } ?: availableSourceModes.first()
    }

    private fun currentSampleRate(): Int? {
        val selected = sampleRateDropdown.text?.toString().orEmpty()
        return availableSampleRates.firstOrNull { sampleRateLabel(it) == selected }
    }

    private fun refreshExportDirectoryUi() {
        bindingUi = true
        exportPathValue.text = describeOutputDirectory(this, selectedExportTreeUri)
        defaultFolderButton.isEnabled = selectedExportTreeUri != null
        bindingUi = false
    }

    private fun refreshBatteryOptimizationUi() {
        val unrestricted = isIgnoringBatteryOptimizations(this)
        batteryOptimizationStatus.text = getString(
            if (unrestricted) R.string.battery_optimization_status_ok else R.string.battery_optimization_status_limited,
        )
        batteryOptimizationButton.isVisible = !unrestricted
    }

    private fun openBatteryOptimizationSettings() {
        val intents = buildList {
            if (!isIgnoringBatteryOptimizations(this@SettingsActivity)) {
                add(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            }
            add(
                Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL").apply {
                    data = Uri.parse("package:$packageName")
                    putExtra("package_name", packageName)
                    putExtra("packageName", packageName)
                },
            )
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                },
            )
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }

        val launched = intents.any { intent ->
            if (intent.resolveActivity(packageManager) == null) {
                return@any false
            }
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }

        if (!launched) {
            Toast.makeText(this, R.string.no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshMoveRecordingsAvailability() {
        val generation = ++moveAvailabilityGeneration
        moveRecordingsButton.isEnabled = false
        moveRecordingsButton.alpha = 0.38f
        lifecycleScope.launch {
            val canMove = RecordingRepository.hasMovableRecordings(
                this@SettingsActivity,
                getOutputDirectoryId(this@SettingsActivity, selectedExportTreeUri),
            )
            if (generation != moveAvailabilityGeneration) {
                return@launch
            }
            moveRecordingsButton.isEnabled = canMove
            moveRecordingsButton.alpha = if (canMove) 1f else 0.38f
        }
    }

    private fun moveExistingRecordings() {
        if (!persistSettings(showFeedback = false)) {
            return
        }
        moveRecordingsButton.isEnabled = false
        moveRecordingsButton.alpha = 0.38f
        lifecycleScope.launch {
            val result = RecordingRepository.moveAllToConfiguredDirectory(this@SettingsActivity)
            val message = when {
                result.moved == 0 && result.removedMissing == 0 -> getString(R.string.move_recordings_none)
                result.removedMissing > 0 -> {
                    val movedMessage = resources.getQuantityString(R.plurals.move_recordings_done, result.moved, result.moved)
                    val removedMessage = resources.getQuantityString(
                        R.plurals.move_recordings_removed_missing,
                        result.removedMissing,
                        result.removedMissing,
                    )
                    "$movedMessage $removedMessage"
                }
                else -> resources.getQuantityString(R.plurals.move_recordings_done, result.moved, result.moved)
            }
            refreshMoveRecordingsAvailability()
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bytesToMegabytes(bytes: Long): Long {
        return maxOf(1L, kotlin.math.ceil(bytes.toDouble() / BYTES_IN_MEGABYTE).toLong())
    }

    private fun megabytesToBytes(memoryInMegabytes: Long): Long {
        if (memoryInMegabytes > Long.MAX_VALUE / BYTES_IN_MEGABYTE) {
            return getRetentionMemoryCapBytes()
        }
        return (memoryInMegabytes * BYTES_IN_MEGABYTE).coerceAtMost(getRetentionMemoryCapBytes())
    }

    private companion object {
        const val BYTES_IN_MEGABYTE = 1024L * 1024L
    }

    private fun saveSettingsSilently() {
        persistSettings(showFeedback = false)
    }

    private fun updateUndoButton(enabled: Boolean) {
        undoButton.isEnabled = enabled
        undoButton.alpha = if (enabled) 1f else 0.38f
    }
}

private class DropdownHighlightAdapter(
    context: Context,
    items: List<String>,
    selectedValue: String,
) : ArrayAdapter<String>(context, R.layout.item_dropdown_option, android.R.id.text1, items.toMutableList()) {
    private var selectedValue = selectedValue
    private val edgePaddingPx = (context.resources.displayMetrics.density * 6f).toInt()

    fun replaceItems(
        items: List<String>,
        selectedValue: String,
    ) {
        clear()
        addAll(items)
        this.selectedValue = selectedValue
        notifyDataSetChanged()
    }

    fun updateSelectedValue(value: String) {
        selectedValue = value
        notifyDataSetChanged()
    }

    override fun getView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        return super.getView(position, convertView, parent).also {
            bindSelectionState(it, getItem(position).orEmpty())
        }
    }

    override fun getDropDownView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        return super.getDropDownView(position, convertView, parent).also {
            bindSelectionState(it, getItem(position).orEmpty())
        }
    }

    private fun bindSelectionState(
        view: View,
        value: String,
    ) {
        val label = view.findViewById<TextView>(android.R.id.text1)
        val active = value == selectedValue
        val topPadding = if (positionOf(value) == 0) edgePaddingPx else 0
        val bottomPadding = if (positionOf(value) == count - 1) edgePaddingPx else 0
        view.setPaddingRelative(0, topPadding, 0, bottomPadding)
        view.isActivated = active
        view.isSelected = active
        label.isActivated = active
        label.isSelected = active
    }

    private fun positionOf(value: String): Int = (0 until count).firstOrNull { getItem(it) == value } ?: -1
}
