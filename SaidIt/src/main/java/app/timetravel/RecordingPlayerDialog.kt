package app.timetravel

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import java.io.File

internal class RecordingPlayerDialog(
    context: Context,
    private val recording: RecordingEntity,
    private val onPlaybackFailed: () -> Unit,
) {
    private val appContext = context
    private val handler = Handler(Looper.getMainLooper())
    private val content = LayoutInflater.from(context).inflate(R.layout.dialog_recording_player, null, false)
    private val statusText: TextView = content.findViewById(R.id.player_status_text)
    private val metaText: TextView = content.findViewById(R.id.player_meta_text)
    private val elapsedText: TextView = content.findViewById(R.id.player_elapsed_text)
    private val durationText: TextView = content.findViewById(R.id.player_duration_text)
    private val seekBar: SeekBar = content.findViewById(R.id.player_seekbar)
    private val toggleButton: MaterialButton = content.findViewById(R.id.player_toggle_button)
    private val handle = ThemedDialog.create(
        context = context,
        title = recording.displayName,
        content = content,
        positiveText = context.getString(R.string.close),
        negativeText = "",
    )

    private var mediaPlayer: MediaPlayer? = null
    private var released = false
    private var dragging = false
    private var prepared = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            if (!prepared || dragging) {
                if (!released) {
                    handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
                }
                return
            }
            val position = runCatching { player.currentPosition.coerceAtLeast(0) }.getOrDefault(0)
            seekBar.progress = position
            elapsedText.text = formatPlaybackTime(position)
            if (player.isPlaying && !released) {
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    init {
        metaText.text = buildString {
            append(recording.codecSummary)
            append(" • ")
            append(formatSavedRecordingDuration(recording.durationMillis))
            append(" • ")
            append(formatShortFileSize(recording.sizeBytes))
        }
        elapsedText.text = formatPlaybackTime(0)
        durationText.text = formatPlaybackTime(recording.durationMillis.toInt())
        seekBar.max = recording.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar,
                progress: Int,
                fromUser: Boolean,
            ) {
                if (fromUser) {
                    elapsedText.text = formatPlaybackTime(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                mediaPlayer?.seekTo(seekBar.progress)
                scheduleProgressUpdate()
            }
        })
        toggleButton.setOnClickListener {
            val player = mediaPlayer ?: return@setOnClickListener
            if (!prepared) return@setOnClickListener
            if (player.isPlaying) {
                player.pause()
                updateToggleButton(false)
                handler.removeCallbacks(progressUpdater)
            } else {
                player.start()
                updateToggleButton(true)
                scheduleProgressUpdate()
            }
        }
        handle.negativeButton.visibility = android.view.View.GONE
        handle.positiveButton.setOnClickListener { dismiss() }
        handle.dialog.setOnDismissListener { release() }
    }

    fun show() {
        handle.dialog.show()
        if (released) return
        val player = MediaPlayer()
        mediaPlayer = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
        )
        player.setOnPreparedListener { preparedPlayer ->
            if (released) return@setOnPreparedListener
            prepared = true
            seekBar.isEnabled = true
            toggleButton.isEnabled = true
            val duration = preparedPlayer.duration.coerceAtLeast(0)
            seekBar.max = duration.coerceAtLeast(1)
            durationText.text = formatPlaybackTime(duration)
            statusText.text = appContext.getString(R.string.player_ready)
            preparedPlayer.start()
            updateToggleButton(true)
            scheduleProgressUpdate()
        }
        player.setOnCompletionListener {
            updateToggleButton(false)
            handler.removeCallbacks(progressUpdater)
            seekBar.progress = seekBar.max
            elapsedText.text = durationText.text
        }
        player.setOnErrorListener { _, _, _ ->
            playbackFailed()
            true
        }
        try {
            when (RecordingStorageType.valueOf(recording.storageType)) {
                RecordingStorageType.FILE -> player.setDataSource(File(recording.id).absolutePath)
                RecordingStorageType.DOCUMENT -> player.setDataSource(appContext, Uri.parse(recording.id))
            }
            statusText.text = appContext.getString(R.string.player_preparing)
            player.prepareAsync()
        } catch (_: Throwable) {
            playbackFailed()
        }
    }

    fun dismiss() {
        handle.dialog.dismiss()
    }

    private fun scheduleProgressUpdate() {
        handler.removeCallbacks(progressUpdater)
        if (!released) {
            handler.post(progressUpdater)
        }
    }

    private fun updateToggleButton(playing: Boolean) {
        toggleButton.text = appContext.getString(if (playing) R.string.player_pause else R.string.player_play)
    }

    private fun playbackFailed() {
        if (released) return
        Toast.makeText(appContext, R.string.player_failed, Toast.LENGTH_SHORT).show()
        dismiss()
        onPlaybackFailed()
    }

    private fun release() {
        if (released) return
        released = true
        prepared = false
        handler.removeCallbacks(progressUpdater)
        mediaPlayer?.runCatching {
            stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L
    }
}
