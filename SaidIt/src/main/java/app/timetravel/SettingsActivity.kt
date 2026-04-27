package app.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
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

class SettingsActivity : AppCompatActivity() {
    private lateinit var historyLimit: TextView
    private lateinit var retentionModeGroup: MaterialButtonToggleGroup
    private lateinit var retentionTimeLayout: TextInputLayout
    private lateinit var retentionSizeLayout: TextInputLayout
    private lateinit var retentionTimeInput: EditText
    private lateinit var retentionSizeInput: EditText
    private lateinit var codecLayout: TextInputLayout
    private lateinit var sampleRateLayout: TextInputLayout
    private lateinit var audioSourceLayout: TextInputLayout
    private lateinit var inputRouteLayout: TextInputLayout
    private lateinit var codecDropdown: MaterialAutoCompleteTextView
    private lateinit var sampleRateDropdown: MaterialAutoCompleteTextView
    private lateinit var audioSourceDropdown: MaterialAutoCompleteTextView
    private lateinit var inputRouteDropdown: MaterialAutoCompleteTextView
    private lateinit var editPresetsButton: MaterialButton
    private lateinit var applyButton: MaterialButton

    private val timeFormatResult = NaturalLanguageResult()
    private var service: TimeTravelService? = null
    private var serviceBound = false
    private var bindingUi = false

    private var availableCodecs: List<ExportCodec> = emptyList()
    private var availableSourceModes: List<AudioSourceMode> = emptyList()
    private var availableRouteModes: List<InputRouteMode> = emptyList()
    private var availableSampleRates: List<Int> = emptyList()

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
        UiFonts.styleSettings(root, this)
        applyWindowInsets(root)

        historyLimit = findViewById(R.id.history_limit)
        retentionModeGroup = findViewById(R.id.retention_mode_group)
        retentionTimeLayout = findViewById(R.id.retention_time_layout)
        retentionSizeLayout = findViewById(R.id.retention_size_layout)
        retentionTimeInput = findViewById(R.id.retention_time_input)
        retentionSizeInput = findViewById(R.id.retention_size_input)
        codecLayout = findViewById(R.id.codec_layout)
        sampleRateLayout = findViewById(R.id.sample_rate_layout)
        audioSourceLayout = findViewById(R.id.audio_source_layout)
        inputRouteLayout = findViewById(R.id.input_route_layout)
        codecDropdown = findViewById(R.id.codec_dropdown)
        sampleRateDropdown = findViewById(R.id.sample_rate_dropdown)
        audioSourceDropdown = findViewById(R.id.audio_source_dropdown)
        inputRouteDropdown = findViewById(R.id.input_route_dropdown)
        editPresetsButton = findViewById(R.id.edit_presets_button)
        applyButton = findViewById(R.id.apply_settings_button)

        historyLimit.typeface = Typeface.MONOSPACE

        findViewById<View>(R.id.settings_return).setOnClickListener { finish() }
        editPresetsButton.setOnClickListener { showEditPresetsDialog() }
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

    private fun applyWindowInsets(content: View) {
        val start = content.paddingStart
        val top = content.paddingTop
        val end = content.paddingEnd
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
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
            refreshRetentionSummary()
        }

        val summaryWatcher = object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!bindingUi) {
                    refreshRetentionSummary()
                }
            }
        }
        retentionTimeInput.addTextChangedListener(summaryWatcher)
        retentionSizeInput.addTextChangedListener(summaryWatcher)

        codecDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshSourceModes(preferredSource = currentSourceMode())
            }
        }
        inputRouteDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshSourceModes(preferredSource = currentSourceMode())
            }
        }
        audioSourceDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshSampleRates(preferredRate = currentSampleRate())
            }
        }
        sampleRateDropdown.setOnItemClickListener { _, _, _, _ ->
            if (!bindingUi) {
                refreshRetentionSummary()
            }
        }

        applyButton.setOnClickListener { applySettings() }
    }

    private fun bindUiFromPreferences() {
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
        refreshRetentionSummary()
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

        refreshRetentionSummary()
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

    private fun refreshRetentionSummary() {
        val sampleRate = currentSampleRate()
        if (sampleRate == null || sampleRate <= 0) {
            historyLimit.text = getString(R.string.unsupported_config_message)
            return
        }

        val fallbackTime = getConfiguredRetentionSeconds(this).toInt()
        val fallbackSizeBytes = getRecorderPreferences(this).getLong(
            TimeTravelConfig.AUDIO_MEMORY_SIZE_KEY,
            Runtime.getRuntime().maxMemory() / 4,
        ).coerceAtMost(getRetentionMemoryCapBytes())

        val seconds = if (currentRetentionMode() == RetentionMode.TIME) {
            val requested = parseDurationInput(retentionTimeInput.text?.toString().orEmpty()) ?: fallbackTime
            retentionSecondsForBytes(bytesForRetentionSeconds(requested.toLong(), sampleRate), sampleRate)
        } else {
            val requestedBytes = megabytesToBytes(
                retentionSizeInput.text?.toString()?.trim()?.toLongOrNull() ?: bytesToMegabytes(fallbackSizeBytes),
            )
            retentionSecondsForBytes(requestedBytes, sampleRate)
        }

        formatNaturalLanguage(resources, seconds.toFloat(), timeFormatResult)
        historyLimit.text = timeFormatResult.text
    }

    private fun applySettings() {
        clearErrors()

        val codec = currentCodec()
        val route = currentRouteMode()
        val source = currentSourceMode()
        val sampleRate = currentSampleRate()

        if (sampleRate == null) {
            sampleRateLayout.error = getString(R.string.unsupported_config_message)
            Toast.makeText(this, R.string.unsupported_config_message, Toast.LENGTH_SHORT).show()
            return
        }

        val retentionTime = parseDurationInput(retentionTimeInput.text?.toString().orEmpty())
        if (retentionTime == null || retentionTime <= 0) {
            retentionTimeLayout.error = getString(R.string.retention_time_invalid)
            return
        }

        val sizeMb = retentionSizeInput.text?.toString()?.trim()?.toLongOrNull()
        if (sizeMb == null || sizeMb <= 0) {
            retentionSizeLayout.error = getString(R.string.custom_memory_size_invalid)
            return
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
            Toast.makeText(this, R.string.settings_saved_next_start, Toast.LENGTH_SHORT).show()
            return
        }

        when (currentService.applyUpdatedPreferences()) {
            TimeTravelService.ApplySettingsResult.BLOCKED_RECORDING -> {
                Toast.makeText(this, R.string.settings_apply_blocked_recording, Toast.LENGTH_SHORT).show()
            }
            TimeTravelService.ApplySettingsResult.APPLIED_NOW -> {
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            TimeTravelService.ApplySettingsResult.DEFERRED_UNTIL_RESTART -> {
                Toast.makeText(this, R.string.settings_saved_deferred_input, Toast.LENGTH_SHORT).show()
            }
        }
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
}
