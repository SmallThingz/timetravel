package app.smallthingz.timetravel

object TimeTravelConfig {
    const val PACKAGE_NAME = "app.smallthingz.timetravel"

    const val AUDIO_MEMORY_ENABLED_KEY = "audio_memory_enabled"
    const val AUDIO_MEMORY_SIZE_KEY = "audio_memory_size"
    const val BUFFER_DISK_CACHE_ENABLED_KEY = "buffer_disk_cache_enabled"
    const val AGGRESSIVE_RESTART_ENABLED_KEY = "aggressive_restart_enabled"
    const val WAKE_LOCK_ENABLED_KEY = "wake_lock_enabled"
    const val RETENTION_MODE_KEY = "retention_mode"
    const val RETENTION_SECONDS_KEY = "retention_seconds"
    const val EXPORT_DIRECTORY_URI_KEY = "export_directory_uri"
    const val OUTPUT_FORMAT_KEY = "output_format"
    const val OUTPUT_CODEC_KEY = "output_codec"
    const val OUTPUT_BITRATE_KBPS_KEY = "output_bitrate_kbps"
    const val AUDIO_SOURCE_KEY = "audio_source"
    const val CHANNEL_MODE_KEY = "channel_mode"
    const val INPUT_ROUTE_KEY = "input_route"
    const val SAMPLE_RATE_KEY = "sample_rate"
    const val THEME_MODE_KEY = "theme_mode"
    const val HISTORY_CHUNK_SECONDS_KEY = "history_chunk_seconds"
    const val AUTO_MERGE_MODE_KEY = "auto_merge_mode"
    const val AUTO_MERGE_DIVISOR_KEY = "auto_merge_divisor"
    const val AUTO_MERGE_CUSTOM_SECONDS_KEY = "auto_merge_custom_seconds"
    const val AUTO_MERGE_CUSTOM_SIZE_MIB_KEY = "auto_merge_custom_size_mib"
    const val AUTO_MERGE_EAGER_ENABLED_KEY = "auto_merge_eager_enabled"
    const val DEBUG_CHUNKS_TAB_ENABLED_KEY = "debug_chunks_tab_enabled"
    const val CUSTOM_EXPORT_MODE_KEY = "custom_export_mode"
    const val CUSTOM_EXPORT_UNIT_KEY = "custom_export_unit"
    const val CUSTOM_EXPORT_PAST_SECONDS_KEY = "custom_export_past_seconds"
    const val CUSTOM_EXPORT_PAST_SIZE_MIB_KEY = "custom_export_past_size_mib"


    const val APP_STORAGE_FOLDER_NAME = "Timetravel"
    const val BUFFER_CACHE_FOLDER_NAME = "buffer-cache"

    const val PREFERRED_DEFAULT_SAMPLE_RATE = 44_100
    const val DEFAULT_RETENTION_SECONDS = 86_400L
    const val DEFAULT_RETENTION_SIZE_BYTES = 512L * 1024L * 1024L

    val DEFAULT_OUTPUT_FORMAT: ExportFormat = ExportFormat.WAV
    val DEFAULT_OUTPUT_CODEC: ExportCodec = ExportCodec.PCM_16
    val DEFAULT_CHANNEL_MODE: ChannelMode = ChannelMode.MONO
    val DEFAULT_CUSTOM_EXPORT_MODE: CustomExportMode = CustomExportMode.PAST
    val DEFAULT_CUSTOM_EXPORT_UNIT: CustomExportUnit = CustomExportUnit.TIME
}
