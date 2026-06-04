package app.smallthingz.timetravel

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

internal class RecordingPlayerDialog(
    context: Context,
    private val recording: RecordingEntity,
    private val onPlaybackFailed: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val content = LayoutInflater.from(context).inflate(R.layout.dialog_recording_player, FrameLayout(context), false)
    private val metaText: TextView = content.findViewById(R.id.player_meta_text)
    private val elapsedText: TextView = content.findViewById(R.id.player_elapsed_text)
    private val durationText: TextView = content.findViewById(R.id.player_duration_text)
    private val seekBar: SeekBar = content.findViewById(R.id.player_seekbar)
    private val toggleButton: ImageButton = content.findViewById(R.id.player_toggle_button)
    private val seekBackButton: ImageButton = content.findViewById(R.id.player_seek_back_button)
    private val seekForwardButton: ImageButton = content.findViewById(R.id.player_seek_forward_button)
    private val infoButton = ThemedDialog.createHeaderIconButton(
        context = context,
        iconResId = R.drawable.ic_info,
        contentDescription = context.getString(R.string.recording_info),
    ).apply {
        (layoutParams as LinearLayout.LayoutParams).marginEnd = (context.resources.displayMetrics.density * 8).toInt()
    }
    private val handle = ThemedDialog.create(
        context = context,
        title = recording.displayName,
        content = content,
        positiveText = null,
        negativeText = null,
        headerAccessory = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(infoButton)
        },
        headerAccessoryGravity = Gravity.END,
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
            val position = try { player.currentPosition.coerceAtLeast(0) } catch (_: IllegalStateException) { 0 }
            seekBar.progress = position
            elapsedText.text = formatPlaybackTime(position)
            if (player.isPlaying && !released) {
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            }
        }
    }

    init {
        metaText.text = buildPlayerCodecSummary(recording.codecSummary)
        content.findViewById<TextView>(R.id.player_size_text).text = formatShortFileSize(recording.sizeBytes)
        elapsedText.text = formatPlaybackTime(0)
        durationText.text = formatPlaybackTime(recording.durationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
                if (!prepared) return
                mediaPlayer?.seekTo(seekBar.progress)
                scheduleProgressUpdate()
            }
        })
        seekBackButton.setOnClickListener { seekBy(-SEEK_JUMP_MS) }
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
        seekForwardButton.setOnClickListener { seekBy(SEEK_JUMP_MS) }
        infoButton.setOnClickListener { showRecordingInfoDialog(appContext, recording) }
        handle.dialog.setOnDismissListener { release() }
        setPlaybackControlsEnabled(false)
        updateToggleButton(false)
    }

    fun show() {
        if (released) return
        handle.dialog.show()
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
            setPlaybackControlsEnabled(true)
            val duration = preparedPlayer.duration.coerceAtLeast(0)
            seekBar.max = duration.coerceAtLeast(1)
            durationText.text = formatPlaybackTime(duration)
            preparedPlayer.start()
            updateToggleButton(true)
            scheduleProgressUpdate()
        }
        player.setOnSeekCompleteListener {
            if (released) return@setOnSeekCompleteListener
            seekBar.progress = it.currentPosition.coerceAtLeast(0)
            elapsedText.text = formatPlaybackTime(it.currentPosition.coerceAtLeast(0))
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
            player.prepareAsync()
        } catch (_: Throwable) {
            playbackFailed()
        }
    }

    fun dismiss() {
        handle.dialog.dismiss()
    }

    private fun seekBy(deltaMs: Int) {
        val player = mediaPlayer ?: return
        if (!prepared) return
        val currentPosition = try { player.currentPosition } catch (_: IllegalStateException) { 0 }
        val targetPosition = (currentPosition + deltaMs).coerceIn(0, seekBar.max)
        player.seekTo(targetPosition)
        scheduleProgressUpdate()
    }

    private fun setPlaybackControlsEnabled(enabled: Boolean) {
        toggleButton.isEnabled = enabled
        seekBackButton.isEnabled = enabled
        seekForwardButton.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.5f
        listOf(toggleButton, seekBackButton, seekForwardButton).forEach { button ->
            button.alpha = alpha
        }
    }

    private fun scheduleProgressUpdate() {
        handler.removeCallbacks(progressUpdater)
        if (!released) {
            handler.post(progressUpdater)
        }
    }

    private fun updateToggleButton(playing: Boolean) {
        toggleButton.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        toggleButton.contentDescription = appContext.getString(if (playing) R.string.player_pause else R.string.player_play)
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
        const val SEEK_JUMP_MS = 10_000
    }
}
