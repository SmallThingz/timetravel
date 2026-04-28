package app.smallthingz.timetravel

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugChunksFragment : Fragment() {
    private var service: TimeTravelService? = null
    private var serviceBound = false
    private var pollJob: Job? = null

    private lateinit var content: View
    private lateinit var summary: TextView
    private lateinit var operationsList: LinearLayout
    private lateinit var chunksList: LinearLayout
    private lateinit var brandLockup: View
    private lateinit var settingsButton: ImageButton

    private val timeFormatter = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? TimeTravelService.BackgroundRecorderBinder)?.service
            requestSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_debug_chunks, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        content = view.findViewById(R.id.chunks_content)
        summary = view.findViewById(R.id.chunks_summary)
        operationsList = view.findViewById(R.id.operations_list)
        chunksList = view.findViewById(R.id.chunks_list)
        brandLockup = view.findViewById(R.id.brand_lockup)
        settingsButton = view.findViewById(R.id.settings_button)

        brandLockup.setOnClickListener {
            AboutInfoDialog.show(requireContext())
        }
        settingsButton.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        val start = content.paddingStart
        val top = content.paddingTop
        val end = content.paddingEnd
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.updatePadding(
                left = start + bars.left,
                top = top,
                right = end + bars.right,
                bottom = bottom + bars.bottom,
            )
            insets
        }
    }

    override fun onStart() {
        super.onStart()
        serviceBound = requireContext().bindService(Intent(requireContext(), TimeTravelService::class.java), connection, Context.BIND_AUTO_CREATE)
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                requestSnapshot()
                delay(1_000L)
            }
        }
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        if (serviceBound) {
            requireContext().unbindService(connection)
            serviceBound = false
        }
        service = null
        super.onStop()
    }

    private fun requestSnapshot() {
        val currentService = service ?: return
        currentService.getChunkDebugSnapshot(object : TimeTravelService.ChunkDebugCallback {
            override fun snapshot(data: TimeTravelService.ChunkDebugSnapshot) {
                if (!isAdded || view == null) {
                    return
                }
                renderSnapshot(data)
            }
        })
    }

    private fun renderSnapshot(snapshot: TimeTravelService.ChunkDebugSnapshot) {
        val history = snapshot.history
        val mode = when {
            snapshot.recording -> "recording"
            snapshot.listeningEnabled -> "live"
            else -> "paused"
        }
        val reencode = when {
            snapshot.historyReencoding -> " · reencode ${formatShortFileSize(snapshot.historyReencodeProcessedBytes)} / ${formatShortFileSize(snapshot.historyReencodeTotalBytes)}"
            snapshot.historyReencodePending -> " · reencode pending"
            else -> ""
        }
        summary.text =
            "${snapshot.format.prefValue} / ${snapshot.codec.prefValue} · ${sampleRateLabel(snapshot.sampleRate)} · ${if (snapshot.channelCount >= 2) "stereo" else "mono"} · $mode$reencode"

        operationsList.removeAllViews()
        val operations = history?.operations.orEmpty()
        if (operations.isEmpty()) {
            operationsList.addView(emptyText(requireContext(), getString(R.string.chunks_operations_empty)))
        } else {
            operations.forEach { operation ->
                val row = layoutInflater.inflate(R.layout.item_debug_operation, operationsList, false)
                row.findViewById<TextView>(R.id.operation_title).text =
                    getString(operationLabelRes(operation.kind))
                row.findViewById<TextView>(R.id.operation_meta).text =
                    "${timeFormatter.format(Date(operation.startedAtMillis))} · ${formatShortFileSize(operation.targetSampleBytes)}"
                row.findViewById<TextView>(R.id.operation_sources).text =
                    operation.sourcePaths.joinToString(separator = "\n") { path -> path.substringAfterLast('/') }
                operationsList.addView(row)
            }
        }

        chunksList.removeAllViews()
        val chunks = history?.chunks.orEmpty()
        if (chunks.isEmpty()) {
            chunksList.addView(emptyText(requireContext(), getString(R.string.chunks_empty)))
            return
        }
        chunks.forEach { chunk ->
            val row = layoutInflater.inflate(R.layout.item_debug_chunk, chunksList, false)
            row.findViewById<TextView>(R.id.chunk_name).text = chunk.fileName
            row.findViewById<TextView>(R.id.chunk_timing).text =
                "${timeFormatter.format(Date(chunk.startedAtMillis))} → ${timeFormatter.format(Date(chunk.endedAtMillis))}"
            row.findViewById<TextView>(R.id.chunk_format).text =
                "${chunk.codec ?: "?"} · ${sampleRateLabel(chunk.sampleRate)} · ${if (chunk.channelCount >= 2) "stereo" else "mono"} · ${formatShortFileSize(chunk.sampleBytes)}"
            row.findViewById<TextView>(R.id.chunk_active).visibility = if (chunk.active) View.VISIBLE else View.GONE
            val affectingOperations = operations.filter { chunk.filePath in it.sourcePaths }
            val operationsText = row.findViewById<TextView>(R.id.chunk_operations)
            if (affectingOperations.isEmpty()) {
                operationsText.visibility = View.GONE
            } else {
                operationsText.visibility = View.VISIBLE
                operationsText.text =
                    affectingOperations.joinToString(separator = " · ") {
                        getString(
                            operationLabelRes(it.kind),
                        )
                    }
            }
            chunksList.addView(row)
        }
    }

    private fun emptyText(context: Context, text: String): TextView {
        return TextView(context).apply {
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(MaterialColorsCompat.onSurfaceVariant(context))
            this.text = text
        }
    }

    private fun operationLabelRes(kind: String): Int {
        return when (kind) {
            "BACKGROUND_MERGE" -> R.string.chunks_operation_background_merge
            "EXPORT_MERGE" -> R.string.chunks_operation_export_merge
            else -> R.string.chunks_operation_export_merge
        }
    }
}

private object MaterialColorsCompat {
    fun onSurfaceVariant(context: Context): Int = com.google.android.material.color.MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
}
