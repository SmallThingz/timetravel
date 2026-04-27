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
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import java.io.File

@SuppressLint("ImplicitSamInstance")
class TimeTravelFragment : Fragment() {
    private lateinit var presetButtons: List<MaterialButton>
    private lateinit var recordMaxButton: MaterialButton
    private lateinit var recordCustomButton: MaterialButton
    private lateinit var contentScroll: View
    private lateinit var historySize: TextView
    private lateinit var recTime: TextView
    private lateinit var formatSummary: TextView
    private lateinit var recTouchArea: View
    private lateinit var recButtonCircle: View
    private lateinit var listenSurface: View
    private lateinit var listenRing: View
    private lateinit var listenGlowPrimary: View
    private lateinit var listenGlowSecondary: View
    private lateinit var listenTitle: TextView
    private lateinit var listenCaption: TextView
    private lateinit var settingsButton: View
    private lateinit var listenSurfaceDrawable: MaterialShapeDrawable
    private lateinit var listenRingDrawable: MaterialShapeDrawable

    private var isListening = true
    private var isRecording = false
    private var isSaving = false
    private var recorder: TimeTravelService? = null
    private var serviceBound = false
    private var exportPresets = IntArray(4)
    private var currentPulseMode = PULSE_OFF
    private var glowAnimators: List<ObjectAnimator> = emptyList()
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
        recordMaxButton = view.findViewById(R.id.record_last_max)
        recordCustomButton = view.findViewById(R.id.record_last_custom)
        recTime = view.findViewById(R.id.rec_time)
        recTouchArea = view.findViewById(R.id.rec_touch_area)
        recButtonCircle = view.findViewById(R.id.rec_button_circle)
        listenSurface = view.findViewById(R.id.bottom_bar)
        listenRing = view.findViewById(R.id.listen_ring)
        listenGlowPrimary = view.findViewById(R.id.listen_glow_primary)
        listenGlowSecondary = view.findViewById(R.id.listen_glow_secondary)
        listenTitle = view.findViewById(R.id.listen_title)
        listenCaption = view.findViewById(R.id.listen_caption)

        presetButtons = listOf(
            view.findViewById(R.id.record_preset_1),
            view.findViewById(R.id.record_preset_2),
            view.findViewById(R.id.record_preset_3),
            view.findViewById(R.id.record_preset_4),
        )

        historySize.typeface = Typeface.MONOSPACE
        recTime.typeface = Typeface.MONOSPACE
        configureListenSurface()

        settingsButton = view.findViewById(R.id.settings_button)
        installPressAnimation(settingsButton, 0.92f)
        installPressAnimation(listenSurface, 0.965f)
        presetButtons.forEach { installPressAnimation(it, 0.98f) }
        installPressAnimation(recordMaxButton, 0.98f)
        installPressAnimation(recordCustomButton, 0.98f)

        settingsButton.setOnClickListener {
            startActivity(Intent(activity, SettingsActivity::class.java))
        }
        listenSurface.setOnClickListener(ListenButtonClickListener())

        recTouchArea.setOnClickListener {
            if (isSaving) return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            setSavingInProgress(true)
            recorder?.stopRecording(SaveResultReceiver(requireActivity()))
        }

