package eu.kanade.tachiyomi.ui.reader.setting

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class ReaderEInkPreferences(
    preferenceStore: PreferenceStore,
) {

    val flashOnPageChange: Preference<Boolean> = preferenceStore.getBoolean("pref_reader_flash", false)

    val flashDurationMillis: Preference<Int> = preferenceStore.getInt(
        "pref_reader_flash_duration",
        DEFAULT_FLASH_DURATION_MILLIS,
    )

    val flashPageInterval: Preference<Int> = preferenceStore.getInt("pref_reader_flash_interval", 1)

    val flashColor: Preference<FlashColor> = preferenceStore.getEnum("pref_reader_flash_mode", FlashColor.BLACK)

    enum class FlashColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    companion object {
        const val DEFAULT_FLASH_DURATION_MILLIS = 100
    }
}
