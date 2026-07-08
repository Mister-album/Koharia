package koharia.epub.settings

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class EpubReaderPreferences(
    preferenceStore: PreferenceStore,
) {

    val enableNativeReader: Preference<Boolean> = preferenceStore.getBoolean("epub_reader_enabled", true)

    val preferLocalFile: Preference<Boolean> = preferenceStore.getBoolean("epub_reader_prefer_local_file", true)

    val syncProgressionToKomga: Preference<Boolean> =
        preferenceStore.getBoolean("epub_reader_sync_progression_komga", true)

    val completionThresholdPercent: Preference<Int> =
        preferenceStore.getInt("epub_reader_completion_threshold_percent", 98)
}