        val exportClickListener = ExportButtonClickListener()
        presetButtons.forEach { button ->
            button.setOnClickListener(exportClickListener)
            button.setOnLongClickListener(exportClickListener)
        }
        recordMaxButton.setOnClickListener(exportClickListener)
        recordMaxButton.setOnLongClickListener(exportClickListener)
        recordCustomButton.setOnClickListener(exportClickListener)
        recordCustomButton.setOnLongClickListener(exportClickListener)

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
        val context = context ?: return
        exportPresets = getConfiguredExportPresets(context)
        presetButtons.zip(exportPresets.toList()).forEach { (button, seconds) ->
            button.text = formatDurationInput(seconds)
            button.tag = seconds
        }
        updateBufferSummary()
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
        val routeMode = activeConfig?.routeMode ?: getConfiguredInputRouteMode(context)
        val sampleRate = activeConfig?.sampleRate ?: getConfiguredSampleRate(context, sourceMode, routeMode, codec)
        formatSummary.text = when (getConfiguredRetentionMode(context)) {
            RetentionMode.TIME -> getString(
                R.string.buffer_status_time,
                formatDurationInput(getConfiguredRetentionSeconds(context).toInt()),
            )

            RetentionMode.SIZE -> getString(
                R.string.buffer_status_size,
                formatShortFileSize(getConfiguredMemorySizeBytes(context, sampleRate)),
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
            fillColor = ColorStateList.valueOf(Color.TRANSPARENT)
            strokeWidth = resources.displayMetrics.density
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
        listenCaption.setText(
            when {
                isRecording -> R.string.buffer_recording_hint
                active -> R.string.buffer_active_hint
                else -> R.string.buffer_inactive_hint
            },
        )
        listenSurface.isEnabled = !isRecording && !isSaving

        val fillColor = MaterialColors.getColor(
            listenSurface,
            if (active) com.google.android.material.R.attr.colorPrimaryContainer
            else com.google.android.material.R.attr.colorSurfaceContainerHighest,
        )
        val contentColor = MaterialColors.getColor(
            listenSurface,
            if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurface,
        )
        val titleColor = MaterialColors.getColor(
            listenSurface,
            if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
            else com.google.android.material.R.attr.colorOnSurface,
        )
        val captionColor = MaterialColors.getColor(
            listenSurface,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        val strokeColor = MaterialColors.getColor(
            listenSurface,
            if (active) R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant,
        )
        listenSurfaceDrawable.fillColor = ColorStateList.valueOf(fillColor)
        listenSurfaceDrawable.strokeColor = ColorStateList.valueOf(strokeColor)
        listenRingDrawable.strokeColor = ColorStateList.valueOf(strokeColor)
        listenTitle.setTextColor(titleColor)
        TextViewCompat.setCompoundDrawableTintList(listenTitle, ColorStateList.valueOf(contentColor))
        listenCaption.setTextColor(captionColor)

        val primaryAlpha = when {
            isRecording -> 0.96f
            active -> 0.68f
            else -> 0.08f
        }
        val secondaryAlpha = when {
            isRecording -> 0.58f
            active -> 0.28f
            else -> 0.04f
        }
        val baseScale = if (active) 1f else 0.94f
        listenGlowPrimary.animate()
            .alpha(primaryAlpha)
            .scaleX(baseScale)
            .scaleY(baseScale)
            .setDuration(220L)
            .start()
        listenGlowSecondary.animate()
            .alpha(secondaryAlpha)
            .scaleX(baseScale)
            .scaleY(baseScale)
            .setDuration(220L)
            .start()

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
            return
        }

        val livePulse = mode == PULSE_RECORDING
        val primaryScaleTo = if (livePulse) 1.18f else 1.1f
        val secondaryScaleTo = if (livePulse) 1.08f else 1.02f
        val primaryAlphaTo = if (livePulse) 1f else 0.84f
        val secondaryAlphaTo = if (livePulse) 0.7f else 0.46f
        val duration = if (livePulse) 1700L else 2600L

        glowAnimators = listOf(
            createGlowAnimator(listenGlowPrimary, "scaleX", 1f, primaryScaleTo, duration),
            createGlowAnimator(listenGlowPrimary, "scaleY", 1f, primaryScaleTo, duration),
            createGlowAnimator(listenGlowPrimary, "alpha", listenGlowPrimary.alpha, primaryAlphaTo, duration),
            createGlowAnimator(listenGlowSecondary, "scaleX", 1f, secondaryScaleTo, duration + 240L),
            createGlowAnimator(listenGlowSecondary, "scaleY", 1f, secondaryScaleTo, duration + 240L),
            createGlowAnimator(listenGlowSecondary, "alpha", listenGlowSecondary.alpha, secondaryAlphaTo, duration + 240L),
        )
        glowAnimators.forEach { it.start() }
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
        glowAnimators.forEach { it.cancel() }
        glowAnimators = emptyList()
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

    private inner class ExportButtonClickListener : View.OnClickListener, View.OnLongClickListener {
        override fun onClick(v: View) {
            export(v, keepRecording = false)
        }

        override fun onLongClick(v: View): Boolean {
            export(v, keepRecording = true)
            return true
        }

        private fun export(
            button: View,
            keepRecording: Boolean,
        ) {
            if (isSaving) return
            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (button.id == R.id.record_last_custom) {
                promptForCustomDuration(keepRecording)
                return
            }

            val seconds = getPrependedSeconds(button)
            if (seconds <= 0f) {
                Toast.makeText(requireContext(), R.string.custom_export_duration_invalid, Toast.LENGTH_SHORT).show()
                return
            }

            val service = recorder ?: return
            if (keepRecording) {
                service.startRecording(seconds)
            } else {
                setSavingInProgress(true)
                service.dumpRecording(seconds, SaveResultReceiver(requireActivity()), "")
            }
        }

        private fun promptForCustomDuration(keepRecording: Boolean) {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_duration, null)
            val durationField = dialogView.findViewById<EditText>(R.id.custom_duration_value)

            val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_TimeTravel_AlertDialog)
                .setTitle(R.string.custom_time)
                .setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val seconds = parseDurationInput(durationField.text?.toString().orEmpty())?.toFloat()
                    if (seconds == null || seconds <= 0f) {
                        durationField.error = getString(R.string.custom_export_duration_invalid)
                        return@setOnClickListener
                    }

                    durationField.error = null
                    val service = recorder ?: return@setOnClickListener
                    if (keepRecording) {
                        service.startRecording(seconds)
                    } else {
                        setSavingInProgress(true)
                        service.dumpRecording(seconds, SaveResultReceiver(requireActivity()), "")
                    }
                    dialog.dismiss()
                }
            }

