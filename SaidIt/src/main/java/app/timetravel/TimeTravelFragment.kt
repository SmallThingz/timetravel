package app.timetravel

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
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.textfield.TextInputLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.appcompat.app.AppCompatDialog
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

@SuppressLint("ImplicitSamInstance")
class TimeTravelFragment : Fragment() {
    private lateinit var recordMaxButton: MaterialButton
    private lateinit var recordCustomButton: MaterialButton
    private lateinit var contentScroll: View
    private lateinit var historySize: TextView
    private lateinit var recTime: TextView
    private lateinit var formatSummary: TextView
    private lateinit var clearBufferButton: MaterialButton
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
    private var recorder: TimeTravelService? = null
    private var serviceBound = false
    private var currentPulseMode = PULSE_OFF
    private var pulseAnimators: List<ObjectAnimator> = emptyList()
    private val springAnimations = mutableListOf<SpringAnimation>()
    private var savingSnackbar: Snackbar? = null

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
        }
    }

    private val serviceStateCallback: TimeTravelService.StateCallback = object : TimeTravelService.StateCallback {
        override fun state(
            listeningEnabled: Boolean,
            recording: Boolean,
            memorized: Float,
            totalMemory: Float,
            recorded: Float,
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
            if (surfaceStateChanged) {
                updateListenSurfaceAppearance()
            }

            updateBufferSummary()
            historySize.text = formatShortTimer(memorized)

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
        historySize = view.findViewById(R.id.history_size)
        formatSummary = view.findViewById(R.id.format_summary)
        clearBufferButton = view.findViewById(R.id.clear_buffer_button)
        recordMaxButton = view.findViewById(R.id.record_last_max)
        recordCustomButton = view.findViewById(R.id.record_last_custom)
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
        installPressAnimation(settingsButton, 0.92f)
        installPressAnimation(listenSurface, 0.965f)
        installPressAnimation(recordMaxButton, 0.98f)
        installPressAnimation(recordCustomButton, 0.98f)

        settingsButton.setOnClickListener {
            startActivity(
                Intent(activity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            activity.overridePendingTransition(0, 0)
        }
        clearBufferButton.setOnClickListener {
            if (!isSaving && !isRecording) {
                recorder?.clearBuffer()
            }
        }
        listenSurface.setOnClickListener(ListenButtonClickListener())

        recTouchArea.setOnClickListener {
            if (isSaving) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setSavingInProgress(true)
            recorder?.stopRecording(SaveResultReceiver(requireActivity()))
        }

        val exportClickListener = ExportButtonClickListener()
        recordMaxButton.setOnClickListener(exportClickListener)
        recordCustomButton.setOnClickListener(exportClickListener)

        refreshConfiguredUi()
        updateListenSurfaceAppearance()
        serviceStateCallback.state(isListening, isRecording, 0f, 0f, 0f)
    }

    private fun applyWindowInsets(root: View) {
        val statusBar = root.findViewById<View>(R.id.status_bar)
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
        clearBufferButton.isEnabled = !isRecording && !isSaving
        clearBufferButton.alpha = if (isRecording || isSaving) 0.5f else 1f
    }

    private fun updateBufferSummary() {
        val context = context ?: return
        if (!isListening && !isRecording) {
            formatSummary.text = getString(R.string.buffer_status_paused)
            return
        }

        val activeConfig = recorder?.getConfigurationSnapshot()
        val codec = activeConfig?.codec ?: getConfiguredOutputCodec(context)
        val sourceMode = activeConfig?.sourceMode ?: getConfiguredAudioSourceMode(context)
        val channelMode = activeConfig?.channelMode ?: getConfiguredChannelMode(context)
        val routeMode = activeConfig?.routeMode ?: getConfiguredInputRouteMode(context)
        val sampleRate = activeConfig?.sampleRate ?: getConfiguredSampleRate(context, sourceMode, routeMode, codec, channelMode)
        formatSummary.text = when (getConfiguredRetentionMode(context)) {
            RetentionMode.TIME -> getString(
                R.string.buffer_status_time,
                formatDurationInput(getConfiguredRetentionSeconds(context).toInt()),
            )

            RetentionMode.SIZE -> getString(
                R.string.buffer_status_size,
                formatShortFileSize(getConfiguredMemorySizeBytes(context, sampleRate, channelMode)),
            )
        }
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
        val scaleX = SpringAnimation(target, DynamicAnimation.SCALE_X).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
        }
        val scaleY = SpringAnimation(target, DynamicAnimation.SCALE_Y).apply {
            spring = SpringForce(1f).apply {
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = SpringForce.STIFFNESS_HIGH
            }
        }
        springAnimations += scaleX
        springAnimations += scaleY
        target.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scaleX.animateToFinalPosition(pressedScale)
                    scaleY.animateToFinalPosition(pressedScale)
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE,
                -> {
                    scaleX.animateToFinalPosition(1f)
                    scaleY.animateToFinalPosition(1f)
                }
            }
            false
        }
    }

    private fun clearPressAnimations() {
        springAnimations.forEach { it.cancel() }
        springAnimations.clear()
    }

    private fun updateListenSurfaceAppearance() {
        val active = isListening || isRecording
        listenTitle.setText(if (active) R.string.buffer_active_summary else R.string.buffer_inactive_summary)

        listenSurface.isEnabled = !isRecording && !isSaving
        clearBufferButton.isEnabled = !isRecording && !isSaving
        clearBufferButton.alpha = if (isRecording || isSaving) 0.5f else 1f

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
                promptForCustomDuration()
                return
            }

            val seconds = getPrependedSeconds(button)
            if (seconds <= 0f) {
                Toast.makeText(requireContext(), R.string.custom_export_duration_invalid, Toast.LENGTH_SHORT).show()
                return
            }

            val service = recorder ?: return
            setSavingInProgress(true)
            service.dumpRecording(seconds, SaveResultReceiver(requireActivity()), "")
        }

        private fun promptForCustomDuration() {
            val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_duration, null, false)
            val durationLayout = content.findViewById<TextInputLayout>(R.id.custom_duration_layout)
            val durationField = content.findViewById<TextInputEditText>(R.id.custom_duration_value)
            durationField.setSelectAllOnFocus(true)

            val handle = ThemedDialog.create(
                context = requireContext(),
                title = getString(R.string.custom_time),
                content = content,
                positiveText = getString(R.string.export),
            )

            fun submit(): Boolean {
                val seconds = parseDurationInput(durationField.text?.toString().orEmpty())?.toFloat()
                if (seconds == null || seconds <= 0f) {
                    durationLayout.error = getString(R.string.custom_export_duration_invalid)
                    return false
                }

                durationLayout.error = null
                val service = recorder ?: return false
                setSavingInProgress(true)
                service.dumpRecording(seconds, SaveResultReceiver(requireActivity()), "")
                handle.dialog.dismiss()
                return true
            }
            handle.negativeButton.setOnClickListener { handle.dialog.dismiss() }
            handle.positiveButton.setOnClickListener { submit() }
            durationField.setOnEditorActionListener { _, actionId, event ->
                if (
                    actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    event?.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    submit()
                } else {
                    false
                }
            }

            handle.dialog.show()
            durationField.post {
                durationField.requestFocus()
                durationField.selectAll()
                val imm =
                    context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(durationField, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        private fun getPrependedSeconds(button: View): Float {
            return when (button.id) {
                R.id.record_last_max -> FULL_BUFFER_SECONDS
                else -> 0f
            }
        }
    }

    private fun setSavingInProgress(inProgress: Boolean) {
        isSaving = inProgress
        val enabled = !inProgress
        recordMaxButton.isEnabled = enabled
        recordCustomButton.isEnabled = enabled
        settingsButton.isEnabled = enabled
        recTouchArea.isEnabled = enabled
        if (inProgress) {
            showSavingSnackbar()
        } else {
            savingSnackbar?.dismiss()
            savingSnackbar = null
        }
        updateListenSurfaceAppearance()
    }

    private fun showSavingSnackbar() {
        val anchor = activity?.findViewById<View>(R.id.bottom_navigation)
        savingSnackbar?.dismiss()
        savingSnackbar = Snackbar.make(requireView(), R.string.saving, Snackbar.LENGTH_INDEFINITE).apply {
            anchor?.let { setAnchorView(it) }
            show()
        }
    }

    private fun showSavedSnackbar(recording: RecordingEntity) {
        val anchor = activity?.findViewById<View>(R.id.bottom_navigation)
        Snackbar.make(requireView(), R.string.saved_snackbar, Snackbar.LENGTH_LONG)
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

    companion object {
        private const val TAG = "TimeTravelFragment"
        private const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f
        private const val PULSE_OFF = 0
        private const val PULSE_LIVE = 1
        private const val PULSE_RECORDING = 2
        private const val STATE_UPDATE_DELAY_MS = 150L

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
                val saved = RecordingRepository.register(activity, recording)
                setSavingInProgress(false)
                showSavedSnackbar(saved)
            }
        }

        override fun fileFailed(message: String) {
            setSavingInProgress(false)
            Toast.makeText(activity, if (message.isBlank()) activity.getString(R.string.save_failed) else message, Toast.LENGTH_SHORT)
                .show()
        }
    }
}
