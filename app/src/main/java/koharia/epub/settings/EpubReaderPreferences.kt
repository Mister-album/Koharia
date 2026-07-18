package koharia.epub.settings

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class EpubReaderPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val preferLocalFile: Preference<Boolean> = preferenceStore.getBoolean("epub_reader_prefer_local_file", true)

    val syncProgressionToKomga: Preference<Boolean> =
        preferenceStore.getBoolean("epub_reader_sync_progression_komga", true)

    val correctKomgaServerTimestamps: Preference<Boolean> =
        preferenceStore.getBoolean("epub_reader_correct_komga_server_timestamps", true)

    fun komgaServerTimestampOffsetMinutes(sourceId: Long): Preference<Long> =
        preferenceStore.getLong("epub_reader_komga_server_timestamp_offset_minutes_$sourceId", 0L)

    val completionThresholdPercent: Preference<Int> =
        preferenceStore.getInt("epub_reader_completion_threshold_percent", 98)

    val persistReaderSettingsChanges: Preference<Boolean> =
        preferenceStore.getBoolean("epub_reader_persist_settings_changes", false)
}
