package app.smallthingz.timetravel

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingAndHistoryMathTest {
    @Test
    fun formatShortTimer_handlesMinuteAndHourBoundaries() {
        assertEquals("0:00", formatShortTimer(0f))
        assertEquals("1:05", formatShortTimer(65.9f))
        assertEquals("1:01:01", formatShortTimer(3661.2f))
    }

    @Test
    fun formatShortFileSize_usesStableHumanReadableUnits() {
        assertEquals("0.0 MiB", formatShortFileSize(0))
        assertEquals("0.0 MiB", formatShortFileSize(1024))
        assertEquals("0.0 MiB", formatShortFileSize(1536))
        assertEquals("1.0 MiB", formatShortFileSize(1024 * 1024))
    }

    @Test
    fun formatSavedRecordingDuration_handlesShortAndLongClips() {
        assertEquals("5 s", formatSavedRecordingDuration(5_000))
        assertEquals("2m 5s", formatSavedRecordingDuration(125_000))
        assertEquals("1h 2m", formatSavedRecordingDuration(3_720_000))
    }

    @Test
    fun liveExportHistoryConfig_convertsBetweenBytesAndDurationConsistently() {
        val config = LiveExportHistory.Config(
            format = ExportFormat.WAV,
            codec = ExportCodec.PCM_16,
            sampleRate = 48_000,
            channelCount = 2,
            bitrateKbps = null,
        )

        assertEquals(192_000L, config.durationUsToSampleBytes(1_000_000L))
        assertEquals(1_000_000L, config.bytesToDurationUs(192_000L))
        assertEquals(1_000L, config.bytesToDurationMillis(192_000L))
    }

    @Test
    fun durationInput_roundTrips_expectedFormats() {
        assertEquals(5 * 60, parseDurationInput("5"))
        assertEquals(65, parseDurationInput("1:05"))
        assertEquals(3_661, parseDurationInput("1:01:01"))
        assertEquals("5:00", formatDurationInput(5 * 60))
        assertEquals("1:05", formatDurationInput(65))
        assertEquals("1:01:01", formatDurationInput(3_661))
    }
}
