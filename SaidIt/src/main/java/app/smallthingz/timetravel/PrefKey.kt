package app.smallthingz.timetravel

import android.content.SharedPreferences

enum class PrefKey {
    AUDIO_MEMORY_ENABLED,
    AUDIO_MEMORY_SIZE,
    BUFFER_DISK_CACHE_ENABLED,
    AGGRESSIVE_RESTART_ENABLED,
    WAKE_LOCK_ENABLED,
    RETENTION_MODE,
    RETENTION_SECONDS,
    EXPORT_DIRECTORY_URI,
    OUTPUT_FORMAT,
    OUTPUT_CODEC,
    OUTPUT_BITRATE_KBPS,
    PCM_SAMPLE_FORMAT,
    AUDIO_SOURCE,
    CHANNEL_MODE,
    INPUT_ROUTE,
    SAMPLE_RATE,
    THEME_MODE,
    HISTORY_CHUNK_SECONDS,
    AUTO_MERGE_MODE,
    AUTO_MERGE_DIVISOR,
    AUTO_MERGE_CUSTOM_SECONDS,
    AUTO_MERGE_CUSTOM_SIZE_MIB,
    AUTO_MERGE_EAGER_ENABLED,
    DEBUG_CHUNKS_TAB_ENABLED,
    CUSTOM_EXPORT_MODE,
    CUSTOM_EXPORT_UNIT,
    CUSTOM_EXPORT_PAST_SECONDS,
    CUSTOM_EXPORT_PAST_SIZE_MIB,
}

fun SharedPreferences.getString(key: PrefKey, default: String?): String? = getString(key.name, default)
fun SharedPreferences.getInt(key: PrefKey, default: Int): Int = getInt(key.name, default)
fun SharedPreferences.getLong(key: PrefKey, default: Long): Long = getLong(key.name, default)
fun SharedPreferences.getBoolean(key: PrefKey, default: Boolean): Boolean = getBoolean(key.name, default)
fun SharedPreferences.contains(key: PrefKey): Boolean = contains(key.name)
fun SharedPreferences.Editor.putString(key: PrefKey, value: String): SharedPreferences.Editor = putString(key.name, value)
fun SharedPreferences.Editor.putInt(key: PrefKey, value: Int): SharedPreferences.Editor = putInt(key.name, value)
fun SharedPreferences.Editor.putLong(key: PrefKey, value: Long): SharedPreferences.Editor = putLong(key.name, value)
fun SharedPreferences.Editor.putBoolean(key: PrefKey, value: Boolean): SharedPreferences.Editor = putBoolean(key.name, value)
fun SharedPreferences.Editor.remove(key: PrefKey): SharedPreferences.Editor = remove(key.name)
