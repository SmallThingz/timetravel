package app.timetravel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

@SuppressLint("ImplicitSamInstance")
class TimeTravelFragment : Fragment() {
    private lateinit var listenButton: MaterialButton
    private lateinit var presetButtons: List<MaterialButton>
    private lateinit var recordMaxButton: MaterialButton
    private lateinit var recordCustomButton: MaterialButton
    private lateinit var historySize: TextView
    private lateinit var recTime: TextView
    private lateinit var statusText: TextView
    private lateinit var formatSummary: TextView
    private lateinit var mainContent: View
    private lateinit var presetGrid: View
    private lateinit var recTouchArea: View
    private lateinit var recordingDivider: View

    private var isListening = true
    private var isRecording = false
    private var recorder: TimeTravelService? = null
    private var serviceBound = false
    private var exportPresets = IntArray(4)

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
            val activity = activity ?: return
            val resources = activity.resources

            if (recording != isRecording) {
                isRecording = recording
                updateRecordingState(recording)
            }

            if (listeningEnabled != isListening) {
                isListening = listeningEnabled
                updateListenButtonAppearance(listeningEnabled)
            }

            statusText.text = if (listeningEnabled) {
                resources.getString(R.string.buffer_active_summary)
            } else {
                resources.getString(R.string.buffer_inactive_summary)
            }

            historySize.text = formatShortTimer(memorized)

            if (recording) {
                recTime.text = formatShortTimer(recorded)
            }

            historySize.removeCallbacks(updater)
            historySize.postOnAnimationDelayed(updater, 300)
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

        mainContent = view.findViewById(R.id.main_content)
        presetGrid = view.findViewById(R.id.preset_grid)
        historySize = view.findViewById(R.id.history_size)
        formatSummary = view.findViewById(R.id.format_summary)
        listenButton = view.findViewById(R.id.listen_button)
        recordMaxButton = view.findViewById(R.id.record_last_max)
        recordCustomButton = view.findViewById(R.id.record_last_custom)
        statusText = view.findViewById(R.id.status_text)
        recTime = view.findViewById(R.id.rec_time)
        recTouchArea = view.findViewById(R.id.rec_touch_area)
        recordingDivider = view.findViewById(R.id.recording_divider)

        presetButtons = listOf(
            view.findViewById(R.id.record_preset_1),
            view.findViewById(R.id.record_preset_2),
            view.findViewById(R.id.record_preset_3),
            view.findViewById(R.id.record_preset_4),
        )

        historySize.typeface = android.graphics.Typeface.MONOSPACE
        recTime.typeface = android.graphics.Typeface.MONOSPACE

        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(activity, SettingsActivity::class.java))
        }
        listenButton.setOnClickListener(ListenButtonClickListener())
        
        recTouchArea.setOnClickListener {
            recorder?.stopRecording(PromptFileReceiver(requireActivity()))
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
        serviceStateCallback.state(isListening, isRecording, 0f, 0f, 0f)
    }

    private fun applyWindowInsets(root: View) {
        val content = root.findViewById<View>(R.id.main_content)
        val start = content.paddingStart
        val top = content.paddingTop
        val end = content.paddingEnd
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
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

    private fun updateRecordingState(recording: Boolean) {
        val view = view ?: return

        if (recording) {
            mainContent.visibility = View.GONE
            presetGrid.visibility = View.GONE
            recordingDivider.visibility = View.VISIBLE
            recTouchArea.visibility = View.VISIBLE
        } else {
            recordingDivider.visibility = View.GONE
            recTouchArea.visibility = View.GONE
            mainContent.visibility = View.VISIBLE
            presetGrid.visibility = View.VISIBLE
        }
    }

    private fun refreshConfiguredUi() {
        val context = context ?: return
        exportPresets = getConfiguredExportPresets(context)
        presetButtons.zip(exportPresets.toList()).forEach { (button, seconds) ->
            button.text = formatDurationInput(seconds)
            button.tag = seconds
        }

        val activeConfig = recorder?.getConfigurationSnapshot()
        val codec = activeConfig?.codec ?: getConfiguredOutputCodec(context)
        val routeMode = activeConfig?.routeMode ?: getConfiguredInputRouteMode(context)
        val sourceMode = activeConfig?.sourceMode ?: getConfiguredAudioSourceMode(context)
        val sampleRate = activeConfig?.sampleRate ?: getConfiguredSampleRate(context, sourceMode, routeMode, codec)

        formatSummary.text = getString(
            R.string.live_summary,
            getString(codec.labelRes),
            sampleRateLabel(sampleRate),
        )
    }

    private fun updateListenButtonAppearance(enabled: Boolean) {
        listenButton.setText(if (enabled) R.string.listening_enabled_disable else R.string.listening_disabled_enable)
        listenButton.setIconResource(if (enabled) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private inner class ListenButtonClickListener : View.OnClickListener {
        private val dialog = WorkingDialog().apply {
            descriptionStringId = R.string.work_preparing_memory
        }

        override fun onClick(v: View) {
            val service = recorder ?: return
            service.getState(
                object : TimeTravelService.StateCallback {
                    override fun state(
                        listeningEnabled: Boolean,
                        recording: Boolean,
                        memorized: Float,
                        totalMemory: Float,
                        recorded: Float,
                    ) {
                        if (listeningEnabled) {
                            service.disableListening()
                        } else {
                            dialog.show(parentFragmentManager, "Preparing memory")
                            service.enableListening()
                            service.getState(
                                object : TimeTravelService.StateCallback {
                                    override fun state(
                                        listeningEnabled: Boolean,
                                        recording: Boolean,
                                        memorized: Float,
                                        totalMemory: Float,
                                        recorded: Float,
                                    ) {
                                        if (dialog.isVisible) {
                                            dialog.dismissAllowingStateLoss()
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
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
                promptForFileName(service, seconds)
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
                        promptForFileName(service, seconds)
                    }
                    dialog.dismiss()
                }
            }

            dialog.show()
        }

        private fun promptForFileName(
            service: TimeTravelService,
            seconds: Float,
        ) {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_save_recording, null)
            val fileName = dialogView.findViewById<EditText>(R.id.recording_name)

            val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_TimeTravel_AlertDialog)
                .setTitle(R.string.save_recording)
                .setView(dialogView)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = fileName.text.toString().trim()
                    if (value.isEmpty()) {
                        fileName.error = getString(R.string.recording_name_required)
                        return@setOnClickListener
                    }

                    fileName.error = null
                    service.dumpRecording(seconds, PromptFileReceiver(requireActivity()), value)
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

    companion object {
        private const val TAG = "TimeTravelFragment"
        private const val FULL_BUFFER_SECONDS = 60f * 60f * 24f * 365f

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

    class PromptFileReceiver(private val activity: FragmentActivity) : TimeTravelService.AudioFileReceiver {
        override fun fileReady(
            file: File,
            runtime: Float,
        ) {
            RecordingDoneDialog()
                .setFile(file)
                .setRuntime(runtime)
                .show(activity.supportFragmentManager, "Recording Done")
        }
    }
}