package app.smallthingz.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class SettingsActivity : AppCompatActivity() {
    private lateinit var themeLayout: TextInputLayout
    private lateinit var retentionTimeLayout: TextInputLayout
    private lateinit var retentionSizeLayout: TextInputLayout
    private lateinit var formatLayout: TextInputLayout
    private lateinit var codecLayout: TextInputLayout
    private lateinit var sampleRateLayout: TextInputLayout
    private lateinit var audioSourceLayout: TextInputLayout
    private lateinit var channelModeLayout: TextInputLayout
    private lateinit var inputRouteLayout: TextInputLayout
    private lateinit var historyChunkLayout: TextInputLayout
    private lateinit var autoMergeLayout: TextInputLayout
    private lateinit var autoMergeValueLayout: TextInputLayout
    private lateinit var bitrateGroup: View
    private lateinit var bitrateValue: TextView
    private lateinit var bitrateSlider: Slider
    private lateinit var themeDropdown: MaterialAutoCompleteTextView
    private lateinit var formatDropdown: MaterialAutoCompleteTextView
    private lateinit var codecDropdown: MaterialAutoCompleteTextView
    private lateinit var sampleRateDropdown: MaterialAutoCompleteTextView
    private lateinit var audioSourceDropdown: MaterialAutoCompleteTextView
    private lateinit var channelModeDropdown: MaterialAutoCompleteTextView
    private lateinit var inputRouteDropdown: MaterialAutoCompleteTextView
    private lateinit var autoMergeDropdown: MaterialAutoCompleteTextView
    private lateinit var retentionTimeInput: EditText
    private lateinit var retentionSizeInput: EditText
    private lateinit var historyChunkInput: EditText
    private lateinit var autoMergeValueInput: EditText
    private lateinit var exportPathValue: TextView
    private lateinit var chooseFolderButton: ImageButton
    private lateinit var defaultFolderButton: ImageButton
    private lateinit var moveRecordingsButton: MaterialButton
    private lateinit var batteryOptimizationButton: MaterialButton
    private lateinit var batteryOptimizationStatus: TextView
    private lateinit var persistentBufferRow: View
    private lateinit var aggressiveRestartRow: View
    private lateinit var wakeLockRow: View
    private lateinit var autoMergeEagerRow: View
    private lateinit var debugDivider: View
    private lateinit var debugTitle: View
    private lateinit var debugChunksRow: View
    private lateinit var persistentBufferSwitch: MaterialSwitch
    private lateinit var aggressiveRestartSwitch: MaterialSwitch
    private lateinit var wakeLockSwitch: MaterialSwitch
    private lateinit var autoMergeEagerSwitch: MaterialSwitch
    private lateinit var debugChunksSwitch: MaterialSwitch
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
    private var availableFormats: List<ExportFormat> = emptyList()
    private var availableCodecs: List<ExportCodec> = emptyList()
    private var availableSourceModes: List<AudioSourceMode> = emptyList()
    private var availableChannelModes: List<ChannelMode> = emptyList()
    private var availableRouteModes: List<InputRouteMode> = emptyList()
    private var availableAutoMergeModes: List<AutoMergeMode> = emptyList()
    private var availableSampleRates: List<Int> = emptyList()
    private var activeRetentionMode = RetentionMode.TIME
    private var retentionTimeSecondsValue = 0
    private var retentionSizeMbValue = 0.0
    private var historyChunkSecondsValue = 0
    private var activeAutoMergeMode = AutoMergeMode.RATIO
    private var autoMergeDivisorValue = defaultAutoMergeDivisor()
    private var autoMergeCustomSecondsValue = defaultAutoMergeCustomSeconds()
    private var autoMergeCustomSizeMibValue = defaultAutoMergeCustomSizeMib()
    private var autoMergeEagerEnabled = true
    private var selectedExportTreeUri: Uri? = null

    data class SettingsSnapshot(
        var themeMode: AppThemeMode = AppThemeMode.SYSTEM,
        var retentionMode: RetentionMode = RetentionMode.TIME,
        var retentionTime: Int = 0,
        var retentionSizeMb: Double = 0.0,
        var format: ExportFormat? = null,
        var codec: ExportCodec? = null,
        var bitrateKbps: Int = 0,
        var source: AudioSourceMode? = null,
        var channelMode: ChannelMode? = null,
        var route: InputRouteMode? = null,
        var sampleRate: Int = 0,
        var historyChunkSeconds: Int = 10,
        var autoMergeMode: AutoMergeMode = AutoMergeMode.RATIO,
        var autoMergeDivisor: Int = defaultAutoMergeDivisor(),
        var autoMergeCustomSeconds: Int = defaultAutoMergeCustomSeconds(),
        var autoMergeCustomSizeMib: Double = defaultAutoMergeCustomSizeMib(),
        var autoMergeEagerEnabled: Boolean = true,
        var exportDirectoryUri: String? = null,
        var persistentBufferEnabled: Boolean = true,
        var aggressiveRestartEnabled: Boolean = true,
        var wakeLockEnabled: Boolean = false,
        var debugChunksTabEnabled: Boolean = false,
    ) {
        fun copyFrom(other: SettingsSnapshot) {
            themeMode = other.themeMode
            retentionMode = other.retentionMode
            retentionTime = other.retentionTime
            retentionSizeMb = other.retentionSizeMb
            format = other.format
            codec = other.codec
            bitrateKbps = other.bitrateKbps
            source = other.source
            channelMode = other.channelMode
            route = other.route
            sampleRate = other.sampleRate
            historyChunkSeconds = other.historyChunkSeconds
            autoMergeMode = other.autoMergeMode
            autoMergeDivisor = other.autoMergeDivisor
            autoMergeCustomSeconds = other.autoMergeCustomSeconds
            autoMergeCustomSizeMib = other.autoMergeCustomSizeMib
            autoMergeEagerEnabled = other.autoMergeEagerEnabled
            exportDirectoryUri = other.exportDirectoryUri
            persistentBufferEnabled = other.persistentBufferEnabled
            aggressiveRestartEnabled = other.aggressiveRestartEnabled
            wakeLockEnabled = other.wakeLockEnabled
            debugChunksTabEnabled = other.debugChunksTabEnabled
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
        applyPhonePortraitOnly()
        applyConfiguredThemeMode(this)
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        window.setWindowAnimations(0)
        applyTimeTravelSystemBars()
        setContentView(R.layout.activity_settings)

        val root = findViewById<View>(R.id.settings_layout)
        applyWindowInsets(root)
        installFocusClear(root)

        themeLayout = findViewById(R.id.theme_layout)
        retentionTimeLayout = findViewById(R.id.retention_time_layout)
        retentionSizeLayout = findViewById(R.id.retention_size_layout)
        formatLayout = findViewById(R.id.format_layout)
        codecLayout = findViewById(R.id.codec_layout)
        sampleRateLayout = findViewById(R.id.sample_rate_layout)
        audioSourceLayout = findViewById(R.id.audio_source_layout)
        channelModeLayout = findViewById(R.id.channel_mode_layout)
        inputRouteLayout = findViewById(R.id.input_route_layout)
        historyChunkLayout = findViewById(R.id.history_chunk_layout)
        autoMergeLayout = findViewById(R.id.auto_merge_layout)
        autoMergeValueLayout = findViewById(R.id.auto_merge_value_layout)
        bitrateGroup = findViewById(R.id.bitrate_group)
        bitrateValue = findViewById(R.id.bitrate_value)
        bitrateSlider = findViewById(R.id.bitrate_slider)
        themeDropdown = findViewById(R.id.theme_dropdown)
        formatDropdown = findViewById(R.id.format_dropdown)
        codecDropdown = findViewById(R.id.codec_dropdown)
        sampleRateDropdown = findViewById(R.id.sample_rate_dropdown)
        audioSourceDropdown = findViewById(R.id.audio_source_dropdown)
        channelModeDropdown = findViewById(R.id.channel_mode_dropdown)
        inputRouteDropdown = findViewById(R.id.input_route_dropdown)
        autoMergeDropdown = findViewById(R.id.auto_merge_dropdown)
        retentionTimeInput = findViewById(R.id.retention_time_input)
        retentionSizeInput = findViewById(R.id.retention_size_input)
        historyChunkInput = findViewById(R.id.history_chunk_input)
        autoMergeValueInput = findViewById(R.id.auto_merge_value_input)
        exportPathValue = findViewById(R.id.export_path_value)
        chooseFolderButton = findViewById(R.id.choose_folder_button)
        defaultFolderButton = findViewById(R.id.default_folder_button)
        moveRecordingsButton = findViewById(R.id.move_recordings_button)
        batteryOptimizationButton = findViewById(R.id.battery_optimization_button)
        batteryOptimizationStatus = findViewById(R.id.battery_optimization_status)
        persistentBufferRow = findViewById(R.id.persistent_buffer_row)
        aggressiveRestartRow = findViewById(R.id.aggressive_restart_row)
        wakeLockRow = findViewById(R.id.wake_lock_row)
        autoMergeEagerRow = findViewById(R.id.auto_merge_eager_row)
        debugDivider = findViewById(R.id.debug_divider)
        debugTitle = findViewById(R.id.debug_title)
        debugChunksRow = findViewById(R.id.debug_chunks_row)
        persistentBufferSwitch = findViewById(R.id.persistent_buffer_switch)
        aggressiveRestartSwitch = findViewById(R.id.aggressive_restart_switch)
        wakeLockSwitch = findViewById(R.id.wake_lock_switch)
        autoMergeEagerSwitch = findViewById(R.id.auto_merge_eager_switch)
        debugChunksSwitch = findViewById(R.id.debug_chunks_switch)
        undoButton = findViewById(R.id.settings_undo_button)
        applyButton = findViewById(R.id.settings_apply_button)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges) {
                    persistSettings(showFeedback = false)
                }
                finish()
                applyNoAnimationCloseTransition()
            }
        })

        undoButton.setOnClickListener { restorePreviousSettings() }
        applyButton.setOnClickListener {
            if (!hasUnsavedChanges) {
                finish()
                applyNoAnimationCloseTransition()
            } else if (persistSettings(showFeedback = false)) {
                finish()
                applyNoAnimationCloseTransition()
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
        debugDivider.isVisible = true
        debugTitle.isVisible = true
        debugChunksRow.isVisible = true
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
        target.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                clearCurrentInputFocus(target)
            }
            false
        }
    }

    private fun clearCurrentInputFocus(target: View) {
        currentFocus?.let { focused ->
            focused.clearFocus()
            (getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(focused.windowToken, 0)
        }
    }

    private fun setupListeners() {
        bindSwitchRow(persistentBufferRow, persistentBufferSwitch)
        bindSwitchRow(aggressiveRestartRow, aggressiveRestartSwitch)
        bindSwitchRow(wakeLockRow, wakeLockSwitch)
        bindSwitchRow(autoMergeEagerRow, autoMergeEagerSwitch)
        bindSwitchRow(debugChunksRow, debugChunksSwitch)

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
        formatDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(formatDropdown)
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
        historyChunkInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (bindingUi) return
                parseDurationInput(historyChunkInput.text?.toString().orEmpty())?.let {
                    historyChunkSecondsValue = it
                }
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        })
        autoMergeDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                updateDropdownSelection(autoMergeDropdown)
                activeAutoMergeMode = currentAutoMergeMode()
                refreshAutoMergeValueUi()
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        autoMergeValueInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (bindingUi) return
                when (activeAutoMergeMode) {
                    AutoMergeMode.OFF -> Unit
                    AutoMergeMode.RATIO -> autoMergeValueInput.text?.toString()?.trim()?.toIntOrNull()?.let {
                        autoMergeDivisorValue = it
                    }
                    AutoMergeMode.CUSTOM_TIME -> parseDurationInput(autoMergeValueInput.text?.toString().orEmpty())?.let {
                        autoMergeCustomSecondsValue = it
                    }
                    AutoMergeMode.CUSTOM_SIZE -> parseRetentionSizeMib(autoMergeValueInput.text?.toString().orEmpty())?.let {
                        autoMergeCustomSizeMibValue = it
                    }
                }
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        })
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
        autoMergeEagerSwitch.setOnCheckedChangeListener { _, _ ->
            if (!bindingUi) {
                autoMergeEagerEnabled = autoMergeEagerSwitch.isChecked
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        debugChunksSwitch.setOnCheckedChangeListener { _, _ ->
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
            512L * BYTES_IN_MEGABYTE,
        )
        val configuredFormat = getConfiguredOutputFormat(this)
        val configuredCodec = getConfiguredOutputCodec(this)
        val configuredRoute = getConfiguredInputRouteMode(this)
        val configuredSource = getConfiguredAudioSourceMode(this)
        val configuredChannelMode = getConfiguredChannelMode(this)
        val configuredRate = prefs.getInt(
            TimeTravelConfig.SAMPLE_RATE_KEY,
            getPreferredSampleRate(
                this,
                configuredSource,
                configuredRoute,
                configuredFormat,
                configuredCodec,
                configuredChannelMode,
            ),
        )
            .takeIf { it > 0 }
            ?: getPreferredSampleRate(
                this,
                configuredSource,
                configuredRoute,
                configuredFormat,
                configuredCodec,
                configuredChannelMode,
            )
        val configuredBitrateKbps = getConfiguredCodecBitrateKbps(this, configuredCodec, configuredRate, configuredChannelMode.channelCount) ?: 0
        val configuredHistoryChunkSeconds = getConfiguredHistoryChunkSeconds(this)
        val configuredAutoMergeMode = getConfiguredAutoMergeMode(this)
        val configuredAutoMergeDivisor = getConfiguredAutoMergeDivisor(this)
        val configuredAutoMergeCustomSeconds = getConfiguredAutoMergeCustomSeconds(this)
        val configuredAutoMergeCustomSizeMib = getConfiguredAutoMergeCustomSizeMib(this)
        val configuredAutoMergeEagerEnabled = isConfiguredAutoMergeEagerEnabled(this)
        val configuredExportTreeUri = getConfiguredExportTreeUri(this)
        val configuredPersistentBuffer = isDiskBufferCacheEnabled(this)
        val configuredAggressiveRestart = isAggressiveRestartEnabled(this)
        val configuredWakeLock = isWakeLockEnabled(this)
        val configuredDebugChunksTab = isDebugChunksTabEnabled(this)

        activeRetentionMode = configuredMode
        retentionTimeSecondsValue = configuredTime
        retentionSizeMbValue = bytesToMegabytes(storedSizeBytes)
        historyChunkSecondsValue = configuredHistoryChunkSeconds
        activeAutoMergeMode = configuredAutoMergeMode
        autoMergeDivisorValue = configuredAutoMergeDivisor
        autoMergeCustomSecondsValue = configuredAutoMergeCustomSeconds
        autoMergeCustomSizeMibValue = configuredAutoMergeCustomSizeMib
        autoMergeEagerEnabled = configuredAutoMergeEagerEnabled
        selectedExportTreeUri = configuredExportTreeUri

        availableThemes = AppThemeMode.entries
        setDropdownItems(
            themeDropdown,
            availableThemes.map { getString(it.labelRes) },
            getString(configuredThemeMode.labelRes),
        )

        availableFormats = supportedFormats()
        setDropdownItems(
            formatDropdown,
            availableFormats.map { getString(it.labelRes) },
            getString((configuredFormat.takeIf { it in availableFormats } ?: availableFormats.first()).labelRes),
        )

        availableRouteModes = InputRouteMode.entries
        setDropdownItems(
            inputRouteDropdown,
            availableRouteModes.map { getString(it.labelRes) },
            getString(configuredRoute.labelRes),
        )

        availableCodecs = supportedCodecs(currentFormat())
        setDropdownItems(
            codecDropdown,
            availableCodecs.map { getString(it.labelRes) },
            getString((configuredCodec.takeIf { it in availableCodecs } ?: availableCodecs.first()).labelRes),
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

        availableAutoMergeModes = AutoMergeMode.entries
        setDropdownItems(
            autoMergeDropdown,
            availableAutoMergeModes.map { getString(it.labelRes) },
            getString(configuredAutoMergeMode.labelRes),
        )
        refreshHistorySettingsUi()

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
        autoMergeEagerSwitch.isChecked = configuredAutoMergeEagerEnabled
        debugChunksSwitch.isChecked = configuredDebugChunksTab

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

    private fun refreshCapabilityUiAsync(resetOriginalSnapshot: Boolean) {
        val generation = ++capabilityRefreshGeneration
        val preferred = SettingsSnapshot().also(::saveCurrentToSnapshot)
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
        snapshot.format = currentFormat()
        snapshot.codec = currentCodec()
        snapshot.bitrateKbps = effectiveCodecBitrateKbps() ?: 0
        snapshot.source = currentSourceMode()
        snapshot.channelMode = currentChannelMode()
        snapshot.route = currentRouteMode()
        snapshot.sampleRate = currentSampleRate() ?: 0
        snapshot.historyChunkSeconds = historyChunkSecondsValue
        snapshot.autoMergeMode = activeAutoMergeMode
        snapshot.autoMergeDivisor = autoMergeDivisorValue
        snapshot.autoMergeCustomSeconds = autoMergeCustomSecondsValue
        snapshot.autoMergeCustomSizeMib = autoMergeCustomSizeMibValue
        snapshot.autoMergeEagerEnabled = autoMergeEagerSwitch.isChecked
        snapshot.exportDirectoryUri = selectedExportTreeUri?.toString()
        snapshot.persistentBufferEnabled = persistentBufferSwitch.isChecked
        snapshot.aggressiveRestartEnabled = aggressiveRestartSwitch.isChecked
        snapshot.wakeLockEnabled = wakeLockSwitch.isChecked
        snapshot.debugChunksTabEnabled = debugChunksSwitch.isChecked
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
        historyChunkSecondsValue = previous.historyChunkSeconds
        activeAutoMergeMode = previous.autoMergeMode
        autoMergeDivisorValue = previous.autoMergeDivisor
        autoMergeCustomSecondsValue = previous.autoMergeCustomSeconds
        autoMergeCustomSizeMibValue = previous.autoMergeCustomSizeMib
        autoMergeEagerEnabled = previous.autoMergeEagerEnabled
        selectedExportTreeUri = previous.exportDirectoryUri?.let(Uri::parse)
        persistentBufferSwitch.isChecked = previous.persistentBufferEnabled
        aggressiveRestartSwitch.isChecked = previous.aggressiveRestartEnabled
        wakeLockSwitch.isChecked = previous.wakeLockEnabled
        autoMergeEagerSwitch.isChecked = previous.autoMergeEagerEnabled
        debugChunksSwitch.isChecked = previous.debugChunksTabEnabled

        setDropdownItems(
            themeDropdown,
            availableThemes.map { getString(it.labelRes) },
            getString(previous.themeMode.labelRes),
        )
        setDropdownItems(
            formatDropdown,
            availableFormats.map { getString(it.labelRes) },
            getString(previous.format?.labelRes ?: availableFormats.first().labelRes),
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
        setDropdownItems(
            autoMergeDropdown,
            availableAutoMergeModes.map { getString(it.labelRes) },
            getString(previous.autoMergeMode.labelRes),
        )
        refreshHistorySettingsUi()

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
        val format = currentFormat()
        availableCodecs = supportedCodecs(format)
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
        val format = currentFormat()
        val codec = currentCodec()
        val route = currentRouteMode()
        availableSourceModes = supportedAudioSourceModes(this, route, format, codec)
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
        val format = currentFormat()
        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        availableChannelModes = supportedChannelModes(this, source, route, format, codec)
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
        val format = currentFormat()
        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        val channelMode = currentChannelMode()
        availableSampleRates = supportedSampleRates(this, source, route, format, codec, channelMode)

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

    private fun refreshHistorySettingsUi(preserveHistoryChunkInput: Boolean = false) {
        val previousBindingUi = bindingUi
        bindingUi = true
        if (!preserveHistoryChunkInput) {
            historyChunkInput.setText(formatDurationInput(historyChunkSecondsValue))
        }
        refreshAutoMergeValueUi()
        bindingUi = previousBindingUi
    }

    private fun refreshAutoMergeValueUi() {
        val previousBindingUi = bindingUi
        bindingUi = true
        autoMergeValueLayout.error = null
        autoMergeValueLayout.isVisible = activeAutoMergeMode != AutoMergeMode.OFF
        if (activeAutoMergeMode == AutoMergeMode.OFF) {
            autoMergeValueLayout.helperText = null
            bindingUi = previousBindingUi
            return
        }

        when (activeAutoMergeMode) {
            AutoMergeMode.OFF -> Unit
            AutoMergeMode.RATIO -> {
                autoMergeValueLayout.hint = getString(R.string.auto_merge_ratio_value_label)
                autoMergeValueLayout.helperText = getString(R.string.auto_merge_ratio_supporting)
                autoMergeValueInput.keyListener = DigitsKeyListener.getInstance("0123456789")
                autoMergeValueInput.inputType = InputType.TYPE_CLASS_NUMBER
                autoMergeValueInput.setText(autoMergeDivisorValue.toString())
            }
            AutoMergeMode.CUSTOM_TIME -> {
                autoMergeValueLayout.hint = getString(R.string.auto_merge_custom_time_value_label)
                autoMergeValueLayout.helperText = getString(R.string.auto_merge_custom_time_supporting)
                autoMergeValueInput.keyListener = DigitsKeyListener.getInstance("0123456789:")
                autoMergeValueInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                autoMergeValueInput.setText(formatDurationInput(autoMergeCustomSecondsValue))
            }
            AutoMergeMode.CUSTOM_SIZE -> {
                autoMergeValueLayout.hint = getString(R.string.auto_merge_custom_size_value_label)
                autoMergeValueLayout.helperText = getString(R.string.auto_merge_custom_size_supporting)
                autoMergeValueInput.keyListener = DigitsKeyListener.getInstance("0123456789.,")
                autoMergeValueInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                autoMergeValueInput.setText(formatRetentionSizeMib(autoMergeCustomSizeMibValue))
            }
        }
        bindingUi = previousBindingUi
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
                parseRetentionSizeMib(retentionSizeInput.text?.toString().orEmpty())?.takeIf { it > 0.0 }?.let {
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

        val format = currentFormat()
        val codec = currentCodec()
        val channelMode = currentChannelMode()
        val bitrateKbps = effectiveCodecBitrateKbps()
        val estimatePrefix = if (format.isPcmContainer) "=" else "~"
        val exportLimitBytes = exportFileSizeLimitBytes(format)
        val exportLimitDurationSeconds = estimateExportDurationSeconds(
            format,
            codec,
            sampleRate,
            channelMode.channelCount,
            exportLimitBytes,
            bitrateKbps,
        )
        val estimatedSizeMb = bytesToMegabytes(
            estimateExportSizeBytes(format, codec, sampleRate, channelMode.channelCount, retentionTimeSecondsValue.toLong(), bitrateKbps),
        )
        val estimatedDuration = formatDurationInput(
            estimateExportDurationSeconds(
                format,
                codec,
                sampleRate,
                channelMode.channelCount,
                rawMegabytesToBytes(retentionSizeMbValue),
                bitrateKbps,
            ),
        )

        val previousBindingUi = bindingUi
        bindingUi = true
        if (activeRetentionMode == RetentionMode.TIME) {
            retentionTimeLayout.prefixText = null
            retentionSizeLayout.prefixText = estimatePrefix
            if (!preserveActiveInputs) {
                retentionTimeInput.setText(formatDurationInput(retentionTimeSecondsValue))
            }
            retentionSizeInput.setText(formatRetentionSizeMib(estimatedSizeMb))
        } else {
            retentionTimeLayout.prefixText = estimatePrefix
            retentionSizeLayout.prefixText = null
            retentionTimeInput.setText(estimatedDuration)
            if (!preserveActiveInputs) {
                retentionSizeInput.setText(formatRetentionSizeMib(retentionSizeMbValue))
            }
        }
        retentionTimeLayout.alpha = if (activeRetentionMode == RetentionMode.TIME) 1f else 0.82f
        retentionSizeLayout.alpha = if (activeRetentionMode == RetentionMode.SIZE) 1f else 0.82f
        retentionTimeLayout.helperText = getString(
            R.string.retention_time_limit_helper,
            "$estimatePrefix${formatDurationInput(exportLimitDurationSeconds)}",
        )
        retentionSizeLayout.helperText = getString(
            R.string.retention_size_limit_helper,
            formatShortFileSize(exportLimitBytes),
        )
        bindingUi = previousBindingUi
    }

    private fun persistSettings(showFeedback: Boolean): Boolean {
        clearErrors()

        val themeMode = currentThemeMode()
        val format = currentFormat()
        val codec = currentCodec()
        val channelMode = currentChannelMode()
        val route = currentRouteMode()
        val source = currentSourceMode()
        val sampleRate = currentSampleRate()
        val bitrateKbps = currentBitrateKbpsOrNull()
        val historyChunkSeconds = parseDurationInput(historyChunkInput.text?.toString().orEmpty())
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
            parseRetentionSizeMib(retentionSizeInput.text?.toString().orEmpty())
        } else {
            retentionSizeMbValue
        }
        if (sizeMb == null || sizeMb <= 0.0) {
            retentionSizeLayout.error = getString(R.string.custom_memory_size_invalid)
            return false
        }

        val requestedSizeBytes = rawMegabytesToBytes(sizeMb)

        val bitrateRange = codecBitrateRangeKbps(codec)
        if (bitrateRange != null && (bitrateKbps == null || bitrateKbps !in bitrateRange)) {
            Toast.makeText(this, getString(R.string.codec_bitrate_invalid, bitrateRange.first, bitrateRange.last), Toast.LENGTH_SHORT).show()
            return false
        }

        if (historyChunkSeconds == null) {
            historyChunkLayout.error = getString(R.string.history_chunk_invalid)
            return false
        }

        val historyChunkRange = historyChunkSecondsRange()
        if (historyChunkSeconds !in historyChunkRange) {
            historyChunkLayout.error = getString(
                R.string.history_chunk_range_invalid,
                formatDurationInput(historyChunkRange.first),
                formatDurationInput(historyChunkRange.last),
            )
            return false
        }

        val autoMergeDivisor = when (activeAutoMergeMode) {
            AutoMergeMode.RATIO -> autoMergeValueInput.text?.toString()?.trim()?.toIntOrNull()
            else -> autoMergeDivisorValue
        }
        val autoMergeCustomSeconds = when (activeAutoMergeMode) {
            AutoMergeMode.CUSTOM_TIME -> parseDurationInput(autoMergeValueInput.text?.toString().orEmpty())
            else -> autoMergeCustomSecondsValue
        }
        val autoMergeCustomSizeMib = when (activeAutoMergeMode) {
            AutoMergeMode.CUSTOM_SIZE -> parseRetentionSizeMib(autoMergeValueInput.text?.toString().orEmpty())
            else -> autoMergeCustomSizeMibValue
        }
        val divisorRange = autoMergeDivisorRange()
        if (activeAutoMergeMode == AutoMergeMode.RATIO) {
            if (autoMergeDivisor == null || autoMergeDivisor !in divisorRange) {
                autoMergeValueLayout.error = getString(
                    R.string.auto_merge_divisor_invalid,
                    divisorRange.first,
                    divisorRange.last,
                )
                return false
            }
        }
        val customRange = autoMergeCustomSecondsRange()
        if (activeAutoMergeMode == AutoMergeMode.CUSTOM_TIME) {
            if (autoMergeCustomSeconds == null) {
                autoMergeValueLayout.error = getString(R.string.auto_merge_custom_time_invalid)
                return false
            }
            if (autoMergeCustomSeconds !in customRange) {
                autoMergeValueLayout.error = getString(
                    R.string.auto_merge_custom_time_range_invalid,
                    formatDurationInput(customRange.first),
                    formatDurationInput(customRange.last),
                )
                return false
            }
        }
        val customSizeRange = autoMergeCustomSizeRangeMib()
        if (activeAutoMergeMode == AutoMergeMode.CUSTOM_SIZE) {
            if (autoMergeCustomSizeMib == null) {
                autoMergeValueLayout.error = getString(R.string.auto_merge_custom_size_invalid)
                return false
            }
            if (autoMergeCustomSizeMib !in customSizeRange) {
                autoMergeValueLayout.error = getString(
                    R.string.auto_merge_custom_size_range_invalid,
                    formatRetentionSizeMib(customSizeRange.start),
                    formatRetentionSizeMib(customSizeRange.endInclusive),
                )
                return false
            }
        }

        retentionTimeSecondsValue = retentionTime
        retentionSizeMbValue = sizeMb
        historyChunkSecondsValue = historyChunkSeconds
        autoMergeDivisorValue = autoMergeDivisor ?: autoMergeDivisorValue
        autoMergeCustomSecondsValue = autoMergeCustomSeconds ?: autoMergeCustomSecondsValue
        autoMergeCustomSizeMibValue = autoMergeCustomSizeMib ?: autoMergeCustomSizeMibValue
        val sizeBytes = requestedSizeBytes

        setConfiguredThemeMode(this, themeMode)
        getRecorderPreferences(this).edit()
            .putString(TimeTravelConfig.RETENTION_MODE_KEY, activeRetentionMode.prefValue)
            .putLong(TimeTravelConfig.RETENTION_SECONDS_KEY, retentionTime.toLong())
            .putLong(TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY, sizeBytes)
            .putString(TimeTravelConfig.OUTPUT_FORMAT_KEY, format.prefValue)
            .putString(TimeTravelConfig.OUTPUT_CODEC_KEY, codec.prefValue)
            .putInt(TimeTravelConfig.OUTPUT_BITRATE_KBPS_KEY, bitrateKbps ?: (effectiveCodecBitrateKbps() ?: 0))
            .putInt(TimeTravelConfig.AUDIO_SOURCE_KEY, source.sourceValue)
            .putString(TimeTravelConfig.CHANNEL_MODE_KEY, channelMode.prefValue)
            .putString(TimeTravelConfig.INPUT_ROUTE_KEY, route.prefValue)
            .putInt(TimeTravelConfig.SAMPLE_RATE_KEY, sampleRate)
            .putInt(TimeTravelConfig.HISTORY_CHUNK_SECONDS_KEY, historyChunkSecondsValue)
            .putString(TimeTravelConfig.AUTO_MERGE_MODE_KEY, activeAutoMergeMode.prefValue)
            .putInt(TimeTravelConfig.AUTO_MERGE_DIVISOR_KEY, autoMergeDivisorValue)
            .putInt(TimeTravelConfig.AUTO_MERGE_CUSTOM_SECONDS_KEY, autoMergeCustomSecondsValue)
            .putString(TimeTravelConfig.AUTO_MERGE_CUSTOM_SIZE_MIB_KEY, formatRetentionSizeMib(autoMergeCustomSizeMibValue))
            .putBoolean(TimeTravelConfig.AUTO_MERGE_EAGER_ENABLED_KEY, autoMergeEagerSwitch.isChecked)
            .putBoolean(TimeTravelConfig.DEBUG_CHUNKS_TAB_ENABLED_KEY, debugChunksSwitch.isChecked)
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
            TimeTravelService.ApplySettingsResult.REENCODE_REQUIRED -> {
                if (showFeedback) {
                    Toast.makeText(this, R.string.settings_saved_reencode_history, Toast.LENGTH_SHORT).show()
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
        formatLayout.error = null
        sampleRateLayout.error = null
        codecLayout.error = null
        audioSourceLayout.error = null
        channelModeLayout.error = null
        inputRouteLayout.error = null
        historyChunkLayout.error = null
        autoMergeValueLayout.error = null
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

    private fun currentFormat(): ExportFormat {
        val selected = formatDropdown.text?.toString().orEmpty()
        return availableFormats.firstOrNull { getString(it.labelRes) == selected } ?: availableFormats.first()
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

    private fun currentAutoMergeMode(): AutoMergeMode {
        val selected = autoMergeDropdown.text?.toString().orEmpty()
        return availableAutoMergeModes.firstOrNull { getString(it.labelRes) == selected } ?: availableAutoMergeModes.firstOrNull() ?: AutoMergeMode.OFF
    }

    private fun refreshExportDirectoryUi() {
        bindingUi = true
        exportPathValue.text = describeOutputDirectory(this, selectedExportTreeUri)
        val hasCustomDirectory = selectedExportTreeUri != null
        defaultFolderButton.isVisible = hasCustomDirectory
        defaultFolderButton.isEnabled = hasCustomDirectory
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

    private fun bytesToMegabytes(bytes: Long): Double {
        return (bytes.coerceAtLeast(0L) / BYTES_IN_MEGABYTE.toDouble())
    }

    private fun rawMegabytesToBytes(memoryInMegabytes: Double): Long {
        if (memoryInMegabytes <= 0.0) {
            return 0L
        }
        if (memoryInMegabytes >= Long.MAX_VALUE / BYTES_IN_MEGABYTE.toDouble()) {
            return Long.MAX_VALUE
        }
        return (memoryInMegabytes * BYTES_IN_MEGABYTE.toDouble()).roundToLong()
    }

    private fun parseRetentionSizeMib(value: String): Double? {
        return value.trim().replace(',', '.').toDoubleOrNull()
    }

    private fun formatRetentionSizeMib(value: Double): String {
        val formatter = DecimalFormat("0.###", DecimalFormatSymbols(Locale.US))
        return formatter.format(value.coerceAtLeast(0.0))
    }

    private companion object {
        const val BYTES_IN_MEGABYTE = 1024L * 1024L
    }
    private fun updateUndoButton(enabled: Boolean) {
        undoButton.isEnabled = enabled
        undoButton.alpha = if (enabled) 1f else 0.38f
        val applyImageButton = applyButton as? ImageButton ?: return
        applyImageButton.setImageResource(if (enabled) R.drawable.ic_check else R.drawable.ic_close)
        applyImageButton.contentDescription = getString(if (enabled) R.string.done else R.string.close)
    }
}

private class DropdownHighlightAdapter(
    context: Context,
    items: List<String>,
    selectedValue: String,
) : ArrayAdapter<String>(context, R.layout.item_dropdown_option, android.R.id.text1, items.toMutableList()) {
    private var selectedValue = selectedValue

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
        val row = view.findViewById<View>(R.id.dropdown_row)
        val check = view.findViewById<ImageView>(R.id.dropdown_check)
        val active = value == selectedValue
        row.isActivated = active
        row.isSelected = active
        view.isActivated = false
        view.isSelected = false
        check.visibility = if (active) View.VISIBLE else View.GONE
    }
}
