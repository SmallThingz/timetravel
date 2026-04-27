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
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SavedRecordingsFragment : Fragment() {
    private lateinit var brandLockup: View
    private lateinit var settingsButton: View
    private lateinit var selectionTitle: TextView
    private lateinit var selectionActions: View
    private lateinit var selectionClearButton: View
    private lateinit var selectionRenameButton: View
    private lateinit var selectionInfoButton: View
    private lateinit var selectionDeleteButton: View
    private lateinit var list: RecyclerView
    private lateinit var emptyState: View
    private val selectedRecordingIds = linkedSetOf<String>()
    private val pendingDeletionIds = linkedSetOf<String>()
    private val pendingDeletionsById = linkedMapOf<String, RecordingEntity>()
    private var latestRecordings: List<RecordingEntity> = emptyList()
    private var latestRecordingsById: Map<String, RecordingEntity> = emptyMap()
    private var pendingDeleteSnackbar: Snackbar? = null
    private var playerDialog: RecordingPlayerDialog? = null
    private val adapter = SavedRecordingAdapter(
        onOpen = ::handleRecordingTap,
        onToggleSelection = ::toggleSelection,
    )
    private val pendingDeleteSnackbarCallback = object : Snackbar.Callback() {
        override fun onDismissed(
            transientBottomBar: Snackbar?,
            event: Int,
        ) {
            if (pendingDeleteSnackbar !== transientBottomBar) return
            pendingDeleteSnackbar = null
            if (event == BaseTransientBottomBar.BaseCallback.DISMISS_EVENT_ACTION) return
            finalizePendingDeletions()
        }
    }

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
        brandLockup = view.findViewById(R.id.brand_lockup)
        settingsButton = view.findViewById(R.id.settings_button)
        selectionTitle = view.findViewById(R.id.selection_title)
        selectionActions = view.findViewById(R.id.selection_actions)
        selectionClearButton = view.findViewById(R.id.selection_clear_button)
        selectionRenameButton = view.findViewById(R.id.selection_rename_button)
        selectionInfoButton = view.findViewById(R.id.selection_info_button)
        selectionDeleteButton = view.findViewById(R.id.selection_delete_button)

        settingsButton.setOnClickListener {
            startActivity(
                Intent(requireContext(), SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            activity?.applyNoAnimationOpenTransition()
        }
        selectionClearButton.setOnClickListener { clearSelection() }
        selectionDeleteButton.setOnClickListener { deleteSelectedRecordings() }
        selectionRenameButton.setOnClickListener { renameSelectedRecording() }
        selectionInfoButton.setOnClickListener { showSelectedRecordingInfo() }

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

    override fun onDestroyView() {
        pendingDeleteSnackbar?.removeCallback(pendingDeleteSnackbarCallback)
        pendingDeleteSnackbar?.dismiss()
        pendingDeleteSnackbar = null
        playerDialog?.dismiss()
        playerDialog = null
        if (pendingDeletionsById.isNotEmpty()) {
            finalizePendingDeletions()
        }
        super.onDestroyView()
    }

    fun refreshRecordings() {
        context ?: return
        if (!this::list.isInitialized) return

        viewLifecycleOwner.lifecycleScope.launch {
            val storedRecordings = RecordingRepository.refresh(requireContext())
            latestRecordings = storedRecordings
            latestRecordingsById = storedRecordings.associateBy { it.id }
            reconcilePendingDeletions()
            selectedRecordingIds.retainAll(latestRecordingsById.keys - pendingDeletionIds)
            renderRecordings()
        }
    }

    private fun handleRecordingTap(recording: RecordingEntity) {
        if (selectedRecordingIds.isNotEmpty()) {
            toggleSelection(recording)
            return
        }
        showRecordingPlayer(recording)
    }

    private fun launchIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.no_app_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecordingPlayer(recording: RecordingEntity) {
        playerDialog?.dismiss()
        playerDialog = RecordingPlayerDialog(
            context = requireContext(),
            recording = recording,
            onPlaybackFailed = { launchIntent(buildOpenRecordingIntent(requireContext(), recording)) },
        ).also { it.show() }
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

    private fun toggleSelection(recording: RecordingEntity) {
        if (!selectedRecordingIds.add(recording.id)) {
            selectedRecordingIds.remove(recording.id)
        }
        adapter.updateSelection(selectedRecordingIds)
        updateSelectionChrome()
    }

    private fun clearSelection() {
        if (selectedRecordingIds.isEmpty()) return
        selectedRecordingIds.clear()
        adapter.updateSelection(selectedRecordingIds)
        updateSelectionChrome()
    }

    private fun updateSelectionChrome() {
        val count = selectedRecordingIds.size
        val selectionActive = count > 0
        brandLockup.isVisible = !selectionActive
        settingsButton.isVisible = !selectionActive
        selectionTitle.isVisible = selectionActive
        selectionActions.isVisible = selectionActive
        selectionTitle.text = resources.getQuantityString(R.plurals.recordings_selected, count, count)
        selectionRenameButton.isVisible = count == 1
        selectionInfoButton.isVisible = count == 1
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
                    durationLabel = "${formatSavedRecordingDuration(recording.durationMillis)} • ${recording.codecSummary}",
                    sizeLabel = formatShortFileSize(recording.sizeBytes),
                ),
            )
        }
        return items
    }

    private fun deleteSelectedRecordings() {
        val selected = selectedRecordingIds.mapNotNull(latestRecordingsById::get)
        if (selected.isEmpty()) {
            clearSelection()
            return
        }
        selected.forEach { recording ->
            pendingDeletionIds += recording.id
            pendingDeletionsById[recording.id] = recording
        }
        clearSelection()
        renderRecordings()
        showPendingDeletionSnackbar()
    }

    private fun reconcilePendingDeletions() {
        val existingIds = latestRecordingsById.keys
        pendingDeletionIds.retainAll(existingIds)
        pendingDeletionsById.entries.removeAll { (id, _) -> id !in existingIds }
    }

    private fun renderRecordings() {
        if (!this::list.isInitialized) return
        val visibleRecordings = latestRecordings.filterNot { it.id in pendingDeletionIds }
        val items = loadSavedRecordings(visibleRecordings)
        adapter.submitList(items)
        adapter.updateSelection(selectedRecordingIds)
        list.isVisible = items.isNotEmpty()
        emptyState.isVisible = items.isEmpty()
        updateSelectionChrome()
    }

    private fun showPendingDeletionSnackbar() {
        val root = view ?: return
        val pendingCount = pendingDeletionsById.size
        if (pendingCount == 0) return

        pendingDeleteSnackbar?.removeCallback(pendingDeleteSnackbarCallback)
        pendingDeleteSnackbar?.dismiss()

        val message = if (pendingCount == 1) {
            getString(R.string.recording_deleted)
        } else {
            resources.getQuantityString(R.plurals.recordings_deleted, pendingCount, pendingCount)
        }
        val anchor = activity?.findViewById<View>(R.id.bottom_navigation)
        pendingDeleteSnackbar = Snackbar.make(root, message, Snackbar.LENGTH_LONG).apply {
            anchor?.let { setAnchorView(it) }
            setAction(R.string.undo) { undoPendingDeletions() }
            addCallback(pendingDeleteSnackbarCallback)
            show()
        }
    }

    private fun undoPendingDeletions() {
        if (pendingDeletionsById.isEmpty()) return
        pendingDeletionIds.clear()
        pendingDeletionsById.clear()
        renderRecordings()
    }

    private fun finalizePendingDeletions() {
        val pending = pendingDeletionsById.values.toList()
        if (pending.isEmpty()) return

        pendingDeletionIds.clear()
        pendingDeletionsById.clear()

        lifecycleScope.launch {
            var deletedCount = 0
            pending.forEach { recording ->
                if (RecordingRepository.delete(requireContext(), recording)) {
                    deletedCount++
                }
            }

            val storedRecordings = RecordingRepository.refresh(requireContext())
            latestRecordings = storedRecordings
            latestRecordingsById = storedRecordings.associateBy { it.id }
            selectedRecordingIds.retainAll(latestRecordingsById.keys)
            if (view != null && this@SavedRecordingsFragment::list.isInitialized) {
                renderRecordings()
            }
            if (deletedCount == 0 && isAdded) {
                Toast.makeText(requireContext(), R.string.recording_delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renameSelectedRecording() {
        val recording = selectedRecordingIds.singleOrNull()?.let(latestRecordingsById::get) ?: return
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_rename_recording, null, false)
        val nameLayout = content.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.rename_recording_layout)
        val nameInput = content.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.rename_recording_input)
        nameInput.setText(recording.displayName.substringBeforeLast('.', recording.displayName))
        nameInput.setSelection(nameInput.text?.length ?: 0)

        val handle = ThemedDialog.create(
            context = requireContext(),
            title = getString(R.string.rename_recording),
            content = content,
            positiveText = getString(R.string.save),
        )
        handle.negativeButton.setOnClickListener { handle.dialog.dismiss() }
        handle.positiveButton.setOnClickListener {
            val requestedName = nameInput.text?.toString().orEmpty().trim()
            if (requestedName.isBlank()) {
                nameLayout.error = getString(R.string.rename_recording_invalid)
                return@setOnClickListener
            }
            nameLayout.error = null
            viewLifecycleOwner.lifecycleScope.launch {
                val renamed = RecordingRepository.rename(requireContext(), recording, requestedName)
                if (renamed == null) {
                    Toast.makeText(requireContext(), R.string.rename_recording_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                clearSelection()
                refreshRecordings()
                handle.dialog.dismiss()
            }
        }
        handle.dialog.show()
    }

    private fun showSelectedRecordingInfo() {
        val recording = selectedRecordingIds.singleOrNull()?.let(latestRecordingsById::get) ?: return
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_recording_details, null, false)
        val details = content.findViewById<TextView>(R.id.recording_details_text)
        details.text = buildRecordingDetailsText(recording)

        val handle = ThemedDialog.create(
            context = requireContext(),
            title = getString(R.string.recording_info),
            content = content,
            positiveText = getString(R.string.close),
            negativeText = "",
        )
        handle.negativeButton.visibility = View.GONE
        handle.positiveButton.setOnClickListener { handle.dialog.dismiss() }
        handle.dialog.show()
    }

    private fun buildRecordingDetailsText(recording: RecordingEntity): String {
        val startedAt = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss z", Locale.getDefault())
            .format(Instant.ofEpochMilli(recording.startedAtMillis).atZone(ZoneId.systemDefault()))
        return buildString {
            appendLine("${getString(R.string.recording_details_name)} ${recording.displayName}")
            appendLine("${getString(R.string.recording_details_started)} $startedAt")
            appendLine("${getString(R.string.recording_details_duration)} ${formatSavedRecordingDuration(recording.durationMillis)}")
            appendLine("${getString(R.string.recording_details_size)} ${formatShortFileSize(recording.sizeBytes)}")
            appendLine("${getString(R.string.recording_details_codec)} ${recording.codecSummary}")
            appendLine("${getString(R.string.recording_details_mime)} ${recording.mimeType}")
            appendLine("${getString(R.string.recording_details_storage)} ${recording.storageType}")
            append("${getString(R.string.recording_details_location)} ${describeRecordingLocation(requireContext(), recording)}")
        }.trim()
    }

}

private sealed class SavedRecordingListItem {
    data class Header(val dateLabel: String) : SavedRecordingListItem()
    data class Recording(
        val recording: RecordingEntity,
        val timestampLabel: String,
        val fileName: String,
        val durationLabel: String,
        val sizeLabel: String,
    ) : SavedRecordingListItem()
}

private class SavedRecordingAdapter(
    private val onOpen: (RecordingEntity) -> Unit,
    private val onToggleSelection: (RecordingEntity) -> Unit,
) : ListAdapter<SavedRecordingListItem, RecyclerView.ViewHolder>(SavedRecordingDiffCallback()) {
    private var selectedIds: Set<String> = emptySet()

    fun updateSelection(selection: Set<String>) {
        selectedIds = selection.toSet()
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        }
    }

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
            SavedRecordingViewHolder(inflater.inflate(R.layout.item_saved_recording, parent, false), onOpen, onToggleSelection)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        when (val item = getItem(position)) {
            is SavedRecordingListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is SavedRecordingListItem.Recording -> {
                val selectionActive = selectedIds.isNotEmpty()
                val selected = item.recording.id in selectedIds
                val typedHolder = holder as SavedRecordingViewHolder
                if (PAYLOAD_SELECTION in payloads) {
                    typedHolder.bindSelection(selectionActive, selected)
                } else {
                    typedHolder.bind(
                        item = item,
                        selectionActive = selectionActive,
                        selected = selected,
                    )
                }
            }
        }
    }

    private companion object {
        const val PAYLOAD_SELECTION = "selection"
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
    private val onToggleSelection: (RecordingEntity) -> Unit,
) : RecyclerView.ViewHolder(itemView) {
    private val row: View = itemView.findViewById(R.id.recording_row)
    private val timestamp: TextView = itemView.findViewById(R.id.recording_timestamp)
    private val name: TextView = itemView.findViewById(R.id.recording_name)
    private val duration: TextView = itemView.findViewById(R.id.recording_duration)
    private val size: TextView = itemView.findViewById(R.id.recording_size)

    fun bind(
        item: SavedRecordingListItem.Recording,
        selectionActive: Boolean,
        selected: Boolean,
    ) {
        timestamp.text = item.timestampLabel
        name.text = item.fileName
        duration.text = item.durationLabel
        size.text = item.sizeLabel
        bindSelection(selectionActive, selected)

        itemView.setOnClickListener {
            if (selectionActive) {
                onToggleSelection(item.recording)
            } else {
                onOpen(item.recording)
            }
        }
        itemView.setOnLongClickListener {
            onToggleSelection(item.recording)
            true
        }
    }

    fun bindSelection(
        selectionActive: Boolean,
        selected: Boolean,
    ) {
        row.isActivated = selected
        row.alpha = if (selectionActive && !selected) 0.75f else 1f
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
