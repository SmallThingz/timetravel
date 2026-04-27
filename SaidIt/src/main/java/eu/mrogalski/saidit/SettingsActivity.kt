package eu.mrogalski.saidit

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import eu.mrogalski.StringFormat
import eu.mrogalski.android.TimeFormat

class SettingsActivity : AppCompatActivity() {
    private val memoryClickListener = MemoryOnClickListener()
    private val customMemoryClickListener = CustomMemoryOnClickListener()
    private val qualityClickListener = QualityOnClickListener()
    private val dialog = WorkingDialog().apply {
        descriptionStringId = R.string.work_preparing_memory
    }
    private val timeFormatResult = TimeFormat.Result()

    private var service: SaidItService? = null

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            val typedBinder = binder as SaidItService.BackgroundRecorderBinder
            service = typedBinder.service
            syncUi()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEchoSystemBars()

        val root = layoutInflater.inflate(R.layout.activity_settings, null) as ViewGroup
        UiFonts.styleSettings(root, this)

        root.findViewById<View>(R.id.settings_return).setOnClickListener { finish() }

        val settingsLayout = root.findViewById<LinearLayout>(R.id.settings_layout)

        val frameLayout = object : FrameLayout(this) {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun fitSystemWindows(insets: Rect): Boolean {
                settingsLayout.setPadding(insets.left, insets.top, insets.right, insets.bottom)
                return true
            }
        }

        frameLayout.addView(root)

        root.findViewById<View>(R.id.memory_low).setOnClickListener(memoryClickListener)
        root.findViewById<View>(R.id.memory_medium).setOnClickListener(memoryClickListener)
        root.findViewById<View>(R.id.memory_high).setOnClickListener(memoryClickListener)
        root.findViewById<View>(R.id.memory_custom_apply).setOnClickListener(customMemoryClickListener)

        initSampleRateButton(root, R.id.quality_8kHz, 8000, 11025)
        initSampleRateButton(root, R.id.quality_16kHz, 16000, 22050)
        initSampleRateButton(root, R.id.quality_48kHz, 48000, 44100)

