package app.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.util.Stack

class SettingsActivity : AppCompatActivity() {
    private lateinit var retentionModeGroup: MaterialButtonToggleGroup
    private lateinit var retentionTimeLayout: TextInputLayout
    private lateinit var retentionSizeLayout: TextInputLayout
    private lateinit var retentionTimeInput: EditText
    private lateinit var retentionSizeInput: EditText
    private lateinit var retentionTimeEstimate: TextView
    private lateinit var retentionSizeEstimate: TextView
    private lateinit var codecLayout: TextInputLayout
    private lateinit var sampleRateLayout: TextInputLayout
    private lateinit var audioSourceLayout: TextInputLayout
    private lateinit var inputRouteLayout: TextInputLayout
    private lateinit var codecDropdown: MaterialAutoCompleteTextView
    private lateinit var sampleRateDropdown: MaterialAutoCompleteTextView
    private lateinit var audioSourceDropdown: MaterialAutoCompleteTextView
    private lateinit var inputRouteDropdown: MaterialAutoCompleteTextView
    private lateinit var editPresetsButton: MaterialButton
    private lateinit var undoButton: MaterialButton

    private var service: TimeTravelService? = null
    private var serviceBound = false
    private var bindingUi = false
    private var hasUnsavedChanges = false

    private val originalSettings = Stack<SettingsSnapshot>()
    private val currentSettings = SettingsSnapshot()

    private var availableCodecs: List<ExportCodec> = emptyList()
    private var availableSourceModes: List<AudioSourceMode> = emptyList()
    private var availableRouteModes: List<InputRouteMode> = emptyList()
    private var availableSampleRates: List<Int> = emptyList()

