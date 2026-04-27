package eu.mrogalski.saidit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.FileProvider
import eu.mrogalski.StringFormat
import eu.mrogalski.android.TimeFormat
import java.io.File

class RecordingDoneDialog : ThemedDialog() {
    private var file: File? = null
    private var runtime = 0f
    private val timeFormatResult = TimeFormat.Result()

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

        TimeFormat.naturalLanguage(activity.resources, runtime, timeFormatResult)

        root.findViewById<TextView>(R.id.recording_done_filename).text = outputFile.name
        root.findViewById<TextView>(R.id.recording_done_dirname).text = outputFile.parent
        root.findViewById<TextView>(R.id.recording_done_runtime).text = timeFormatResult.text
        root.findViewById<TextView>(R.id.recording_done_size).text =
            StringFormat.shortFileSize(outputFile.length())

        root.findViewById<View>(R.id.recording_done_open_dir).setOnClickListener {
            openFolder(outputFile)
        }
        root.findViewById<View>(R.id.recording_done_send).setOnClickListener {
            val fileUri =
                FileProvider.getUriForFile(activity, "${BuildConfig.APPLICATION_ID}.provider", outputFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, fileUri)
                type = activity.contentResolver.getType(fileUri) ?: "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.send)))
        }
        root.findViewById<View>(R.id.recording_done_play).setOnClickListener {
            val fileUri =
                FileProvider.getUriForFile(activity, "${BuildConfig.APPLICATION_ID}.provider", outputFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        return root
    }

    fun setFile(file: File): RecordingDoneDialog = apply {
        this.file = file
    }

    fun setRuntime(runtime: Float): RecordingDoneDialog = apply {
        this.runtime = runtime
    }

    private fun openFolder(file: File) {
        val folder = file.parentFile ?: return
        if (folder.exists() && folder.isDirectory) {
            val uri = Uri.parse(folder.absolutePath)
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } else {
            Log.e("OpenFolder", "Folder does not exist or is not a directory")
        }
    }

    @Suppress("unused")
    private fun isExternalStorageWritable(): Boolean {
        return Environment.MEDIA_MOUNTED == Environment.getExternalStorageState()
    }

    private companion object {
        const val KEY_RUNTIME = "runtime"
        const val KEY_FILE = "file"
    }
}