        setContentView(frameLayout)
        findViewById<TextView>(R.id.history_limit).typeface = Typeface.MONOSPACE
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SaidItService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
    }

    private fun syncUi() {
        val currentService = service ?: return
        findViewById<Button>(R.id.memory_low).text =
            StringFormat.shortFileSize(lowMemorySize)
        findViewById<Button>(R.id.memory_medium).text =
            StringFormat.shortFileSize(mediumMemorySize)
        findViewById<Button>(R.id.memory_high).text =
            StringFormat.shortFileSize(highMemorySize)
        syncCustomMemoryField()

        TimeFormat.naturalLanguage(
            resources,
            currentService.bytesToSeconds * currentService.memorySize,
            timeFormatResult,
        )
        findViewById<TextView>(R.id.history_limit).text = timeFormatResult.text

        highlightButtons(currentService.samplingRate)
    }

    private fun highlightButtons(samplingRate: Int) {
        highlightMemoryButtons(configuredMemorySize)

        val button = when {
            samplingRate >= 44100 -> 3
            samplingRate >= 16000 -> 2
            else -> 1
        }
        highlightButton(R.id.quality_8kHz, R.id.quality_16kHz, R.id.quality_48kHz, button)
    }

    private fun highlightMemoryButtons(configuredMemorySize: Long) {
        findViewById<View>(R.id.memory_low).setBackgroundResource(
            if (configuredMemorySize == lowMemorySize) R.drawable.green_button else R.drawable.gray_button,
        )
        findViewById<View>(R.id.memory_medium).setBackgroundResource(
            if (configuredMemorySize == mediumMemorySize) R.drawable.green_button else R.drawable.gray_button,
        )
        findViewById<View>(R.id.memory_high).setBackgroundResource(
            if (configuredMemorySize == highMemorySize) R.drawable.green_button else R.drawable.gray_button,
        )
    }

    private fun highlightButton(button1: Int, button2: Int, button3: Int, selected: Int) {
        findViewById<View>(button1).setBackgroundResource(
            if (selected == 1) R.drawable.green_button else R.drawable.gray_button,
        )
        findViewById<View>(button2).setBackgroundResource(
            if (selected == 2) R.drawable.green_button else R.drawable.gray_button,
        )
        findViewById<View>(button3).setBackgroundResource(
            if (selected == 3) R.drawable.green_button else R.drawable.gray_button,
        )
    }

    private fun syncCustomMemoryField() {
        val customMemorySize = findViewById<EditText>(R.id.memory_custom_size_mb)
        val currentValue = bytesToMegabytes(configuredMemorySize).toString()
        if (currentValue != customMemorySize.text.toString()) {
            customMemorySize.setText(currentValue)
        }
    }

    private val maxMemorySize: Long
        get() = Runtime.getRuntime().maxMemory()

    private val lowMemorySize: Long
        get() = maxMemorySize / 4

    private val mediumMemorySize: Long
        get() = maxMemorySize / 2

    private val highMemorySize: Long
        get() = maxMemorySize * 3 / 4

    private val configuredMemorySize: Long
        get() = getSharedPreferences(SaidIt.PACKAGE_NAME, MODE_PRIVATE)
            .getLong(SaidIt.AUDIO_MEMORY_SIZE_KEY, lowMemorySize)

    private fun bytesToMegabytes(bytes: Long): Long {
        return maxOf(1L, kotlin.math.ceil(bytes.toDouble() / BYTES_IN_MEGABYTE).toLong())
    }

    private fun megabytesToMemorySize(memoryInMegabytes: Long): Long {
        if (memoryInMegabytes > Long.MAX_VALUE / BYTES_IN_MEGABYTE) {
            return maxMemorySize
        }
        return minOf(memoryInMegabytes * BYTES_IN_MEGABYTE, maxMemorySize)
    }

    private fun initSampleRateButton(
        layout: ViewGroup,
        buttonId: Int,
        primarySampleRate: Int,
        secondarySampleRate: Int,
    ) {
        val button = layout.findViewById<Button>(buttonId)
        button.setOnClickListener(qualityClickListener)
        when {
            testSampleRateValid(primarySampleRate) -> {
                button.text = "${primarySampleRate / 1000} kHz"
                button.tag = primarySampleRate
            }

            testSampleRateValid(secondarySampleRate) -> {
                button.text = "${secondarySampleRate / 1000} kHz"
                button.tag = secondarySampleRate
            }

            else -> button.visibility = View.GONE
        }
    }

    private fun testSampleRateValid(sampleRate: Int): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return bufferSize > 0
    }

    private fun updateMemorySize(memorySize: Long) {
        val currentService = service ?: return
        dialog.show(supportFragmentManager, "Preparing memory")
        currentService.setMemorySize(memorySize)
        currentService.getState(
            object : SaidItService.StateCallback {
                override fun state(
                    listeningEnabled: Boolean,
                    recording: Boolean,
                    memorized: Float,
                    totalMemory: Float,
                    recorded: Float,
                ) {
                    syncUi()
                    if (dialog.isVisible) {
                        dialog.dismissAllowingStateLoss()
                    }
                }
            },
        )
    }

    private inner class MemoryOnClickListener : View.OnClickListener {
        override fun onClick(v: View) {
            updateMemorySize(getMemorySize(v))
        }

        private fun getMemorySize(button: View): Long {
            return when (button.id) {
                R.id.memory_high -> highMemorySize
                R.id.memory_medium -> mediumMemorySize
                R.id.memory_low -> lowMemorySize
                else -> 0L
            }
        }
    }

    private inner class CustomMemoryOnClickListener : View.OnClickListener {
        override fun onClick(v: View) {
            val customMemorySize = findViewById<EditText>(R.id.memory_custom_size_mb)
            val memoryText = customMemorySize.text.toString().trim()

            val memoryInMegabytes = memoryText.toLongOrNull()
            if (memoryInMegabytes == null || memoryInMegabytes <= 0) {
                customMemorySize.error = getString(R.string.custom_memory_size_invalid)
                return
            }

            customMemorySize.error = null
            updateMemorySize(megabytesToMemorySize(memoryInMegabytes))
        }
    }

    private inner class QualityOnClickListener : View.OnClickListener {
        override fun onClick(v: View) {
            val currentService = service ?: return
            val sampleRate = getSampleRate(v)
            dialog.show(supportFragmentManager, "Preparing memory")
            currentService.setSampleRate(sampleRate)
            currentService.getState(
                object : SaidItService.StateCallback {
                    override fun state(
                        listeningEnabled: Boolean,
                        recording: Boolean,
                        memorized: Float,
                        totalMemory: Float,
                        recorded: Float,
                    ) {
                        syncUi()
                        if (dialog.isVisible) {
                            dialog.dismissAllowingStateLoss()
                        }
                    }
                },
            )
        }

        private fun getSampleRate(button: View): Int {
            val tag = button.tag
            return if (tag is Int) tag else 8000
        }
    }

    private companion object {
        const val BYTES_IN_MEGABYTE = 1024L * 1024L
    }
}
