package eu.mrogalski.saidit

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.mrogalski.android.TimeFormat
import java.io.File

class SaidItFragment : Fragment() {
    private lateinit var listenButton: Button
    private lateinit var recordPauseButton: Button
    private lateinit var recordLastFiveMinutesButton: Button
    private lateinit var recordMaxButton: Button
    private lateinit var recordLastMinuteButton: Button
    private lateinit var recordLastThirtyMinuteButton: Button
    private lateinit var recordLastTwoHoursButton: Button
    private lateinit var recordLastSixHoursButton: Button
    private lateinit var recordCustomButton: Button
    private lateinit var customExportDuration: EditText
    private lateinit var historyLimit: TextView
    private lateinit var historySize: TextView
    private lateinit var historySizeTitle: TextView
    private lateinit var readySection: LinearLayout
    private lateinit var recSection: LinearLayout
    private lateinit var recIndicator: TextView
    private lateinit var recTime: TextView
    private lateinit var rateOnGithubButton: ImageButton
    private lateinit var heart: ImageView

    private var isListening = true
    private var isRecording = false
    private var echo: SaidItService? = null

    private val timeFormatResult = TimeFormat.Result()

    private val updater: Runnable = object : Runnable {
        override fun run() {
            if (view == null) {
                return
            }
            echo?.getState(serviceStateCallback)
        }
    }

