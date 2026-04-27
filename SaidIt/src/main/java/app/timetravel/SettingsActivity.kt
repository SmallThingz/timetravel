package app.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.util.Stack

class SettingsActivity : AppCompatActivity() {
    private lateinit var themeLayout: TextInputLayout
    private lateinit var retentionTimeLayout: TextInputLayout
    private lateinit var retentionSizeLayout: TextInputLayout
    private lateinit var codecLayout: TextInputLayout
    private lateinit var sampleRateLayout: TextInputLayout
    private lateinit var audioSourceLayout: TextInputLayout
    private lateinit var inputRouteLayout: TextInputLayout
    private lateinit var exportPathLayout: TextInputLayout
    private lateinit var themeDropdown: MaterialAutoCompleteTextView
    private lateinit var codecDropdown: MaterialAutoCompleteTextView
    private lateinit var sampleRateDropdown: MaterialAutoCompleteTextView
    private lateinit var audioSourceDropdown: MaterialAutoCompleteTextView
    private lateinit var inputRouteDropdown: MaterialAutoCompleteTextView
    private lateinit var retentionTimeInput: EditText
    private lateinit var retentionSizeInput: EditText
    private lateinit var exportPathInput: EditText
    private lateinit var editPresetsButton: MaterialButton
    private lateinit var chooseFolderButton: MaterialButton
    private lateinit var defaultFolderButton: MaterialButton
    private lateinit var moveRecordingsButton: MaterialButton
    private lateinit var undoButton: MaterialButton

    private var service: TimeTravelService? = null
    private var serviceBound = false
    private var bindingUi = false
    private var formattingTimeInput = false
    private var hasUnsavedChanges = false
    private var moveAvailabilityGeneration = 0

    private val originalSettings = Stack<SettingsSnapshot>()
    private val currentSettings = SettingsSnapshot()

