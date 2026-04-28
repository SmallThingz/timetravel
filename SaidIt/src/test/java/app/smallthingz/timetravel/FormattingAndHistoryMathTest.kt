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
        assertEquals("0 B", formatShortFileSize(0))
        assertEquals("1 KB", formatShortFileSize(1024))
        assertEquals("1.5 KB", formatShortFileSize(1536))
        assertEquals("1 MB", formatShortFileSize(1024 * 1024))
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
            codec = ExportCodec.WAV,
            sampleRate = 48_000,
            channelCount = 2,
            bitrateKbps = null,
        )

        assertEquals(192_000L, config.durationUsToSampleBytes(1_000_000L))
        assertEquals(1_000_000L, config.bytesToDurationUs(192_000L))
        assertEquals(1_000L, config.bytesToDurationMillis(192_000L))
    }

    @Test
    fun liveExportHistoryConfig_segmentDuration_isClamped() {
        val config = LiveExportHistory.Config(
            codec = ExportCodec.AAC_LC,
            sampleRate = 48_000,
            channelCount = 2,
            bitrateKbps = 192,
        )

        assertEquals(2_000L, config.suggestedSegmentDurationMillis(96_000L))
        assertEquals(2_000L, config.suggestedSegmentDurationMillis(96_000_000L))
    }
}
