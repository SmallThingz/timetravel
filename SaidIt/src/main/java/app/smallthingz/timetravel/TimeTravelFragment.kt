package app.smallthingz.timetravel

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputLayout
import androidx.appcompat.app.AppCompatDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

@SuppressLint("ImplicitSamInstance")
class TimeTravelFragment : Fragment() {
    private lateinit var brandLockup: View
    private lateinit var recordMaxButton: MaterialButton
    private lateinit var recordCustomButton: MaterialButton
    private lateinit var reencodeHistoryButton: MaterialButton
    private lateinit var contentScroll: View
    private lateinit var historySize: TextView
    private lateinit var recTime: TextView
    private lateinit var formatSummary: TextView
    private lateinit var clearBufferButton: View
    private lateinit var recTouchArea: View
    private lateinit var recButtonCircle: View
    private lateinit var listenSurface: View
    private lateinit var listenRing: View
    private lateinit var listenTitle: TextView

    private lateinit var settingsButton: View
    private lateinit var listenSurfaceDrawable: MaterialShapeDrawable
    private lateinit var listenRingDrawable: MaterialShapeDrawable

    private var isListening = true
    private var isRecording = false
    private var isSaving = false
    private var isHistoryReencodePending = false
    private var isHistoryReencoding = false
    private var historyReencodeProcessedBytes = 0L
    private var historyReencodeTotalBytes = 0L
    private var recorder: TimeTravelService? = null
    private var serviceBound = false
    private var currentPulseMode = PULSE_OFF
    private var pulseAnimators: List<ObjectAnimator> = emptyList()
    private var savingSnackbar: Snackbar? = null
    private var lastMemorizedSeconds = 0f
    private var lastTotalMemorySeconds = 0f

    private val updater: Runnable = object : Runnable {
        override fun run() {
            if (view == null) return
            recorder?.getState(serviceStateCallback)
        }
    }

