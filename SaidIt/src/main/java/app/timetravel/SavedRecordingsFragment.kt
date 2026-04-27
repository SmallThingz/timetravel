package app.timetravel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class SavedRecordingsFragment : Fragment() {
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private val adapter = SavedRecordingAdapter(
        onOpen = ::openRecording,
        onShare = ::shareRecording,
        onDelete = ::deleteRecording,
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_saved_recordings, container, false)
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        applyWindowInsets(view)
        view.findViewById<View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        list = view.findViewById(R.id.recordings_list)
        emptyState = view.findViewById(R.id.recordings_empty)

        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter
        list.itemAnimator = null

        refreshRecordings()
    }

    override fun onResume() {
        super.onResume()
        refreshRecordings()
    }

    fun refreshRecordings() {
        context ?: return
        if (!this::list.isInitialized) return

        viewLifecycleOwner.lifecycleScope.launch {
            val recordings = loadSavedRecordings(RecordingRepository.refresh(requireContext()))
            adapter.submitList(recordings)
            list.isVisible = recordings.isNotEmpty()
            emptyState.isVisible = recordings.isEmpty()
        }
    }

    private fun openRecording(recording: RecordingEntity) {
        launchIntent(buildOpenRecordingIntent(requireContext(), recording))
    }

    private fun shareRecording(recording: RecordingEntity) {
        launchIntent(Intent.createChooser(buildShareRecordingIntent(requireContext(), recording), getString(R.string.send)))
    }

    private fun launchIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyWindowInsets(root: View) {
        val header = root.findViewById<View>(R.id.recordings_header)
        val start = header.paddingStart
        val top = header.paddingTop
        val end = header.paddingEnd
        val bottom = header.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(
                left = start + bars.left,
                top = top + bars.top,
                right = end + bars.right,
                bottom = bottom,
            )
            insets
        }
    }

    private fun loadSavedRecordings(recordings: List<RecordingEntity>): List<SavedRecordingListItem> {
        val items = mutableListOf<SavedRecordingListItem>()
        var currentDateHeader = ""

        recordings.forEach { recording ->
            val dateHeader = formatRecordingDateHeader(requireContext(), recording.startedAtMillis)
            if (dateHeader != currentDateHeader) {
                currentDateHeader = dateHeader
                items.add(SavedRecordingListItem.Header(dateHeader))
            }

            items.add(
                SavedRecordingListItem.Recording(
                    recording = recording,
                    timestampLabel = formatRecordingStartTimestamp(requireContext(), recording.startedAtMillis),
                    fileName = recording.displayName,
                    durationLabel = formatSavedRecordingDuration(recording.durationMillis),
                    codecLabel = recording.codecSummary,
                    sizeLabel = formatShortFileSize(recording.sizeBytes),
                ),
            )
        }
        return items
    }

    private fun deleteRecording(recording: RecordingEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = RecordingRepository.delete(requireContext(), recording)
            if (!deleted) {
                Toast.makeText(requireContext(), R.string.recording_delete_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            refreshRecordings()
            Snackbar.make(requireView(), R.string.recording_deleted, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun formatSavedRecordingDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L && seconds > 0L -> "${minutes}m ${seconds}s"
            minutes > 0L -> "$minutes min"
            else -> "$seconds s"
        }
    }
}

private sealed class SavedRecordingListItem {
    data class Header(val dateLabel: String) : SavedRecordingListItem()
    data class Recording(
        val recording: RecordingEntity,
        val timestampLabel: String,
        val fileName: String,
        val durationLabel: String,
        val codecLabel: String,
        val sizeLabel: String,
    ) : SavedRecordingListItem()
}

private class SavedRecordingAdapter(
    private val onOpen: (RecordingEntity) -> Unit,
    private val onShare: (RecordingEntity) -> Unit,
    private val onDelete: (RecordingEntity) -> Unit,
) : ListAdapter<SavedRecordingListItem, RecyclerView.ViewHolder>(SavedRecordingDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SavedRecordingListItem.Header -> 0
            is SavedRecordingListItem.Recording -> 1
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderViewHolder(inflater.inflate(R.layout.item_recording_header, parent, false))
        } else {
            SavedRecordingViewHolder(inflater.inflate(R.layout.item_saved_recording, parent, false), onOpen, onShare, onDelete)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val item = getItem(position)) {
            is SavedRecordingListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is SavedRecordingListItem.Recording -> (holder as SavedRecordingViewHolder).bind(item)
        }
    }
}

private class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val dateText: TextView = itemView.findViewById(R.id.header_date)

    fun bind(item: SavedRecordingListItem.Header) {
        dateText.text = item.dateLabel
    }
}

private class SavedRecordingViewHolder(
    itemView: View,
    private val onOpen: (RecordingEntity) -> Unit,
    private val onShare: (RecordingEntity) -> Unit,
    private val onDelete: (RecordingEntity) -> Unit,
) : RecyclerView.ViewHolder(itemView) {
    private val timestamp: TextView = itemView.findViewById(R.id.recording_timestamp)
    private val name: TextView = itemView.findViewById(R.id.recording_name)
    private val duration: TextView = itemView.findViewById(R.id.recording_duration)
    private val codec: TextView = itemView.findViewById(R.id.recording_codec_info)
    private val size: TextView = itemView.findViewById(R.id.recording_size)
    private val delete: View = itemView.findViewById(R.id.recording_delete)

    fun bind(item: SavedRecordingListItem.Recording) {
        timestamp.text = item.timestampLabel
        name.text = item.fileName
        duration.text = item.durationLabel
        codec.text = item.codecLabel
        size.text = item.sizeLabel

        itemView.setOnClickListener { onOpen(item.recording) }
        itemView.setOnLongClickListener {
            onShare(item.recording)
            true
        }
        delete.setOnClickListener { onDelete(item.recording) }
    }
}

private class SavedRecordingDiffCallback : DiffUtil.ItemCallback<SavedRecordingListItem>() {
    override fun areItemsTheSame(
        oldItem: SavedRecordingListItem,
        newItem: SavedRecordingListItem,
    ): Boolean {
        if (oldItem is SavedRecordingListItem.Header && newItem is SavedRecordingListItem.Header) {
            return oldItem.dateLabel == newItem.dateLabel
        }
        if (oldItem is SavedRecordingListItem.Recording && newItem is SavedRecordingListItem.Recording) {
            return oldItem.recording.id == newItem.recording.id
        }
        return false
    }

    override fun areContentsTheSame(
        oldItem: SavedRecordingListItem,
        newItem: SavedRecordingListItem,
    ): Boolean {
        return oldItem == newItem
    }
}
