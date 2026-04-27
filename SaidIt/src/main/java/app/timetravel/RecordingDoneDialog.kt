package app.timetravel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class RecordingDoneDialog : ThemedDialog() {
    private var file: File? = null
    private var runtime = 0f
    private val timeFormatResult = NaturalLanguageResult()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_RUNTIME, runtime)
        outState.putString(KEY_FILE, file?.path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(KEY_FILE)?.let { file = File(it) }
        runtime = savedInstanceState?.getFloat(KEY_RUNTIME) ?: runtime
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.recording_done_dialog, container, false)
        fixFonts(root)

        val activity = requireActivity()
        val outputFile = requireNotNull(file) { "Recording file must be set before showing dialog." }
        val fileUri = FileProvider.getUriForFile(activity, "${BuildConfig.APPLICATION_ID}.provider", outputFile)

        formatNaturalLanguage(activity.resources, runtime, timeFormatResult)

        root.findViewById<TextView>(R.id.recording_done_filename).text = outputFile.name
        root.findViewById<TextView>(R.id.recording_done_dirname).text = outputFile.parent
        root.findViewById<TextView>(R.id.recording_done_runtime).text = timeFormatResult.text
        root.findViewById<TextView>(R.id.recording_done_size).text = formatShortFileSize(outputFile.length())

        root.findViewById<View>(R.id.recording_done_open).setOnClickListener {
            launchIntent(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(fileUri, "audio/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }
        root.findViewById<View>(R.id.recording_done_send).setOnClickListener {
            launchIntent(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        putExtra(Intent.EXTRA_STREAM, fileUri)
                        type = activity.contentResolver.getType(fileUri) ?: "audio/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    getString(R.string.send),
                ),
            )
        }
        root.findViewById<View>(R.id.recording_done_close).setOnClickListener {
            dismissAllowingStateLoss()
        }

        return root
    }

    fun setFile(file: File): RecordingDoneDialog = apply {
        this.file = file
    }

    fun setRuntime(runtime: Float): RecordingDoneDialog = apply {
        this.runtime = runtime
    }

    private fun launchIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val KEY_RUNTIME = "runtime"
        const val KEY_FILE = "file"
    }
}
