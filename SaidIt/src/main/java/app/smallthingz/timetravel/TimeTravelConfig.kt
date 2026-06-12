package app.smallthingz.timetravel

object TimeTravelConfig {


    const val APP_STORAGE_FOLDER_NAME = "Timetravel"
    const val BUFFER_CACHE_FOLDER_NAME = "buffer-cache"
    const val BUFFER_META_FILE_NAME = "buffer.meta"
    const val BUFFER_PCM_FILE_NAME = "buffer.pcm"
    const val HISTORY_CACHE_FOLDER_NAME = "live-export-history"
    const val HISTORY_CACHE_PATH_SEGMENT = "/live-export-history/"
    const val DATABASE_FILE_NAME = "timetravel-recordings.db"
    const val MIME_AUDIO_PREFIX = "audio/"
    const val FALLBACK_MIME_TYPE_AUDIO = "audio/*"
    const val FALLBACK_DISPLAY_NAME = "TimeTravel"
    const val MIB_SUFFIX = " MiB"
    const val BITRATE_SUFFIX = " kbps"
    const val ESTIMATE_EXACT_PREFIX = "="
    const val ESTIMATE_APPROX_PREFIX = "~"
    const val KEY_SELECTED_TAB = "selected_tab"
    const val FRAGMENT_TAG_CAPTURE = "capture-fragment"
    const val FRAGMENT_TAG_FILES = "files-fragment"
    const val PAYLOAD_SELECTION = "selection"
    const val CODEC_SUMMARY_SEPARATOR = " · "

    const val PREFERRED_DEFAULT_SAMPLE_RATE = 44_100
    const val DEFAULT_RETENTION_SECONDS = 86_400L
    const val DEFAULT_RETENTION_SIZE_BYTES = 512L * 1024L * 1024L

    val DEFAULT_OUTPUT_FORMAT: ExportFormat = ExportFormat.WAV
    val DEFAULT_OUTPUT_CODEC: ExportCodec = ExportCodec.PCM_16
    val DEFAULT_CHANNEL_MODE: ChannelMode = ChannelMode.MONO
    val DEFAULT_CUSTOM_EXPORT_MODE: CustomExportMode = CustomExportMode.PAST
    val DEFAULT_CUSTOM_EXPORT_UNIT: CustomExportUnit = CustomExportUnit.TIME

    const val FORMAT_DURATION_HMS = "%d:%02d:%02d"
    const val FORMAT_DURATION_MS = "%d:%02d"
    const val FORMAT_SIZE_MIB = "0.0"
    const val FORMAT_RETENTION_SIZE_MIB = "0.###"
    const val FORMAT_SAMPLE_RATE_KHZ = "%.2f kHz"
    const val MODE_LABEL_RECORDING = "Recording"
    const val MODE_LABEL_LIVE = "Live"
    const val MODE_LABEL_PAUSED = "Paused"
    const val STATUS_SAMPLES = " samples"
    const val STATUS_MERGING = " · Merging "
    const val STATUS_NEXT = " · Next "
    const val OPERATION_BACKGROUND_MERGE = "BACKGROUND_MERGE"
    const val OPERATION_EXPORT_MERGE = "EXPORT_MERGE"
    const val OPERATION_HISTORY_REENCODE = "HISTORY_REENCODE"
    const val THREAD_NAME_AUDIO = "timeTravelAudioThread"
    const val THREAD_NAME_EXPORT = "timeTravelExportThread"
    const val THREAD_NAME_EXPORT_WORK = "timeTravelExportWork"
    const val DEBUG_ACTION_PREFIX = "app.smallthingz.timetravel.debug."
    const val EXTRA_SECONDS = "seconds"
    const val EXTRA_FORMAT = "format"
    const val EXTRA_CODEC = "codec"
    const val EXTRA_BITRATE_KBPS = "bitrate_kbps"

    const val FORMAT_DATE_DEBUG = "dd MMM yyyy HH:mm:ss"
    const val FORMAT_DATE_INFO = "dd MMM yyyy HH:mm:ss z"

    const val TEMP_FILE_SUFFIX = ".tmp"
}
