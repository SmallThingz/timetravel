package app.smallthingz.timetravel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattingAndHistoryMathTest {
    @Test
    fun missingRecordingTtl_startsFromFirstObservedMiss_notCreationTime() {
        val createdAt = 1_000L
        val firstMissingAt = createdAt + RecordingRepository.MISSING_RECORDING_TTL_MILLIS - 1L
        val oldRecording = RecordingEntity(
            id = "id",
            displayName = "clip.m4a",
            mimeType = "audio/mp4",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "aac",
            storageType = RecordingStorageType.FILE.name,
            directoryId = "dir",
            createdAtMillis = createdAt,
            lastSeenAtMillis = createdAt,
            missingSinceMillis = firstMissingAt,
        )

        assertTrue(!isMissingRecordingExpired(oldRecording, firstMissingAt + RecordingRepository.MISSING_RECORDING_TTL_MILLIS - 1L))
        assertTrue(isMissingRecordingExpired(oldRecording, firstMissingAt + RecordingRepository.MISSING_RECORDING_TTL_MILLIS))
    }

    @Test
    fun mergeObservedRecording_preservesOriginalCreationTime_andClearsMissingState() {
        val existing = RecordingEntity(
            id = "id",
            displayName = "clip.m4a",
            mimeType = "audio/mp4",
            startedAtMillis = 500L,
            durationMillis = 1_000L,
            sizeBytes = 2_000L,
            codecSummary = "aac",
            storageType = RecordingStorageType.FILE.name,
            directoryId = "dir",
            createdAtMillis = 10L,
            lastSeenAtMillis = 20L,
            missingSinceMillis = 30L,
        )
        val observed = existing.copy(createdAtMillis = 999L, lastSeenAtMillis = 999L, missingSinceMillis = 999L)

        val merged = mergeObservedRecording(existing, observed, nowMillis = 40L)

        assertEquals(10L, merged.createdAtMillis)
        assertEquals(40L, merged.lastSeenAtMillis)
        assertEquals(null, merged.missingSinceMillis)
    }

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

    @Test
    fun defaultCodecBitrate_prefersOpusMonoAt96Kbps() {
        assertEquals(96, defaultCodecBitrateKbps(ExportCodec.OPUS, 48_000, 1))
        assertEquals(160, defaultCodecBitrateKbps(ExportCodec.OPUS, 48_000, 2))
    }

    @Test
    fun sampleRatePreference_prefers44k1_thenNearestHigher() {
        assertEquals(listOf(44_100, 48_000, 32_000), orderSampleRatesByPreference(listOf(48_000, 32_000, 44_100), 44_100))
        assertEquals(listOf(48_000, 32_000, 24_000), orderSampleRatesByPreference(listOf(48_000, 24_000, 32_000), 44_100))
    }

    @Test
    fun estimateExportDuration_scalesWithRequestedSize() {
        val nineHundredMiB = 900L * 1024L * 1024L
        val nineThousandMiB = 9_000L * 1024L * 1024L

        val smaller = estimateExportDurationSeconds(
            format = ExportFormat.OGG,
            codec = ExportCodec.OPUS,
            sampleRate = 48_000,
            channelCount = 1,
            sizeBytes = nineHundredMiB,
            bitrateKbps = 96,
        )
        val larger = estimateExportDurationSeconds(
            format = ExportFormat.OGG,
            codec = ExportCodec.OPUS,
            sampleRate = 48_000,
            channelCount = 1,
            sizeBytes = nineThousandMiB,
            bitrateKbps = 96,
        )

        assertTrue(larger > smaller)
    }

    @Test
    fun bytesForRetentionSeconds_handlesLongDurationsWithoutHeapClamp() {
        val fortyEightHours = 48L * 60L * 60L
        val bytes = bytesForRetentionSeconds(fortyEightHours, 48_000, 1)

        assertEquals(16_588_800_000L, bytes)
        assertEquals(fortyEightHours, retentionSecondsForBytes(bytes, 48_000, 1))
    }

    @Test
    fun codecFormatMatrix_keepsExpectedContainers() {
        assertTrue(ExportFormat.THREE_GPP in ExportCodec.AMR_WB.supportedFormats)
        assertTrue(ExportFormat.AMR_WB_FILE in ExportCodec.AMR_WB.supportedFormats)
        assertTrue(ExportFormat.OGG in ExportCodec.OPUS.supportedFormats)
    }

    @Test
    fun rawContainerConstraints_enforceOnlySupportedConfigurations() {
        assertTrue(isExportConfigurationSupported(ExportFormat.AAC_ADTS, ExportCodec.AAC_LC, 44_100, 1))
        assertTrue(isExportConfigurationSupported(ExportFormat.MPEG_2_TS, ExportCodec.AAC_LC, 48_000, 2))
        assertTrue(isExportConfigurationSupported(ExportFormat.AMR_NB_FILE, ExportCodec.AMR_NB, 8_000, 1))
        assertTrue(isExportConfigurationSupported(ExportFormat.AMR_WB_FILE, ExportCodec.AMR_WB, 16_000, 1))

        assertTrue(!isExportConfigurationSupported(ExportFormat.AAC_ADTS, ExportCodec.AAC_ELD, 44_100, 1))
        assertTrue(!isExportConfigurationSupported(ExportFormat.MPEG_2_TS, ExportCodec.AAC_LC, 7_000, 2))
        assertTrue(!isExportConfigurationSupported(ExportFormat.AMR_NB_FILE, ExportCodec.AMR_NB, 16_000, 1))
        assertTrue(!isExportConfigurationSupported(ExportFormat.AMR_WB_FILE, ExportCodec.AMR_WB, 16_000, 2))
        assertTrue(!isExportConfigurationSupported(ExportFormat.THREE_GPP, ExportCodec.AMR_NB, 8_000, 2))
    }

    @Test
    fun parseAdtsHeader_readsLcRateAndChannels() {
        val header = buildAdtsHeader(sampleRate = 44_100, channelCount = 2, payloadSize = 256)
        val parsed = parseAdtsHeader(header)

        assertEquals(2, parsed?.profile)
        assertEquals(44_100, parsed?.sampleRate)
        assertEquals(2, parsed?.channelCount)
    }

    @Test
    fun detectRawAmrCodec_distinguishesNbAndWbHeaders() {
        assertEquals(ExportCodec.AMR_NB, detectRawAmrCodec("#!AMR\n".toByteArray(Charsets.US_ASCII)))
        assertEquals(ExportCodec.AMR_WB, detectRawAmrCodec("#!AMR-WB\n".toByteArray(Charsets.US_ASCII)))
        assertEquals(null, detectRawAmrCodec("bogus".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun muxedExportLimit_isFourGiBClass() {
        val minExpected = (4L * 1024L * 1024L * 1024L) - (16L * 1024L * 1024L)
        assertTrue(exportFileSizeLimitBytes(ExportFormat.M4A) >= minExpected)
        assertTrue(exportFileSizeLimitBytes(ExportFormat.THREE_GPP) >= minExpected)
        assertTrue(exportFileSizeLimitBytes(ExportFormat.OGG) >= minExpected)
        assertTrue(exportFileSizeLimitBytes(ExportFormat.WEBM) >= minExpected)
        assertTrue(exportFileSizeLimitBytes(ExportFormat.AMR_WB_FILE) >= 4L * 1024L * 1024L * 1024L)
    }

    @Test
    fun audioMemory_readTraversesAcrossChunksInOrder() {
        val memory = AudioMemory()
        memory.allocate(960_000)
        val source = ByteArray(600_000) { (it % 251).toByte() }
        memory.write(source, 0, source.size)

        val collected = ArrayList<Byte>()
        memory.read(120_000, 300_000) { array, offset, count ->
            repeat(count) { index ->
                collected += array[offset + index]
            }
            count
        }

        assertEquals(300_000, collected.size)
        assertEquals(source[120_000], collected.first())
        assertEquals(source[419_999], collected.last())
    }
}
