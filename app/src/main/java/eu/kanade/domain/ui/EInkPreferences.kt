package eu.kanade.domain.ui

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class EInkPreferences(
    preferenceStore: PreferenceStore,
) {

    val enabled: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_mode", false)

    val appRefreshEnabled: Preference<Boolean> = preferenceStore.getBoolean("pref_eink_app_refresh", false)

    val appRefreshInterval: Preference<Int> = preferenceStore.getInt("pref_eink_app_refresh_interval", 1)

    val appRefreshDurationMillis: Preference<Int> = preferenceStore.getInt(
        "pref_eink_app_refresh_duration",
        DEFAULT_REFRESH_DURATION_MILLIS,
    )

    val appRefreshColor: Preference<RefreshColor> = preferenceStore.getEnum(
        "pref_eink_app_refresh_color",
        RefreshColor.BLACK,
    )

    enum class RefreshColor {
        BLACK,
        WHITE,
        WHITE_BLACK,
    }

    companion object {
        const val DEFAULT_REFRESH_DURATION_MILLIS = 100
        const val MIN_REFRESH_DURATION_MILLIS = 100
        const val MAX_REFRESH_DURATION_MILLIS = 1500
        const val MIN_REFRESH_INTERVAL = 1
        const val MAX_REFRESH_INTERVAL = 10
    }
}