    private var availableThemes: List<AppThemeMode> = emptyList()
    private var availableCodecs: List<ExportCodec> = emptyList()
    private var availableSourceModes: List<AudioSourceMode> = emptyList()
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
        var source: AudioSourceMode? = null,
        var route: InputRouteMode? = null,
        var sampleRate: Int = 0,
        var exportDirectoryUri: String? = null,
    ) {
        fun copyFrom(other: SettingsSnapshot) {
            themeMode = other.themeMode
            retentionMode = other.retentionMode
            retentionTime = other.retentionTime
            retentionSizeMb = other.retentionSizeMb
            codec = other.codec
            source = other.source
            route = other.route
            sampleRate = other.sampleRate
            exportDirectoryUri = other.exportDirectoryUri
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
        inputRouteLayout = findViewById(R.id.input_route_layout)
        exportPathLayout = findViewById(R.id.export_path_layout)
        themeDropdown = findViewById(R.id.theme_dropdown)
        codecDropdown = findViewById(R.id.codec_dropdown)
        sampleRateDropdown = findViewById(R.id.sample_rate_dropdown)
        audioSourceDropdown = findViewById(R.id.audio_source_dropdown)
        inputRouteDropdown = findViewById(R.id.input_route_dropdown)
        retentionTimeInput = findViewById(R.id.retention_time_input)
        retentionSizeInput = findViewById(R.id.retention_size_input)
        exportPathInput = findViewById(R.id.export_path_input)
        editPresetsButton = findViewById(R.id.edit_presets_button)
        chooseFolderButton = findViewById(R.id.choose_folder_button)
        defaultFolderButton = findViewById(R.id.default_folder_button)
        moveRecordingsButton = findViewById(R.id.move_recordings_button)
        undoButton = findViewById(R.id.undo_button)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges) {
                    saveSettingsSilently()
                }
                finish()
                overridePendingTransition(0, 0)
            }
        })

        findViewById<View>(R.id.settings_return).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        editPresetsButton.setOnClickListener { showEditPresetsDialog() }
        chooseFolderButton.setOnClickListener { exportDirectoryLauncher.launch(selectedExportTreeUri) }
        defaultFolderButton.setOnClickListener {
            selectedExportTreeUri = null
            refreshExportDirectoryUi()
            refreshMoveRecordingsAvailability()
            saveCurrentToSnapshot(currentSettings)
            pushUndoState()
        }
        moveRecordingsButton.setOnClickListener { moveExistingRecordings() }
        undoButton.setOnClickListener { restorePreviousSettings() }
        setupListeners()
        bindUiFromPreferences(true)
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
        retentionTimeInput.setOnClickListener { activateRetentionMode(RetentionMode.TIME) }
        retentionSizeInput.setOnClickListener { activateRetentionMode(RetentionMode.SIZE) }
        retentionTimeInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) activateRetentionMode(RetentionMode.TIME) }
        retentionSizeInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) activateRetentionMode(RetentionMode.SIZE) }

        retentionTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (bindingUi || formattingTimeInput) return
                val formatted = formatRetentionTimeInput(s?.toString().orEmpty())
                if (formatted != s?.toString().orEmpty()) {
                    formattingTimeInput = true
                    retentionTimeInput.setText(formatted)
                    retentionTimeInput.setSelection(formatted.length)
                    formattingTimeInput = false
                    return
                }
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

        themeDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        codecDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshSourceModes(preferredSource = currentSourceMode())
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        inputRouteDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshSourceModes(preferredSource = currentSourceMode())
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        audioSourceDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshSampleRates(preferredRate = currentSampleRate())
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
        sampleRateDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshRetentionFields(preserveActiveInputs = true)
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
    }

    private fun bindUiFromPreferences(isInitial: Boolean = false) {
        bindingUi = true

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
        val configuredRate = getConfiguredSampleRate(this, configuredSource, configuredRoute, configuredCodec)
        val configuredExportTreeUri = getConfiguredExportTreeUri(this)

        if (isInitial) {
            activeRetentionMode = configuredMode
            retentionTimeSecondsValue = configuredTime
            retentionSizeMbValue = bytesToMegabytes(storedSizeBytes)
            selectedExportTreeUri = configuredExportTreeUri
            currentSettings.themeMode = configuredThemeMode
            currentSettings.retentionMode = configuredMode
            currentSettings.retentionTime = retentionTimeSecondsValue
            currentSettings.retentionSizeMb = retentionSizeMbValue
            currentSettings.codec = configuredCodec
            currentSettings.source = configuredSource
            currentSettings.route = configuredRoute
            currentSettings.sampleRate = configuredRate
            currentSettings.exportDirectoryUri = configuredExportTreeUri?.toString()
            originalSettings.clear()
            originalSettings.push(currentSettings.copy())
        } else {
            activeRetentionMode = configuredMode
            retentionTimeSecondsValue = configuredTime
            retentionSizeMbValue = bytesToMegabytes(storedSizeBytes)
            selectedExportTreeUri = configuredExportTreeUri
        }

        availableThemes = AppThemeMode.entries
        setDropdownItems(
            themeDropdown,
            availableThemes.map { getString(it.labelRes) },
            getString(configuredThemeMode.labelRes),
        )

        availableCodecs = supportedCodecs()
        val selectedCodec = availableCodecs.firstOrNull { it == configuredCodec } ?: availableCodecs.first()
        setDropdownItems(
            codecDropdown,
            availableCodecs.map { getString(it.labelRes) },
            getString(selectedCodec.labelRes),
        )

        availableRouteModes = supportedInputRouteModes(this)
        val selectedRoute = availableRouteModes.firstOrNull { it == configuredRoute } ?: availableRouteModes.first()
        setDropdownItems(
            inputRouteDropdown,
            availableRouteModes.map { getString(it.labelRes) },
            getString(selectedRoute.labelRes),
        )

        refreshSourceModes(configuredSource, configuredRate)

        bindingUi = false
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()
    }

    private fun saveCurrentToSnapshot(snapshot: SettingsSnapshot) {
        snapshot.themeMode = currentThemeMode()
        snapshot.retentionMode = activeRetentionMode
        snapshot.retentionTime = retentionTimeSecondsValue
        snapshot.retentionSizeMb = retentionSizeMbValue
        snapshot.codec = currentCodec()
        snapshot.source = currentSourceMode()
        snapshot.route = currentRouteMode()
        snapshot.sampleRate = currentSampleRate() ?: 0
        snapshot.exportDirectoryUri = selectedExportTreeUri?.toString()
    }

    private fun pushUndoState() {
        hasUnsavedChanges = originalSettings.peek() != currentSettings
        undoButton.visibility = if (hasUnsavedChanges) View.VISIBLE else View.GONE
    }

    private fun restorePreviousSettings() {
        if (originalSettings.size > 1) {
            originalSettings.pop()
        }
        val previous = originalSettings.peek()

        bindingUi = true
        activeRetentionMode = previous.retentionMode
        retentionTimeSecondsValue = previous.retentionTime
        retentionSizeMbValue = previous.retentionSizeMb
        selectedExportTreeUri = previous.exportDirectoryUri?.let(Uri::parse)

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

        refreshSourceModes(previous.source, previous.sampleRate)

        bindingUi = false
        refreshRetentionFields()
        refreshExportDirectoryUi()
        refreshMoveRecordingsAvailability()

        currentSettings.copyFrom(previous)
        hasUnsavedChanges = originalSettings.peek() != currentSettings
        undoButton.visibility = if (hasUnsavedChanges) View.VISIBLE else View.GONE
    }

    private fun refreshSourceModes(
        preferredSource: AudioSourceMode? = null,
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
        refreshSampleRates(preferredRate)
    }

    private fun refreshSampleRates(preferredRate: Int? = null) {
        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        availableSampleRates = supportedSampleRates(this, source, route, codec)

        sampleRateLayout.error = if (availableSampleRates.isEmpty()) getString(R.string.unsupported_config_message) else null

        if (availableSampleRates.isEmpty()) {
            setDropdownItems(sampleRateDropdown, emptyList(), "")
            sampleRateDropdown.isEnabled = false
        } else {
            sampleRateDropdown.isEnabled = true
            val selectedRate = preferredRate?.takeIf { it in availableSampleRates } ?: availableSampleRates.first()
            setDropdownItems(
                sampleRateDropdown,
                availableSampleRates.map(::sampleRateLabel),
                sampleRateLabel(selectedRate),
            )
        }

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
            bindingUi = true
            if (!preserveActiveInputs) {
                retentionTimeInput.setText(formatRetentionTimeInput(""))
                retentionSizeInput.setText("")
            }
            retentionTimeLayout.prefixText = null
            retentionSizeLayout.prefixText = null
            bindingUi = false
            return
        }

        val codec = currentCodec()
        val estimatePrefix = if (codec == ExportCodec.WAV) "=" else "~"
        val estimatedSizeMb = bytesToMegabytes(
            estimateExportSizeBytes(codec, sampleRate, retentionTimeSecondsValue.toLong()),
        ).toString()
        val estimatedDuration = formatDurationInput(
            estimateExportDurationSeconds(codec, sampleRate, megabytesToBytes(retentionSizeMbValue)).toInt(),
        )

        bindingUi = true
        if (activeRetentionMode == RetentionMode.TIME) {
            retentionTimeLayout.prefixText = null
            retentionSizeLayout.prefixText = estimatePrefix
            if (!preserveActiveInputs) {
                retentionTimeInput.setText(formatRetentionTimeInput(formatDurationInput(retentionTimeSecondsValue)))
            }
            retentionSizeInput.setText(estimatedSizeMb)
        } else {
            retentionTimeLayout.prefixText = estimatePrefix
            retentionSizeLayout.prefixText = null
            retentionTimeInput.setText(formatRetentionTimeInput(estimatedDuration))
            if (!preserveActiveInputs) {
                retentionSizeInput.setText(retentionSizeMbValue.toString())
            }
        }
        retentionTimeLayout.alpha = if (activeRetentionMode == RetentionMode.TIME) 1f else 0.82f
        retentionSizeLayout.alpha = if (activeRetentionMode == RetentionMode.SIZE) 1f else 0.82f
        bindingUi = false
    }

    private fun persistSettings(showFeedback: Boolean): Boolean {
        clearErrors()

        val themeMode = currentThemeMode()
        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        val sampleRate = currentSampleRate()

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

        retentionTimeSecondsValue = retentionTime
        retentionSizeMbValue = sizeMb
        val sizeBytes = megabytesToBytes(sizeMb)

        setConfiguredThemeMode(this, themeMode)
        getRecorderPreferences(this).edit()
            .putString(TimeTravelConfig.RETENTION_MODE_KEY, activeRetentionMode.prefValue)
            .putLong(TimeTravelConfig.RETENTION_SECONDS_KEY, retentionTime.toLong())
            .putLong(TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY, sizeBytes)
            .putString(TimeTravelConfig.OUTPUT_CODEC_KEY, codec.prefValue)
            .putInt(TimeTravelConfig.AUDIO_SOURCE_KEY, source.sourceValue)
            .putString(TimeTravelConfig.INPUT_ROUTE_KEY, route.prefValue)
            .putInt(TimeTravelConfig.SAMPLE_RATE_KEY, sampleRate)
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

    private fun showEditPresetsDialog() {
        ExportPresetEditor.show(this, getConfiguredExportPresets(this)) { presets ->
            saveConfiguredExportPresets(this, presets)
        }
    }

    private fun clearErrors() {
        retentionTimeLayout.error = null
        retentionSizeLayout.error = null
        themeLayout.error = null
        sampleRateLayout.error = null
        codecLayout.error = null
        audioSourceLayout.error = null
        inputRouteLayout.error = null
    }

    private fun setDropdownItems(
        view: MaterialAutoCompleteTextView,
        items: List<String>,
        selectedValue: String,
    ) {
        view.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_option, items))
        view.setText(selectedValue, false)
    }

    private fun currentThemeMode(): AppThemeMode {
        val selected = themeDropdown.text?.toString().orEmpty()
        return availableThemes.firstOrNull { getString(it.labelRes) == selected } ?: AppThemeMode.SYSTEM
    }

    private fun currentCodec(): ExportCodec {
        val selected = codecDropdown.text?.toString().orEmpty()
        return availableCodecs.firstOrNull { getString(it.labelRes) == selected } ?: availableCodecs.first()
    }

    private fun currentRouteMode(): InputRouteMode {
        val selected = inputRouteDropdown.text?.toString().orEmpty()
        return availableRouteModes.firstOrNull { getString(it.labelRes) == selected } ?: availableRouteModes.first()
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
        exportPathInput.setText(describeOutputDirectory(this, selectedExportTreeUri))
        exportPathLayout.error = null
        defaultFolderButton.isEnabled = selectedExportTreeUri != null
        bindingUi = false
    }

    private fun refreshMoveRecordingsAvailability() {
        val generation = ++moveAvailabilityGeneration
        moveRecordingsButton.isEnabled = false
        moveRecordingsButton.alpha = 0.38f
        lifecycleScope.launch {
            val canMove = RecordingRepository.hasMovableRecordings(this@SettingsActivity)
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
                    getString(R.string.move_recordings_done_with_cleanup, result.moved, result.removedMissing)
                }
                else -> getString(R.string.move_recordings_done, result.moved)
            }
            refreshMoveRecordingsAvailability()
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatRetentionTimeInput(value: String): String {
        val digits = value.filter(Char::isDigit).takeLast(6).padStart(6, '0')
        return buildString(8) {
            append(digits.substring(0, 2))
            append(':')
            append(digits.substring(2, 4))
            append(':')
            append(digits.substring(4, 6))
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
}
