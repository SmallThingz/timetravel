package app.smallthingz.timetravel

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import java.util.Date
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class ListItem {
    data class Header(val dateLabel: String) : ListItem()
    data class Recording(
        val recording: RecordingEntity,
        val timestampLabel: String,
        val fileName: String,
        val durationLabel: String,
        val sizeLabel: String,
    ) : ListItem()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var recordings by remember { mutableStateOf<List<RecordingEntity>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }

    val selectedIds = remember { mutableStateMapOf<String, RecordingEntity>() }
    val pendingDeletions = remember { mutableStateMapOf<String, RecordingEntity>() }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var infoRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var showPlayerDialog by remember { mutableStateOf(false) }
    var playerRecording by remember { mutableStateOf<RecordingEntity?>(null) }

    fun refresh() {
        isRefreshing = true
        scope.launch {
            try {
                val stored = withContext(Dispatchers.IO) { RecordingRepository.refresh(context) }
                recordings = stored
                pendingDeletions.keys.retainAll(stored.map { it.id }.toSet())
                val toRemove = mutableListOf<String>()
                for (id in pendingDeletions.keys) {
                    if (stored.none { it.id == id }) {
                        toRemove.add(id)
                    }
                }
                toRemove.forEach { pendingDeletions.remove(it) }
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun clearSelection() {
        selectedIds.clear()
    }

    val visibleRecordings by remember {
        derivedStateOf { recordings.filterNot { it.id in pendingDeletions } }
    }

    val listItems by remember {
        derivedStateOf { buildListItems(context, visibleRecordings) }
    }

    fun finalizeDeletions() {
        val pending = pendingDeletions.values.toList()
        if (pending.isEmpty()) return
        pendingDeletions.clear()
        scope.launch {
            var deleted = 0
            pending.forEach { recording ->
                if (RecordingRepository.delete(context, recording)) deleted++
            }
            val stored = withContext(Dispatchers.IO) { RecordingRepository.refresh(context) }
            recordings = stored
            if (deleted == 0) {
                Toast.makeText(context, R.string.recording_delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteSelected() {
        val selected = selectedIds.values.toList()
        if (selected.isEmpty()) { clearSelection(); return }
        selected.forEach { pendingDeletions[it.id] = it }
        clearSelection()
        scope.launch {
            val count = pendingDeletions.size
            val message = if (count == 1) context.getString(R.string.recording_deleted)
            else context.resources.getQuantityString(R.plurals.recordings_deleted, count, count)
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = context.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                pendingDeletions.clear()
            } else {
                finalizeDeletions()
            }
        }
    }

    fun renameSelected() {
        val recording = selectedIds.values.singleOrNull() ?: return
        renameRecording = recording
        showRenameDialog = true
    }

    fun infoSelected() {
        val recording = selectedIds.values.singleOrNull() ?: return
        infoRecording = recording
        showInfoDialog = true
    }

    val selectionActive = selectedIds.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionActive) {
                TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(R.plurals.recordings_selected, selectedIds.size, selectedIds.size),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_selection))
                        }
                    },
                    actions = {
                        if (selectedIds.size == 1) {
                            IconButton(onClick = { renameSelected() }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rename_recording))
                            }
                        }
                        if (selectedIds.size == 1) {
                            IconButton(onClick = { infoSelected() }) {
                                Icon(Icons.Default.Info, contentDescription = stringResource(R.string.recording_info))
                            }
                        }
                        IconButton(onClick = { deleteSelected() }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_recording))
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (listItems.isEmpty() && !isRefreshing) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                ) {
                    items(listItems, key = { it.hashCode() }) { item ->
                        when (item) {
                            is ListItem.Header -> HeaderItem(item.dateLabel)
                            is ListItem.Recording -> {
                                RecordingItem(
                                    item = item,
                                    isSelected = item.recording.id in selectedIds,
                                    selectionActive = selectionActive,
                                    onClick = {
                                        if (selectionActive) {
                                            if (selectedIds.containsKey(item.recording.id)) {
                                                selectedIds.remove(item.recording.id)
                                            } else {
                                                selectedIds[item.recording.id] = item.recording
                                            }
                                        } else {
                                            playerRecording = item.recording
                                            showPlayerDialog = true
                                        }
                                    },
                                    onLongClick = {
                                        if (selectedIds.containsKey(item.recording.id)) {
                                            selectedIds.remove(item.recording.id)
                                        } else {
                                            selectedIds[item.recording.id] = item.recording
                                        }
                                    },
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showRenameDialog && renameRecording != null) {
        RenameRecordingDialog(
            recording = renameRecording!!,
            onDismiss = { showRenameDialog = false; renameRecording = null },
            onRenamed = {
                showRenameDialog = false
                renameRecording = null
                clearSelection()
                refresh()
            },
        )
    }

    if (showInfoDialog && infoRecording != null) {
        RecordingInfoDialogContent(
            recording = infoRecording!!,
            onDismiss = { showInfoDialog = false; infoRecording = null },
        )
    }

    if (showPlayerDialog && playerRecording != null) {
        val currentRecording = playerRecording!!
        RecordingPlayerDialog(
            recording = currentRecording,
            onDismiss = { showPlayerDialog = false; playerRecording = null },
            onPlaybackFailed = {
                showPlayerDialog = false
                playerRecording = null
                try {
                    context.startActivity(buildOpenRecordingIntent(context, currentRecording))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.no_app_available, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.recordings_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.recordings_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeaderItem(dateLabel: String) {
    Text(
        text = dateLabel,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = (0.04).sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingItem(
    item: ListItem.Recording,
    isSelected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else MaterialTheme.colorScheme.surface,
        label = "bg",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_audio_file),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = item.timestampLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.sizeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun RenameRecordingDialog(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(recording.displayName.substringBeforeLast('.', recording.displayName)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_recording)) },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = {
                            val trimmed = name.trim()
                            if (trimmed.isBlank()) {
                                error = context.getString(R.string.rename_recording_invalid)
                                return@Button
                            }
                            scope.launch {
                                val renamed = RecordingRepository.rename(context, recording, trimmed)
                                if (renamed == null) {
                                    error = context.getString(R.string.rename_recording_failed)
                                } else {
                                    onRenamed()
                                }
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = stringResource(R.string.done),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RecordingInfoDialogContent(
    recording: RecordingEntity,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val detailsText = remember(recording) { buildRecordingDetailsText(context, recording) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(18.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recording_info),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Text(
                text = detailsText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 24.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {},
    )
}

private fun buildRecordingDetailsText(context: Context, recording: RecordingEntity): String {
    val dateFormat = android.text.format.DateFormat.getMediumDateFormat(context)
    val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    val startedAt = Date(recording.startedAtMillis)
    val sizeText = formatShortFileSize(recording.sizeBytes)
    val durationText = formatSavedRecordingDuration(recording.durationMillis)

    return buildString {
        appendLine("${context.getString(R.string.recording_details_name)} ${recording.displayName}")
        appendLine("${context.getString(R.string.recording_details_started)} ${dateFormat.format(startedAt)} ${timeFormat.format(startedAt)}")
        appendLine("${context.getString(R.string.recording_details_duration)} $durationText")
        appendLine("${context.getString(R.string.recording_details_size)} $sizeText")
        appendLine("${context.getString(R.string.recording_details_codec)} ${recording.codecSummary}")
        appendLine("${context.getString(R.string.recording_details_mime)} ${recording.mimeType}")
        appendLine("${context.getString(R.string.recording_details_storage)} ${recording.storageType}")
        append("${context.getString(R.string.recording_details_location)} ${recording.directoryId}")
    }
}

private fun buildListItems(context: Context, recordings: List<RecordingEntity>): List<ListItem> {
    val items = mutableListOf<ListItem>()
    var currentDateHeader = ""
    recordings.forEach { recording ->
        val dateHeader = formatRecordingDateHeader(context, recording.startedAtMillis)
        if (dateHeader != currentDateHeader) {
            currentDateHeader = dateHeader
            items.add(ListItem.Header(dateHeader))
        }
        items.add(
            ListItem.Recording(
                recording = recording,
                timestampLabel = formatRecordingStartTimestamp(context, recording.startedAtMillis),
                fileName = recording.displayName,
                durationLabel = "${formatSavedRecordingDuration(recording.durationMillis)} \u2022 ${recording.codecSummary}",
                sizeLabel = formatShortFileSize(recording.sizeBytes),
            ),
        )
    }
    return items
}
