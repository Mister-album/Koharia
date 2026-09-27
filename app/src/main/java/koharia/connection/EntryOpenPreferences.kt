package koharia.connection

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

enum class EntryOpenMode { READER, PAGE_PREVIEW, DETAILS }

class EntryOpenPreferences(preferenceStore: PreferenceStore) {
    val localSingleComic: Preference<String> = preferenceStore.getString(
        "entry_open_mode_local_single_comic",
        EntryOpenMode.READER.name,
    )
    val komgaSingleBook: Preference<String> = preferenceStore.getString(
        "entry_open_mode_komga_single_book",
        EntryOpenMode.DETAILS.name,
    )

    val localSingleBook: Preference<String> = preferenceStore.getString(
        "entry_open_mode_local_single_book",
        EntryOpenMode.DETAILS.name,
    )

    fun localMode(): EntryOpenMode = localSingleComic.get().toEntryOpenMode(EntryOpenMode.READER)
    fun localBookMode(): EntryOpenMode = localSingleBook.get().toEntryOpenMode(EntryOpenMode.DETAILS)
        .takeUnless { it == EntryOpenMode.PAGE_PREVIEW } ?: EntryOpenMode.DETAILS
    fun komgaMode(): EntryOpenMode = komgaSingleBook.get().toEntryOpenMode(EntryOpenMode.DETAILS)
}

private fun String.toEntryOpenMode(default: EntryOpenMode): EntryOpenMode =
    EntryOpenMode.entries.firstOrNull { it.name == this } ?: default