    private val recorderConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.d(TAG, "onServiceConnected")
            val typedBinder = binder as TimeTravelService.BackgroundRecorderBinder
            val service = typedBinder.service
            serviceBound = true
            if (recorder === service) {
                return
            }
            recorder = service
            refreshConfiguredUi()
            view?.postOnAnimation(updater)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "onServiceDisconnected")
            recorder = null
            serviceBound = false
            if (isSaving) {
                setSavingInProgress(false)
            }
        }
    }

    private val serviceStateCallback: TimeTravelService.StateCallback = object : TimeTravelService.StateCallback {
        override fun state(
            listeningEnabled: Boolean,
            recording: Boolean,
            memorized: Float,
            totalMemory: Float,
            recorded: Float,
            historyReencodePending: Boolean,
            historyReencoding: Boolean,
            historyReencodeProcessedBytes: Long,
            historyReencodeTotalBytes: Long,
        ) {
            activity ?: return
            var surfaceStateChanged = false

            if (recording != isRecording) {
                isRecording = recording
                updateRecordingState(recording)
                surfaceStateChanged = true
            }

            if (listeningEnabled != isListening) {
                isListening = listeningEnabled
                surfaceStateChanged = true
            }
            if (
                historyReencodePending != isHistoryReencodePending ||
                historyReencoding != isHistoryReencoding ||
                historyReencodeProcessedBytes != this@TimeTravelFragment.historyReencodeProcessedBytes ||
                historyReencodeTotalBytes != this@TimeTravelFragment.historyReencodeTotalBytes
            ) {
                isHistoryReencodePending = historyReencodePending
                isHistoryReencoding = historyReencoding
                this@TimeTravelFragment.historyReencodeProcessedBytes = historyReencodeProcessedBytes
                this@TimeTravelFragment.historyReencodeTotalBytes = historyReencodeTotalBytes
                surfaceStateChanged = true
            }
            if (surfaceStateChanged) {
                updateListenSurfaceAppearance()
            }

            lastMemorizedSeconds = memorized
            lastTotalMemorySeconds = totalMemory
            updateBufferSummary()
            if (recording) {
                recTime.text = formatShortTimer(recorded)
            }

            historySize.removeCallbacks(updater)
            historySize.postOnAnimationDelayed(updater, STATE_UPDATE_DELAY_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        val activity = activity ?: return
        serviceBound = activity.bindService(
            Intent(activity, TimeTravelService::class.java),
            recorderConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        if (this::historySize.isInitialized) {
            historySize.removeCallbacks(updater)
        }
        val activity = activity ?: return
        if (serviceBound) {
            activity.unbindService(recorderConnection)
            serviceBound = false
        }
        recorder = null
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            refreshConfiguredUi()
            recorder?.getState(serviceStateCallback)
        }
    }

    override fun onDestroyView() {
        if (this::historySize.isInitialized) {
            historySize.removeCallbacks(updater)
        }
        stopGlowAnimation()
        clearPressAnimations()
        savingSnackbar?.dismiss()
        savingSnackbar = null
        super.onDestroyView()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_background_recorder, container, false)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()

        applyWindowInsets(view)

        contentScroll = view.findViewById(R.id.content_scroll)
        brandLockup = view.findViewById(R.id.brand_lockup)
        historySize = view.findViewById(R.id.history_size)
        formatSummary = view.findViewById(R.id.format_summary)
        clearBufferButton = view.findViewById(R.id.clear_buffer_button)
        recordMaxButton = view.findViewById(R.id.record_last_max)
        recordCustomButton = view.findViewById(R.id.record_last_custom)
        reencodeHistoryButton = view.findViewById(R.id.reencode_history_button)
        recTime = view.findViewById(R.id.rec_button_circle)
        recTouchArea = view.findViewById(R.id.rec_touch_area)
        recButtonCircle = recTime
        listenSurface = view.findViewById(R.id.bottom_bar)
        listenRing = view.findViewById(R.id.listen_ring)
        listenTitle = view.findViewById(R.id.listen_title)


        historySize.typeface = Typeface.MONOSPACE
        recTime.typeface = Typeface.MONOSPACE
        configureListenSurface()

        settingsButton = view.findViewById(R.id.settings_button)
        installPressAnimation(brandLockup, 0.96f)
        installPressAnimation(settingsButton, 0.92f)
        installPressAnimation(clearBufferButton, 0.92f)
        installPressAnimation(listenSurface, 0.965f)
        installPressAnimation(recordMaxButton, 0.98f)
        installPressAnimation(recordCustomButton, 0.98f)
        installPressAnimation(reencodeHistoryButton, 0.98f)

        brandLockup.setOnClickListener {
            AboutInfoDialog.show(requireContext())
        }
        settingsButton.setOnClickListener {
            startActivity(
                Intent(activity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            activity.applyNoAnimationOpenTransition()
        }
        clearBufferButton.setOnClickListener {
            if (!isSaving && !isRecording) {
                showClearBufferDialog()
            }
        }
        listenSurface.setOnClickListener(ListenButtonClickListener())

        recTouchArea.setOnClickListener {
            val service = recorder ?: return@setOnClickListener
            if (isSaving) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setSavingInProgress(true)
            service.stopRecording(SaveResultReceiver(requireActivity()))
        }

        val exportClickListener = ExportButtonClickListener()
        recordMaxButton.setOnClickListener(exportClickListener)
        recordCustomButton.setOnClickListener(exportClickListener)
        reencodeHistoryButton.setOnClickListener {
            if (!isHistoryReencoding) {
                recorder?.startHistoryReencode()
            }
        }

        refreshConfiguredUi()
        updateListenSurfaceAppearance()
        serviceStateCallback.state(isListening, isRecording, 0f, 0f, 0f, false, false, 0L, 0L)
    }

    private fun applyWindowInsets(root: View) {
        val statusBar = root.findViewById<View>(R.id.top_bar)
        val content = root.findViewById<View>(R.id.content_scroll)
        val recordingSurface = root.findViewById<View>(R.id.rec_touch_area)
        val statusStart = statusBar.paddingStart
        val statusTop = statusBar.paddingTop
        val statusEnd = statusBar.paddingEnd
        val statusBottom = statusBar.paddingBottom
        val start = content.paddingStart
        val top = content.paddingTop
        val end = content.paddingEnd
        val bottom = content.paddingBottom
        val recordingStart = recordingSurface.paddingStart
        val recordingTop = recordingSurface.paddingTop
        val recordingEnd = recordingSurface.paddingEnd
        val recordingBottom = recordingSurface.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBar.updatePadding(
                left = statusStart + bars.left,
                top = statusTop + bars.top,
                right = statusEnd + bars.right,
                bottom = statusBottom,
            )
            content.updatePadding(
                left = start + bars.left,
                top = top,
                right = end + bars.right,
                bottom = bottom + bars.bottom,
            )
            recordingSurface.updatePadding(
                left = recordingStart + bars.left,
                top = recordingTop,
                right = recordingEnd + bars.right,
                bottom = recordingBottom + bars.bottom,
            )
            insets
        }
    }

    private fun updateRecordingState(recording: Boolean) {
        if (recording) {
            contentScroll.visibility = View.GONE
            recTouchArea.visibility = View.VISIBLE
        } else {
            recTouchArea.visibility = View.GONE
            contentScroll.visibility = View.VISIBLE
        }
    }

    private fun refreshConfiguredUi() {
        updateBufferSummary()
        updateActionButtons()
    }

    private fun updateActionButtons() {
        val exportBlocked = isRecording || isSaving || isHistoryReencodePending || isHistoryReencoding
        clearBufferButton.isEnabled = !isRecording && !isSaving && !isHistoryReencoding
        clearBufferButton.alpha = if (clearBufferButton.isEnabled) 1f else 0.5f
        recordMaxButton.isEnabled = !exportBlocked
        recordCustomButton.isEnabled = !exportBlocked
        recordMaxButton.isVisible = true
        recordCustomButton.isVisible = true
        reencodeHistoryButton.isVisible = isHistoryReencodePending || isHistoryReencoding
        reencodeHistoryButton.isEnabled = !isHistoryReencoding
        reencodeHistoryButton.text =
            if (isHistoryReencoding) {
                getString(
                    R.string.reencode_history_progress,
                    if (historyReencodeTotalBytes <= 0L) {
                        0
                    } else {
                        ((historyReencodeProcessedBytes * 100L) / historyReencodeTotalBytes).toInt().coerceIn(0, 100)
                    },
                )
            } else {
                getString(R.string.reencode_history)
            }
    }

    private fun updateBufferSummary() {
        val context = context ?: return
        val exportConfig = currentExportConfig(context)
        val retentionMode = getConfiguredRetentionMode(context)
        val displayedCurrentSeconds = lastMemorizedSeconds.coerceAtLeast(0f).toInt()
        val displayedLimitSeconds =
            when (retentionMode) {
                RetentionMode.TIME -> getConfiguredRetentionSeconds(context).coerceAtLeast(0L).toInt()
                RetentionMode.SIZE -> lastTotalMemorySeconds.coerceAtLeast(0f).toInt()
            }
        val currentBytes = estimateExportSizeBytes(
            exportConfig.format,
            exportConfig.codec,
            exportConfig.sampleRate,
            exportConfig.channelCount,
            displayedCurrentSeconds.toLong(),
            exportConfig.bitrateKbps,
        )
        val limitBytes = estimateExportSizeBytes(
            exportConfig.format,
            exportConfig.codec,
            exportConfig.sampleRate,
            exportConfig.channelCount,
            displayedLimitSeconds.toLong(),
            exportConfig.bitrateKbps,
        )
        val configuredLimitBytes =
            when (retentionMode) {
                RetentionMode.TIME -> limitBytes
                RetentionMode.SIZE -> getConfiguredRetentionSizeBytes(context)
            }
        val exportLimitBytes = exportFileSizeLimitBytes(exportConfig.format)
        val overExportLimit = currentBytes > exportLimitBytes
        when (retentionMode) {
            RetentionMode.TIME -> {
                historySize.text = "${formatShortTimer(displayedCurrentSeconds.toFloat())} / ${formatShortTimer(displayedLimitSeconds.toFloat())}"
                formatSummary.text =
                    if (overExportLimit) {
                        getString(R.string.export_limit_summary, formatShortFileSize(exportLimitBytes))
                    } else {
                        formatShortFileSize(currentBytes)
                    }
            }

            RetentionMode.SIZE -> {
                historySize.text = "${formatShortFileSize(currentBytes)} / ${formatShortFileSize(configuredLimitBytes)}"
                formatSummary.text =
                    if (overExportLimit) {
                        getString(R.string.export_limit_summary, formatShortFileSize(exportLimitBytes))
                    } else {
                        formatShortTimer(displayedCurrentSeconds.toFloat())
                    }
            }
        }
        formatSummary.setTextColor(
            MaterialColors.getColor(
                formatSummary,
                if (overExportLimit) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant,
            ),
        )
    }

    private fun configureListenSurface() {
        val context = requireContext()
        val circleShape = ShapeAppearanceModel.builder()
            .setAllCornerSizes(RelativeCornerSize(0.5f))
            .build()

        listenSurfaceDrawable = MaterialShapeDrawable(circleShape).apply {
            initializeElevationOverlay(context)
            strokeWidth = resources.displayMetrics.density
        }
        listenRingDrawable = MaterialShapeDrawable(circleShape).apply {
            strokeWidth = resources.displayMetrics.density * 0.85f
            fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeColor = ColorStateList.valueOf(
                MaterialColors.getColor(
                    listenSurface,
                    com.google.android.material.R.attr.colorOutlineVariant,
                ),
            )
        }
        listenSurface.background = listenSurfaceDrawable
        listenRing.background = listenRingDrawable
        listenSurface.foreground = RippleDrawable(
            ColorStateList.valueOf(
                MaterialColors.getColor(
                    listenSurface,
                    com.google.android.material.R.attr.colorSecondaryContainer,
                ),
            ),
            null,
            MaterialShapeDrawable(circleShape).apply {
                fillColor = ColorStateList.valueOf(Color.WHITE)
            },
        )
        
        recButtonCircle.background = MaterialShapeDrawable(circleShape).apply {
            fillColor = ColorStateList.valueOf(MaterialColors.getColor(
                recButtonCircle,
                com.google.android.material.R.attr.colorPrimaryContainer,
            ))
            strokeWidth = resources.displayMetrics.density
            strokeColor = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(
                        recButtonCircle,
                        com.google.android.material.R.attr.colorOutlineVariant,
                    ),
                    54,
                ),
            )
            initializeElevationOverlay(context)
        }
        recButtonCircle.foreground = RippleDrawable(
            ColorStateList.valueOf(MaterialColors.getColor(
                recButtonCircle,
                com.google.android.material.R.attr.colorOnPrimaryContainer,
            )),
            null,
            MaterialShapeDrawable(circleShape).apply {
                fillColor = ColorStateList.valueOf(Color.WHITE)
            },
        )
    }

    private fun installPressAnimation(
        target: View,
        pressedScale: Float,
    ) {
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(PRESS_ANIMATION_DURATION_MS)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE,
                -> {
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(PRESS_ANIMATION_DURATION_MS)
                        .start()
                }
            }
            false
        }
    }

    private fun clearPressAnimations() {
        listOf(brandLockup, settingsButton, clearBufferButton, listenSurface, recordMaxButton, recordCustomButton, reencodeHistoryButton).forEach { target ->
            target.animate().cancel()
            target.scaleX = 1f
            target.scaleY = 1f
        }
    }

    private fun showClearBufferDialog() {
        val confirmButton = ThemedDialog.createHeaderIconButton(
            context = requireContext(),
            iconResId = R.drawable.ic_check,
            contentDescription = getString(R.string.clear_buffer),
            tintAttr = androidx.appcompat.R.attr.colorError,
        )
        val handle = ThemedDialog.create(
            context = requireContext(),
            title = getString(R.string.clear_buffer),
            content = Space(requireContext()).apply { minimumHeight = dp(14) },
            positiveText = null,
            negativeText = null,
            headerAccessory = confirmButton,
            headerAccessoryGravity = Gravity.END,
            headerCloseSpacingDp = 8,
        )
        handle.actionRow.visibility = View.GONE
        confirmButton.setOnClickListener {
            handle.dialog.dismiss()
            recorder?.clearBuffer()
        }
        handle.dialog.show()
    }

    private fun updateListenSurfaceAppearance() {
        val active = isListening || isRecording
        listenTitle.setText(if (active) R.string.buffer_active_summary else R.string.buffer_inactive_summary)

        listenSurface.isEnabled = !isRecording && !isSaving && !isHistoryReencoding
        listenSurface.alpha = if (listenSurface.isEnabled) 1f else 0.72f
        updateActionButtons()

        val fillColor = MaterialColors.getColor(
            listenSurface,
            if (active) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorSurfaceContainerHigh,
        )
        val contentColor = MaterialColors.getColor(
            listenSurface,
            if (active) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurface,
        )
        val borderBaseColor = MaterialColors.getColor(
            listenSurface,
            if (active) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorOutlineVariant,
        )
        val ringBaseColor = MaterialColors.getColor(
            listenSurface,
            if (active) androidx.appcompat.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorOutlineVariant,
        )
        val ringFillColor = if (active) ColorUtils.setAlphaComponent(ringBaseColor, if (isRecording) 56 else 40) else Color.TRANSPARENT
        val strokeColor = ColorUtils.setAlphaComponent(borderBaseColor, if (active) 110 else 56)
        val ringStrokeColor = ColorUtils.setAlphaComponent(ringBaseColor, if (active) 160 else 44)
        listenSurfaceDrawable.fillColor = ColorStateList.valueOf(fillColor)
        listenSurfaceDrawable.strokeColor = ColorStateList.valueOf(strokeColor)
        listenRingDrawable.fillColor = ColorStateList.valueOf(ringFillColor)
        listenRingDrawable.strokeColor = ColorStateList.valueOf(ringStrokeColor)
        listenTitle.setTextColor(contentColor)
        TextViewCompat.setCompoundDrawableTintList(listenTitle, ColorStateList.valueOf(contentColor))
        listenRing.alpha = if (active) 1f else 0.7f
        listenRing.scaleX = 1f
        listenRing.scaleY = 1f

        startGlowAnimation(
            when {
                isRecording -> PULSE_RECORDING
                active -> PULSE_LIVE
                else -> PULSE_OFF
            },
        )
    }

    private fun startGlowAnimation(mode: Int) {
        if (currentPulseMode == mode) {
            return
        }

        stopGlowAnimation()
        currentPulseMode = mode
        if (mode == PULSE_OFF) {
            listenRing.scaleX = 1f
            listenRing.scaleY = 1f
            return
        }

        val recordingPulse = mode == PULSE_RECORDING
        val scaleTo = if (recordingPulse) 1.09f else 1.065f
        val duration = if (recordingPulse) 2200L else 3400L

        pulseAnimators = listOf(
            createGlowAnimator(listenRing, "scaleX", 1f, scaleTo, duration),
            createGlowAnimator(listenRing, "scaleY", 1f, scaleTo, duration),
        )
        pulseAnimators.forEach { it.start() }
    }

    private fun createGlowAnimator(
        target: View,
        property: String,
        from: Float,
        to: Float,
        duration: Long,
    ): ObjectAnimator {
        return ObjectAnimator.ofFloat(target, property, from, to).apply {
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            this.duration = duration
        }
    }

    private fun stopGlowAnimation() {
        pulseAnimators.forEach { it.cancel() }
        pulseAnimators = emptyList()
        currentPulseMode = PULSE_OFF
    }

    private inner class ListenButtonClickListener : View.OnClickListener {
        override fun onClick(v: View) {
            if (isSaving) return
            val service = recorder ?: return
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            isListening = !isListening
            updateListenSurfaceAppearance()
            updateBufferSummary()
            if (isListening) {
                service.enableListening()
            } else {
                service.disableListening()
            }
        }
    }

    private inner class ExportButtonClickListener : View.OnClickListener {
        override fun onClick(v: View) {
            export(v)
        }

        private fun export(
            button: View,
        ) {
            if (isSaving) return
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (button.id == R.id.record_last_custom) {
                promptForCustomRange()
                return
            }

            val currentSeconds = lastMemorizedSeconds.coerceAtLeast(0f)
            if (currentSeconds <= 0f) {
                Toast.makeText(requireContext(), R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                return
            }
            startExport(clampExportRange(0f, currentSeconds))
        }

        private fun promptForCustomRange() {
            val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_duration, FrameLayout(requireContext()), false)
            val prefs = getRecorderPreferences(requireContext())
            val scopeGroup = content.findViewById<MaterialButtonToggleGroup>(R.id.custom_export_scope_group)
            val unitGroup = content.findViewById<MaterialButtonToggleGroup>(R.id.custom_export_unit_group)
            val rangeScopeButton = content.findViewById<MaterialButton>(R.id.custom_export_scope_range_button)
            val pastScopeButton = content.findViewById<MaterialButton>(R.id.custom_export_scope_past_button)
            val timeUnitButton = content.findViewById<MaterialButton>(R.id.custom_export_unit_time_button)
            val sizeUnitButton = content.findViewById<MaterialButton>(R.id.custom_export_unit_size_button)
            val rangeTimePanel = content.findViewById<View>(R.id.custom_range_time_panel)
            val rangeSizePanel = content.findViewById<View>(R.id.custom_range_size_panel)
            val pastTimePanel = content.findViewById<View>(R.id.custom_past_time_panel)
            val pastSizePanel = content.findViewById<View>(R.id.custom_past_size_panel)
            val startTimeLayout = content.findViewById<TextInputLayout>(R.id.custom_start_layout)
            val endTimeLayout = content.findViewById<TextInputLayout>(R.id.custom_end_layout)
            val startTimeField = content.findViewById<TextInputEditText>(R.id.custom_start_value)
            val endTimeField = content.findViewById<TextInputEditText>(R.id.custom_end_value)
            val startSizeLayout = content.findViewById<TextInputLayout>(R.id.custom_start_size_layout)
            val endSizeLayout = content.findViewById<TextInputLayout>(R.id.custom_end_size_layout)
            val startSizeField = content.findViewById<TextInputEditText>(R.id.custom_start_size_value)
            val endSizeField = content.findViewById<TextInputEditText>(R.id.custom_end_size_value)
            val pastTimeLayout = content.findViewById<TextInputLayout>(R.id.custom_past_time_layout)
            val pastTimeField = content.findViewById<TextInputEditText>(R.id.custom_past_time_value)
            val pastSizeLayout = content.findViewById<TextInputLayout>(R.id.custom_past_size_layout)
            val pastSizeField = content.findViewById<TextInputEditText>(R.id.custom_past_size_value)
            val rangeTimeConfirmButton = content.findViewById<View>(R.id.custom_range_time_confirm_button)
            val rangeSizeConfirmButton = content.findViewById<View>(R.id.custom_range_size_confirm_button)
            val pastTimeConfirmButton = content.findViewById<View>(R.id.custom_past_time_confirm_button)
            val pastSizeConfirmButton = content.findViewById<View>(R.id.custom_past_size_confirm_button)

            listOf(startTimeField, endTimeField, startSizeField, endSizeField, pastTimeField, pastSizeField).forEach {
                it.setSelectAllOnFocus(true)
            }
            startTimeField.setText("0:00")
            endTimeField.setText(formatDurationInput(lastMemorizedSeconds.coerceAtLeast(0f).roundToInt()))
            val currentBytes = currentBufferExportBytes()
            startSizeField.setText(formatSizeInputMib(0L))
            endSizeField.setText(formatSizeInputMib(currentBytes))
            val lastPastSeconds = prefs.getInt(TimeTravelConfig.CUSTOM_EXPORT_PAST_SECONDS_KEY, 5 * 60).coerceAtLeast(1)
            pastTimeField.setText(formatDurationInput(lastPastSeconds))
            val lastPastSizeMib = prefs.getString(TimeTravelConfig.CUSTOM_EXPORT_PAST_SIZE_MIB_KEY, formatSizeInputMib(currentBytes)) ?: formatSizeInputMib(currentBytes)
            pastSizeField.setText(lastPastSizeMib)

            fun currentModeKey(): String =
                if (scopeGroup.checkedButtonId == rangeScopeButton.id) TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE else TimeTravelConfig.CUSTOM_EXPORT_MODE_PAST

            fun currentUnitKey(): String =
                if (unitGroup.checkedButtonId == timeUnitButton.id) TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME else TimeTravelConfig.CUSTOM_EXPORT_UNIT_SIZE

            fun refreshPanels() {
                val rangeMode = currentModeKey() == TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE
                val timeUnit = currentUnitKey() == TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME
                rangeTimePanel.visibility = if (rangeMode && timeUnit) View.VISIBLE else View.GONE
                rangeSizePanel.visibility = if (rangeMode && !timeUnit) View.VISIBLE else View.GONE
                pastTimePanel.visibility = if (!rangeMode && timeUnit) View.VISIBLE else View.GONE
                pastSizePanel.visibility = if (!rangeMode && !timeUnit) View.VISIBLE else View.GONE
            }

            val initialModeKey = prefs.getString(
                TimeTravelConfig.CUSTOM_EXPORT_MODE_KEY,
                TimeTravelConfig.CUSTOM_EXPORT_MODE_PAST,
            ) ?: TimeTravelConfig.CUSTOM_EXPORT_MODE_PAST
            val initialUnitKey = prefs.getString(
                TimeTravelConfig.CUSTOM_EXPORT_UNIT_KEY,
                TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME,
            ) ?: TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME
            scopeGroup.check(
                if (initialModeKey == TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE) rangeScopeButton.id else pastScopeButton.id,
            )
            unitGroup.check(
                if (initialUnitKey == TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME) timeUnitButton.id else sizeUnitButton.id,
            )
            refreshPanels()

            scopeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val modeKey =
                    if (checkedId == rangeScopeButton.id) TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE
                    else TimeTravelConfig.CUSTOM_EXPORT_MODE_PAST
                prefs.edit().putString(TimeTravelConfig.CUSTOM_EXPORT_MODE_KEY, modeKey).apply()
                refreshPanels()
            }
            unitGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val unitKey =
                    if (checkedId == timeUnitButton.id) TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME
                    else TimeTravelConfig.CUSTOM_EXPORT_UNIT_SIZE
                prefs.edit().putString(TimeTravelConfig.CUSTOM_EXPORT_UNIT_KEY, unitKey).apply()
                refreshPanels()
            }

            val handle = ThemedDialog.create(
                context = requireContext(),
                title = getString(R.string.export),
                content = content,
                positiveText = null,
                negativeText = null,
            )

            fun submit(): Boolean {
                val currentSeconds = lastMemorizedSeconds.coerceAtLeast(0f)
                if (currentSeconds <= 0f) {
                    Toast.makeText(requireContext(), R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
                    return false
                }
                val startSeconds = parseDurationInput(startTimeField.text?.toString().orEmpty())?.toFloat()
                val endSeconds = parseDurationInput(endTimeField.text?.toString().orEmpty())?.toFloat()
                val pastSeconds = parseDurationInput(pastTimeField.text?.toString().orEmpty())?.toFloat()
                val startSizeBytes = parseSizeInputMib(startSizeField.text?.toString().orEmpty())
                val endSizeBytes = parseSizeInputMib(endSizeField.text?.toString().orEmpty())
                val pastSizeBytes = parseSizeInputMib(pastSizeField.text?.toString().orEmpty())
                listOf(startTimeLayout, endTimeLayout, startSizeLayout, endSizeLayout, pastTimeLayout, pastSizeLayout).forEach { it.error = null }

                val rangeMode = currentModeKey() == TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE
                val timeUnit = currentUnitKey() == TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME

                if (rangeMode && timeUnit) {
                    if (startSeconds == null || startSeconds < 0f) {
                        startTimeLayout.error = getString(R.string.retention_time_invalid)
                        return false
                    }
                    if (endSeconds == null || endSeconds <= 0f) {
                        endTimeLayout.error = getString(R.string.retention_time_invalid)
                        return false
                    }
                    if (endSeconds <= startSeconds || startSeconds > currentSeconds || endSeconds > currentSeconds) {
                        endTimeLayout.error = getString(R.string.custom_export_range_invalid)
                        return false
                    }
                    handle.dialog.dismiss()
                    return startExport(clampExportRange(startSeconds, endSeconds))
                }

                if (rangeMode && !timeUnit) {
                    if (startSizeBytes == null || startSizeBytes < 0L) {
                        startSizeLayout.error = getString(R.string.custom_export_size_invalid)
                        return false
                    }
                    if (endSizeBytes == null || endSizeBytes <= 0L) {
                        endSizeLayout.error = getString(R.string.custom_export_size_invalid)
                        return false
                    }
                    if (endSizeBytes <= startSizeBytes || startSizeBytes > currentBytes || endSizeBytes > currentBytes) {
                        endSizeLayout.error = getString(R.string.custom_export_range_invalid)
                        return false
                    }
                    handle.dialog.dismiss()
                    return startExport(
                        clampExportRange(
                            sizeBytesToExportSeconds(startSizeBytes),
                            sizeBytesToExportSeconds(endSizeBytes),
                        ),
                    )
                }

                if (!rangeMode && timeUnit) {
                    if (pastSeconds == null || pastSeconds <= 0f) {
                        pastTimeLayout.error = getString(R.string.retention_time_invalid)
                        return false
                    }
                    prefs.edit().putInt(TimeTravelConfig.CUSTOM_EXPORT_PAST_SECONDS_KEY, pastSeconds.roundToInt()).apply()
                    handle.dialog.dismiss()
                    return startExport(clampExportRange((currentSeconds - pastSeconds).coerceAtLeast(0f), currentSeconds))
                }

                if (pastSizeBytes == null || pastSizeBytes <= 0L) {
                    pastSizeLayout.error = getString(R.string.custom_export_size_invalid)
                    return false
                }
                if (pastSizeBytes > currentBytes) {
                    pastSizeLayout.error = getString(R.string.custom_export_range_invalid)
                    return false
                }
                prefs.edit().putString(TimeTravelConfig.CUSTOM_EXPORT_PAST_SIZE_MIB_KEY, formatSizeInputMib(pastSizeBytes)).apply()
                handle.dialog.dismiss()
                val pastSizeSeconds = sizeBytesToExportSeconds(pastSizeBytes)
                return startExport(clampExportRange((currentSeconds - pastSizeSeconds).coerceAtLeast(0f), currentSeconds))
            }
            listOf(
                rangeTimeConfirmButton,
                rangeSizeConfirmButton,
                pastTimeConfirmButton,
                pastSizeConfirmButton,
            ).forEach { button ->
                button.setOnClickListener { submit() }
            }
            val submitListener = TextView.OnEditorActionListener { _, actionId, event ->
                if (
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    submit()
                } else {
                    false
                }
            }
            listOf(endTimeField, endSizeField, pastTimeField, pastSizeField).forEach { it.setOnEditorActionListener(submitListener) }

            handle.dialog.show()
            val initialField = when {
                currentModeKey() == TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE && currentUnitKey() == TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME -> startTimeField
                currentModeKey() == TimeTravelConfig.CUSTOM_EXPORT_MODE_RANGE -> startSizeField
                currentUnitKey() == TimeTravelConfig.CUSTOM_EXPORT_UNIT_TIME -> pastTimeField
                else -> pastSizeField
            }
            initialField.post {
                initialField.requestFocus()
                initialField.selectAll()
                val imm =
                    context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(initialField, 0)
            }
        }

        private fun startExport(range: ExportRange): Boolean {
            val service = recorder ?: return false
            if (range.warningDurationSeconds != null) {
                showExportClampDialog(range.warningDurationSeconds) {
                    setSavingInProgress(true)
                    service.dumpRecordingRange(range.startSeconds, range.endSeconds, SaveResultReceiver(requireActivity()), "")
                }
                return true
            }
            setSavingInProgress(true)
            service.dumpRecordingRange(range.startSeconds, range.endSeconds, SaveResultReceiver(requireActivity()), "")
            return true
        }

        private fun clampExportRange(
            startSeconds: Float,
            endSeconds: Float,
        ): ExportRange {
            val context = requireContext()
            val exportConfig = currentExportConfig(context)
            val maxDurationSeconds = exportDurationLimitSeconds(
                exportConfig.format,
                exportConfig.codec,
                exportConfig.sampleRate,
                exportConfig.channelCount,
                exportConfig.bitrateKbps,
            ).toFloat().coerceAtLeast(1f)
            val boundedEnd = endSeconds.coerceAtLeast(startSeconds)
            val requestedDuration = boundedEnd - startSeconds
            if (requestedDuration <= maxDurationSeconds) {
                return ExportRange(startSeconds, boundedEnd, null)
            }
            return ExportRange(
                startSeconds = (boundedEnd - maxDurationSeconds).coerceAtLeast(0f),
                endSeconds = boundedEnd,
                warningDurationSeconds = maxDurationSeconds,
            )
        }

        private fun currentBufferExportBytes(): Long {
            val context = requireContext()
            val exportConfig = currentExportConfig(context)
            return estimateExportSizeBytes(
                exportConfig.format,
                exportConfig.codec,
                exportConfig.sampleRate,
                exportConfig.channelCount,
                lastMemorizedSeconds.coerceAtLeast(0f).toLong(),
                exportConfig.bitrateKbps,
            )
        }

        private fun sizeBytesToExportSeconds(sizeBytes: Long): Float {
            val context = requireContext()
            val exportConfig = currentExportConfig(context)
            return estimateExportDurationSeconds(
                exportConfig.format,
                exportConfig.codec,
                exportConfig.sampleRate,
                exportConfig.channelCount,
                sizeBytes,
                exportConfig.bitrateKbps,
            ).toFloat()
        }

        private fun parseSizeInputMib(value: String): Long? {
            val mib = value.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (mib < 0.0) return null
            return (mib * 1024.0 * 1024.0).roundToLong()
        }

        private fun formatSizeInputMib(sizeBytes: Long): String {
            val mebibytes = sizeBytes.coerceAtLeast(0L) / (1024.0 * 1024.0)
            return DecimalFormat("0.0", DecimalFormatSymbols(Locale.US)).format(mebibytes)
        }

        private fun showExportClampDialog(
            clampedDurationSeconds: Float,
            onProceed: () -> Unit,
        ) {
            val content = TextView(requireContext()).apply {
                setPadding(dp(24), dp(16), dp(24), 0)
                text = getString(R.string.export_limit_dialog_message, formatShortTimer(clampedDurationSeconds))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            val handle = ThemedDialog.create(
                context = requireContext(),
                title = getString(R.string.export_limit_dialog_title),
                content = content,
                positiveText = getString(R.string.export),
                negativeText = null,
            )
            handle.positiveButton.setOnClickListener {
                handle.dialog.dismiss()
                onProceed()
            }
            handle.dialog.show()
        }
    }

    private fun setSavingInProgress(inProgress: Boolean) {
        isSaving = inProgress
        if (
            !this::recordMaxButton.isInitialized ||
            !this::recordCustomButton.isInitialized ||
            !this::reencodeHistoryButton.isInitialized ||
            !this::settingsButton.isInitialized ||
            !this::recTouchArea.isInitialized
        ) {
            return
        }
        val enabled = !inProgress
        recordMaxButton.isEnabled = enabled && !isHistoryReencodePending && !isHistoryReencoding
        recordCustomButton.isEnabled = enabled && !isHistoryReencodePending && !isHistoryReencoding
        reencodeHistoryButton.isEnabled = enabled && !isHistoryReencoding
        settingsButton.isEnabled = enabled
        recTouchArea.isEnabled = enabled
        if (inProgress) {
            showSavingSnackbar()
        } else {
            savingSnackbar?.dismiss()
            savingSnackbar = null
        }
        updateListenSurfaceAppearance()
        updateActionButtons()
    }

    private fun showSavingSnackbar() {
        val root = view ?: return
        val anchor = activity?.findViewById<View>(R.id.bottom_navigation)
        savingSnackbar?.dismiss()
        savingSnackbar = Snackbar.make(root, R.string.saving, Snackbar.LENGTH_INDEFINITE).apply {
            anchor?.let { setAnchorView(it) }
            setAction(android.R.string.cancel) {
                recorder?.cancelCurrentExport()
            }
            show()
        }
    }

    private fun showSavedSnackbar(recording: RecordingEntity) {
        val root = view ?: return
        val anchor = activity?.findViewById<View>(R.id.bottom_navigation)
        Snackbar.make(root, R.string.saved_snackbar, Snackbar.LENGTH_LONG)
            .setAction(R.string.open) {
                try {
                    startActivity(buildOpenRecordingIntent(requireContext(), recording))
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), R.string.no_app_available, Toast.LENGTH_SHORT).show()
                }
            }
            .apply { anchor?.let { setAnchorView(it) } }
            .show()
    }

    private fun currentExportConfig(context: Context): ExportUiConfig {
        val activeConfig = recorder?.getConfigurationSnapshot()
        val format = activeConfig?.format ?: getConfiguredOutputFormat(context)
        val codec = activeConfig?.codec ?: getConfiguredOutputCodec(context)
        val sourceMode = activeConfig?.sourceMode ?: getConfiguredAudioSourceMode(context)
        val channelMode = activeConfig?.channelMode ?: getConfiguredChannelMode(context)
        val routeMode = activeConfig?.routeMode ?: getConfiguredInputRouteMode(context)
        val sampleRate = activeConfig?.sampleRate ?: getConfiguredSampleRate(context, sourceMode, routeMode, format, codec, channelMode)
        return ExportUiConfig(
            format = format,
            codec = codec,
            sampleRate = sampleRate,
            channelCount = channelMode.channelCount,
            bitrateKbps = getConfiguredCodecBitrateKbps(context, codec, sampleRate, channelMode.channelCount),
        )
    }

    companion object {
        private const val TAG = "TimeTravelFragment"
        private const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        private const val PULSE_OFF = 0
        private const val PULSE_LIVE = 1
        private const val PULSE_RECORDING = 2
        private const val STATE_UPDATE_DELAY_MS = 150L
        private const val PRESS_ANIMATION_DURATION_MS = 110L

        fun buildNotificationForFile(
            context: Context,
            recording: RecordingEntity,
        ): Notification {
            val intent = buildOpenRecordingIntent(context, recording)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            return NotificationCompat.Builder(context, TimeTravelService.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.recording_saved))
                .setContentText(recording.displayName)
                .setSmallIcon(R.drawable.ic_notification_saved)
                .setTicker(recording.displayName)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        }
    }

    private data class ExportRange(
        val startSeconds: Float,
        val endSeconds: Float,
        val warningDurationSeconds: Float?,
    )

    private data class ExportUiConfig(
        val format: ExportFormat,
        val codec: ExportCodec,
        val sampleRate: Int,
        val channelCount: Int,
        val bitrateKbps: Int?,
    )

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    class NotifyFileReceiver(private val context: Context) : TimeTravelService.AudioFileReceiver {
        override fun fileReady(recording: RecordingEntity) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val saved = RecordingRepository.register(context, recording)
                if (
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return@launch
                }
                NotificationManagerCompat.from(context).notify(43, buildNotificationForFile(context, saved))
            }
        }
    }

    private inner class SaveResultReceiver(private val activity: FragmentActivity) : TimeTravelService.AudioFileReceiver {
        override fun fileReady(recording: RecordingEntity) {
            activity.lifecycleScope.launch {
                val saved = RecordingRepository.register(activity.applicationContext, recording)
                if (view == null || !isAdded || !this@TimeTravelFragment::recordMaxButton.isInitialized) {
                    isSaving = false
                    return@launch
                }
                setSavingInProgress(false)
                showSavedSnackbar(saved)
            }
        }

        override fun fileFailed(message: String) {
            if (view == null || !isAdded || !this@TimeTravelFragment::recordMaxButton.isInitialized) {
                isSaving = false
            } else {
                setSavingInProgress(false)
            }
            Toast.makeText(activity, if (message.isBlank()) activity.getString(R.string.save_failed) else message, Toast.LENGTH_SHORT)
                .show()
        }

        override fun fileCancelled() {
            if (view == null || !isAdded || !this@TimeTravelFragment::recordMaxButton.isInitialized) {
                isSaving = false
            } else {
                setSavingInProgress(false)
            }
        }
    }
}