    private val echoConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.d(TAG, "onServiceConnected")
            val typedBinder = binder as SaidItService.BackgroundRecorderBinder
            val service = typedBinder.service
            if (echo === service) {
                Log.d(TAG, "update loop already running, skipping")
                return
            }
            echo = service
            view?.postOnAnimation(updater)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "onServiceDisconnected")
            echo = null
        }
    }

    private val serviceStateCallback: SaidItService.StateCallback = object : SaidItService.StateCallback {
        override fun state(
            listeningEnabled: Boolean,
            recording: Boolean,
            memorized: Float,
            totalMemory: Float,
            recorded: Float,
        ) {
            val activity = activity ?: return
            val resources = activity.resources

            if ((isRecording != recording) || (isListening != listeningEnabled)) {
                if (recording != isRecording) {
                    isRecording = recording
                    recSection.visibility = if (recording) View.VISIBLE else View.GONE
                }

                if (listeningEnabled != isListening) {
                    isListening = listeningEnabled
                    if (listeningEnabled) {
                        listenButton.setText(R.string.listening_enabled_disable)
                        listenButton.setBackgroundResource(R.drawable.top_green_button)
                        listenButton.setShadowLayer(
                            0.01f,
                            0f,
                            resources.getDimensionPixelOffset(R.dimen.shadow_offset).toFloat(),
                            ContextCompat.getColor(activity, R.color.accent_deep),
                        )
                    } else {
                        listenButton.setText(R.string.listening_disabled_enable)
                        listenButton.setBackgroundResource(R.drawable.top_gray_button)
                        listenButton.setShadowLayer(
                            0.01f,
                            0f,
                            resources.getDimensionPixelOffset(R.dimen.shadow_offset).toFloat(),
                            ContextCompat.getColor(activity, R.color.border_strong),
                        )
                    }
                }

                readySection.visibility = if (listeningEnabled && !recording) View.VISIBLE else View.GONE
            }

            TimeFormat.naturalLanguage(resources, totalMemory, timeFormatResult)
            if (historyLimit.text.toString() != timeFormatResult.text) {
                historyLimit.text = timeFormatResult.text
            }

            TimeFormat.naturalLanguage(resources, memorized, timeFormatResult)
            if (historySize.text.toString() != timeFormatResult.text) {
                historySizeTitle.text =
                    resources.getQuantityText(R.plurals.history_size_title, timeFormatResult.count)
                historySize.text = timeFormatResult.text
                recordMaxButton.text = TimeFormat.shortTimer(memorized)
            }

            TimeFormat.naturalLanguage(resources, recorded, timeFormatResult)
            if (recTime.text.toString() != timeFormatResult.text) {
                recIndicator.text =
                    resources.getQuantityText(R.plurals.recorded, timeFormatResult.count)
                recTime.text = timeFormatResult.text
            }

            historySize.postOnAnimationDelayed(updater, 100)
        }
    }

    override fun onStart() {
        Log.d(TAG, "onStart")
        super.onStart()
        val activity = activity ?: return
        activity.bindService(
            Intent(activity, SaidItService::class.java),
            echoConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        super.onStop()
        val activity = activity ?: return
        activity.unbindService(echoConnection)
        echo = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_background_recorder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity()
        UiFonts.styleMainScreen(view, activity)

        historyLimit = view.findViewById(R.id.history_limit)
        historySize = view.findViewById(R.id.history_size)
        historySizeTitle = view.findViewById(R.id.history_size_title)
        listenButton = view.findViewById(R.id.listen_button)
        recordPauseButton = view.findViewById(R.id.rec_stop_button)
        recordLastMinuteButton = view.findViewById(R.id.record_last_minute)
        recordLastFiveMinutesButton = view.findViewById(R.id.record_last_5_minutes)
        recordLastThirtyMinuteButton = view.findViewById(R.id.record_last_30_minutes)
        recordLastTwoHoursButton = view.findViewById(R.id.record_last_2_hrs)
        recordLastSixHoursButton = view.findViewById(R.id.record_last_6_hrs)
        recordMaxButton = view.findViewById(R.id.record_last_max)
        customExportDuration = view.findViewById(R.id.custom_export_duration)
        recordCustomButton = view.findViewById(R.id.record_last_custom)
        readySection = view.findViewById(R.id.ready_section)
        recSection = view.findViewById(R.id.rec_section)
        recIndicator = view.findViewById(R.id.rec_indicator)
        recTime = view.findViewById(R.id.rec_time)
        rateOnGithubButton = view.findViewById(R.id.rate_on_google_play)
        heart = view.findViewById(R.id.heart)

        historyLimit.typeface = Typeface.MONOSPACE
        historySize.typeface = Typeface.MONOSPACE
        recTime.typeface = Typeface.MONOSPACE

        listenButton.setOnClickListener(ListenButtonClickListener())
        applyStatusBarInsetToListenButton()

        val recordButtonClickListener = RecordButtonClickListener()
        recordPauseButton.setOnClickListener(recordButtonClickListener)
        recordLastMinuteButton.setOnClickListener(recordButtonClickListener)
        recordLastMinuteButton.setOnLongClickListener(recordButtonClickListener)
        recordLastFiveMinutesButton.setOnClickListener(recordButtonClickListener)
        recordLastFiveMinutesButton.setOnLongClickListener(recordButtonClickListener)
        recordLastThirtyMinuteButton.setOnClickListener(recordButtonClickListener)
        recordLastThirtyMinuteButton.setOnLongClickListener(recordButtonClickListener)
        recordLastTwoHoursButton.setOnClickListener(recordButtonClickListener)
        recordLastTwoHoursButton.setOnLongClickListener(recordButtonClickListener)
        recordLastSixHoursButton.setOnClickListener(recordButtonClickListener)
        recordLastSixHoursButton.setOnLongClickListener(recordButtonClickListener)
        recordMaxButton.setOnClickListener(recordButtonClickListener)
        recordMaxButton.setOnLongClickListener(recordButtonClickListener)
        recordCustomButton.setOnClickListener(recordButtonClickListener)

        val pulse = AnimationUtils.loadAnimation(activity, R.anim.pulse)
        heart.startAnimation(pulse)

        rateOnGithubButton.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mafik/echo")))
            } catch (_: android.content.ActivityNotFoundException) {
            }
        }

        heart.setOnClickListener {
            heart.animate().scaleX(10f).scaleY(10f).alpha(0f).setDuration(2000).start()
            val handler = Handler(activity.mainLooper)
            handler.postDelayed(
                {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/sponsors/mafik"),
                            ),
                        )
                    } catch (_: android.content.ActivityNotFoundException) {
                    }
                },
                1000,
            )
            handler.postDelayed(
                {
                    heart.alpha = 0f
                    heart.scaleX = 1f
                    heart.scaleY = 1f
                    heart.animate().alpha(1f).start()
                },
                3000,
            )
        }

        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(activity, SettingsActivity::class.java))
        }

        serviceStateCallback.state(isListening, isRecording, 0f, 0f, 0f)
    }

    private fun applyStatusBarInsetToListenButton() {
        val statusBarHeight = getStatusBarHeight()
        listenButton.setPadding(
            listenButton.paddingLeft,
            listenButton.paddingTop + statusBarHeight,
            listenButton.paddingRight,
            listenButton.paddingBottom,
        )
        listenButton.layoutParams = listenButton.layoutParams.apply {
            height += statusBarHeight
        }
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private inner class ListenButtonClickListener : View.OnClickListener {
        private val dialog = WorkingDialog().apply {
            descriptionStringId = R.string.work_preparing_memory
        }

        override fun onClick(v: View) {
            val service = echo ?: return
            service.getState(
                object : SaidItService.StateCallback {
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
                                object : SaidItService.StateCallback {
                                    override fun state(
                                        listeningEnabled: Boolean,
                                        recording: Boolean,
                                        memorized: Float,
                                        totalMemory: Float,
                                        recorded: Float,
                                    ) {
                                        dialog.dismissAllowingStateLoss()
                                    }
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    private inner class RecordButtonClickListener :
        View.OnClickListener,
        View.OnLongClickListener {
        override fun onClick(v: View) {
            record(v, keepRecording = false)
        }

        override fun onLongClick(v: View): Boolean {
            record(v, keepRecording = true)
            return true
        }

        private fun record(button: View, keepRecording: Boolean) {
            val seconds = getPrependedSeconds(button)
            if (seconds <= 0f) {
                if (button.id == R.id.record_last_custom) {
                    customExportDuration.error = getString(R.string.custom_export_duration_invalid)
                } else {
                    Toast.makeText(
                        requireContext(),
                        R.string.custom_export_duration_invalid,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return
            }

            if (button.id == R.id.record_last_custom) {
                customExportDuration.error = null
            }

            val service = echo ?: return
            service.getState(
                object : SaidItService.StateCallback {
                    override fun state(
                        listeningEnabled: Boolean,
                        recording: Boolean,
                        memorized: Float,
                        totalMemory: Float,
                        recorded: Float,
                    ) {
                        activity?.runOnUiThread {
                            if (recording) {
                                service.stopRecording(PromptFileReceiver(requireActivity()), "")
                            } else if (keepRecording) {
                                service.startRecording(seconds)
                            } else {
                                promptForFileName(service, seconds)
                            }
                        }
                    }
                },
            )
        }

        private fun promptForFileName(service: SaidItService, seconds: Float) {
            val dialogView =
                LayoutInflater.from(requireContext()).inflate(R.layout.dialog_save_recording, null)
            val fileName = dialogView.findViewById<EditText>(R.id.recording_name)

            val dialog = MaterialAlertDialogBuilder(
                requireContext(),
                R.style.ThemeOverlay_Echo_AlertDialog,
            )
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
                R.id.record_last_minute -> 60f
                R.id.record_last_5_minutes -> 60f * 5
                R.id.record_last_30_minutes -> 60f * 30
                R.id.record_last_2_hrs -> 60f * 60 * 2
                R.id.record_last_6_hrs -> 60f * 60 * 6
                R.id.record_last_max -> 60f * 60 * 24 * 365
                R.id.record_last_custom -> parseCustomDurationSeconds()
                else -> 0f
            }
        }

        private fun parseCustomDurationSeconds(): Float {
            val value = customExportDuration.text.toString().trim()
            if (value.isEmpty()) {
                return 0f
            }

            val parts = value.split(":")
            if (parts.size !in 2..3) {
                return 0f
            }

            var seconds = 0
            parts.forEachIndexed { index, part ->
                if (part.isEmpty()) {
                    return 0f
                }

                val unit = part.toIntOrNull() ?: return 0f
                if (unit < 0) {
                    return 0f
                }
                if (index > 0 && unit >= 60) {
                    return 0f
                }
                seconds = seconds * 60 + unit
            }

            return seconds.toFloat()
        }
    }

    companion object {
        private const val TAG = "SaidItFragment"
        private const val NOTIFICATION_CHANNEL_ID = "SaidItServiceChannel"

        fun buildNotificationForFile(context: Context, outFile: File): Notification {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val fileUri = FileProvider.getUriForFile(
                    context,
                    "${context.applicationContext.packageName}.provider",
                    outFile,
                )
                setDataAndType(fileUri, "audio/wav")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val pendingIntent =
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.recording_saved))
                .setContentText(outFile.name)
                .setSmallIcon(R.drawable.ic_stat_notify_recorded)
                .setTicker(outFile.name)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build()
        }
    }

    class NotifyFileReceiver(private val context: Context) : SaidItService.WavFileReceiver {
        override fun fileReady(file: File, runtime: Float) {
            val notificationManager = NotificationManagerCompat.from(context)
            if (
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            notificationManager.notify(43, buildNotificationForFile(context, file))
        }
    }

    class PromptFileReceiver(private val activity: FragmentActivity) : SaidItService.WavFileReceiver {
        override fun fileReady(file: File, runtime: Float) {
            RecordingDoneDialog()
                .setFile(file)
                .setRuntime(runtime)
                .show(activity.supportFragmentManager, "Recording Done")
        }
    }
}