    data class SettingsSnapshot(
        var retentionMode: RetentionMode = RetentionMode.TIME,
        var retentionTime: Int = 0,
        var retentionSizeMb: Long = 0,
        var codec: ExportCodec? = null,
        var source: AudioSourceMode? = null,
        var route: InputRouteMode? = null,
        var sampleRate: Int = 0,
    ) {
        fun copyFrom(other: SettingsSnapshot) {
            retentionMode = other.retentionMode
            retentionTime = other.retentionTime
            retentionSizeMb = other.retentionSizeMb
            codec = other.codec
            source = other.source
            route = other.route
            sampleRate = other.sampleRate
        }
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
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        applyTimeTravelSystemBars()
        setContentView(R.layout.activity_settings)

        val root = findViewById<View>(R.id.settings_layout)
        applyWindowInsets(root)

        retentionModeGroup = findViewById(R.id.retention_mode_group)
        retentionTimeLayout = findViewById(R.id.retention_time_layout)
        retentionSizeLayout = findViewById(R.id.retention_size_layout)
        retentionTimeInput = findViewById(R.id.retention_time_input)
        retentionSizeInput = findViewById(R.id.retention_size_input)
        retentionTimeEstimate = findViewById(R.id.retention_time_estimate)
        retentionSizeEstimate = findViewById(R.id.retention_size_estimate)
        codecLayout = findViewById(R.id.codec_layout)
        sampleRateLayout = findViewById(R.id.sample_rate_layout)
        audioSourceLayout = findViewById(R.id.audio_source_layout)
        inputRouteLayout = findViewById(R.id.input_route_layout)
        codecDropdown = findViewById(R.id.codec_dropdown)
        sampleRateDropdown = findViewById(R.id.sample_rate_dropdown)
        audioSourceDropdown = findViewById(R.id.audio_source_dropdown)
        inputRouteDropdown = findViewById(R.id.input_route_dropdown)
        editPresetsButton = findViewById(R.id.edit_presets_button)
        undoButton = findViewById(R.id.undo_button)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges) {
                    saveSettingsSilently()
                }
                finish()
            }
        })

        findViewById<View>(R.id.settings_return).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        editPresetsButton.setOnClickListener { showEditPresetsDialog() }
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

    private fun setupListeners() {
        retentionModeGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked || bindingUi) return@addOnButtonCheckedListener
            updateRetentionModeUi()
            refreshRetentionEstimates()
            saveCurrentToSnapshot(currentSettings)
            pushUndoState()
        }

        val summaryWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!bindingUi) {
                    refreshRetentionEstimates()
                    saveCurrentToSnapshot(currentSettings)
                    pushUndoState()
                }
            }
        }
        retentionTimeInput.addTextChangedListener(summaryWatcher)
        retentionSizeInput.addTextChangedListener(summaryWatcher)

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
                refreshRetentionEstimates()
                saveCurrentToSnapshot(currentSettings)
                pushUndoState()
            }
        }
    }

    private fun bindUiFromPreferences(isInitial: Boolean = false) {
        bindingUi = true

        val prefs = getRecorderPreferences(this)
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

        if (isInitial) {
            currentSettings.retentionMode = configuredMode
            currentSettings.retentionTime = configuredTime
            currentSettings.retentionSizeMb = bytesToMegabytes(storedSizeBytes)
            currentSettings.codec = configuredCodec
            currentSettings.source = configuredSource
            currentSettings.route = configuredRoute
            currentSettings.sampleRate = configuredRate
            originalSettings.clear()
            originalSettings.push(currentSettings.copy())
        }

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

        retentionTimeInput.setText(formatDurationInput(configuredTime))
        retentionSizeInput.setText(bytesToMegabytes(storedSizeBytes).toString())
        retentionModeGroup.check(
            if (configuredMode == RetentionMode.TIME) R.id.retention_mode_time_button else R.id.retention_mode_size_button,
        )

        bindingUi = false
        updateRetentionModeUi()
        refreshRetentionEstimates()
    }

    private fun saveCurrentToSnapshot(snapshot: SettingsSnapshot) {
        snapshot.retentionMode = currentRetentionMode()
        snapshot.retentionTime = parseDurationInput(retentionTimeInput.text?.toString().orEmpty()) ?: 0
        snapshot.retentionSizeMb = retentionSizeInput.text?.toString()?.trim()?.toLongOrNull() ?: 0
        snapshot.codec = currentCodec()
        snapshot.source = currentSourceMode()
        snapshot.route = currentRouteMode()
        snapshot.sampleRate = currentSampleRate() ?: 0
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
        
        retentionModeGroup.check(
            if (previous.retentionMode == RetentionMode.TIME) R.id.retention_mode_time_button else R.id.retention_mode_size_button,
        )
        retentionTimeInput.setText(formatDurationInput(previous.retentionTime))
        retentionSizeInput.setText(previous.retentionSizeMb.toString())
        
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
        updateRetentionModeUi()
        refreshRetentionEstimates()
        
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

        refreshRetentionEstimates()
    }

    private fun updateRetentionModeUi() {
        val usingTimeMode = currentRetentionMode() == RetentionMode.TIME
        setFieldEnabled(retentionTimeLayout, retentionTimeInput, usingTimeMode)
        setFieldEnabled(retentionSizeLayout, retentionSizeInput, !usingTimeMode)
    }

    private fun setFieldEnabled(
        layout: TextInputLayout,
        field: EditText,
        enabled: Boolean,
    ) {
        layout.isEnabled = enabled
        field.isEnabled = enabled
        layout.alpha = if (enabled) 1f else 0.68f
    }

    private fun refreshRetentionEstimates() {
        val sampleRate = currentSampleRate()
        if (sampleRate == null || sampleRate <= 0) {
            val unsupported = getString(R.string.unsupported_config_message)
            retentionTimeEstimate.text = unsupported
            retentionSizeEstimate.text = unsupported
            return
        }

        val codec = currentCodec()
        val fallbackTime = getConfiguredRetentionSeconds(this).toInt()
        val fallbackSizeMb = bytesToMegabytes(
            getRecorderPreferences(this).getLong(
                TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY,
                Runtime.getRuntime().maxMemory() / 4,
            ).coerceAtMost(getRetentionMemoryCapBytes()),
        )

        val requestedTimeSeconds = parseDurationInput(retentionTimeInput.text?.toString().orEmpty()) ?: fallbackTime
        val requestedSizeBytes = megabytesToBytes(
            retentionSizeInput.text?.toString()?.trim()?.toLongOrNull() ?: fallbackSizeMb,
        )

        retentionTimeEstimate.text = getString(
            R.string.retention_time_estimate,
            formatShortFileSize(estimateExportSizeBytes(codec, sampleRate, requestedTimeSeconds.toLong())),
        )
        retentionSizeEstimate.text = getString(
            R.string.retention_size_estimate,
            formatDurationInput(estimateExportDurationSeconds(codec, sampleRate, requestedSizeBytes).toInt()),
        )
    }

    private fun persistSettings(showFeedback: Boolean): Boolean {
        clearErrors()

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

        val retentionTime = parseDurationInput(retentionTimeInput.text?.toString().orEmpty())
        if (retentionTime == null || retentionTime <= 0) {
            retentionTimeLayout.error = getString(R.string.retention_time_invalid)
            return false
        }

        val sizeMb = retentionSizeInput.text?.toString()?.trim()?.toLongOrNull()
        if (sizeMb == null || sizeMb <= 0) {
            retentionSizeLayout.error = getString(R.string.custom_memory_size_invalid)
            return false
        }

        val sizeBytes = megabytesToBytes(sizeMb)

        getRecorderPreferences(this).edit()
            .putString(TimeTravelConfig.RETENTION_MODE_KEY, currentRetentionMode().prefValue)
            .putLong(TimeTravelConfig.RETENTION_SECONDS_KEY, retentionTime.toLong())
            .putLong(TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY, sizeBytes)
            .putString(TimeTravelConfig.OUTPUT_CODEC_KEY, codec.prefValue)
            .putInt(TimeTravelConfig.AUDIO_SOURCE_KEY, source.sourceValue)
            .putString(TimeTravelConfig.INPUT_ROUTE_KEY, route.prefValue)
            .putInt(TimeTravelConfig.SAMPLE_RATE_KEY, sampleRate)
            .apply()

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
        view.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items))
        view.setText(selectedValue, false)
    }

    private fun currentRetentionMode(): RetentionMode {
        return if (retentionModeGroup.checkedButtonId == R.id.retention_mode_time_button) RetentionMode.TIME else RetentionMode.SIZE
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