            dialog.show()
        }

        private fun getPrependedSeconds(button: View): Float {
            return when (button.id) {
                R.id.record_last_max -> FULL_BUFFER_SECONDS
                else -> (button.tag as? Int)?.toFloat() ?: 0f
            }
        }
    }

    private fun setSavingInProgress(inProgress: Boolean) {
        isSaving = inProgress
        val enabled = !inProgress
        presetButtons.forEach { it.isEnabled = enabled }
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

    private fun showSavedSnackbar(file: File) {
        val anchor = activity?.findViewById<View>(R.id.bottom_navigation)
        Snackbar.make(requireView(), R.string.saved_snackbar, Snackbar.LENGTH_LONG)
            .setAction(R.string.open) {
                try {
                    startActivity(buildOpenRecordingIntent(requireContext(), file))
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
            outFile: File,
        ): Notification {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val fileUri = FileProvider.getUriForFile(
                    context,
                    "${context.applicationContext.packageName}.provider",
                    outFile,
                )
                setDataAndType(fileUri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            return NotificationCompat.Builder(context, TimeTravelService.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.recording_saved))
                .setContentText(outFile.name)
                .setSmallIcon(R.drawable.ic_notification_saved)
                .setTicker(outFile.name)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        }
    }

    class NotifyFileReceiver(private val context: Context) : TimeTravelService.AudioFileReceiver {
        override fun fileReady(
            file: File,
            runtime: Float,
        ) {
            if (
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            NotificationManagerCompat.from(context).notify(43, buildNotificationForFile(context, file))
        }
    }

    private inner class SaveResultReceiver(private val activity: FragmentActivity) : TimeTravelService.AudioFileReceiver {
        override fun fileReady(
            file: File,
            runtime: Float,
        ) {
            setSavingInProgress(false)
            showSavedSnackbar(file)
        }

        override fun fileFailed(message: String) {
            setSavingInProgress(false)
            Toast.makeText(activity, if (message.isBlank()) activity.getString(R.string.save_failed) else message, Toast.LENGTH_SHORT)
                .show()
        }
    }
}
