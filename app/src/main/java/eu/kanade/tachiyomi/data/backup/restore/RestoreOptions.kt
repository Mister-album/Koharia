package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val komgaSettings: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        komgaSettings,
    )

    fun canRestore() = libraryEntries || categories || appSettings || komgaSettings

    companion object {
        val options = persistentListOf(
            Entry(
                label = MR.strings.label_library,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.pref_komga_server,
                getter = RestoreOptions::komgaSettings,
                setter = { options, enabled -> options.copy(komgaSettings = enabled) },
            ),
        )

        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array[0],
            categories = array[1],
            appSettings = array[2],
            komgaSettings = array.getOrElse(4) { array.getOrElse(3) { true } },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
    )
}
