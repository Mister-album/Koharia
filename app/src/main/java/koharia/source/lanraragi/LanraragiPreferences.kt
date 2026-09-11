package koharia.source.lanraragi

import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.lanraragi.LanraragiArchiveOpenMode
import tachiyomi.core.common.preference.Preference

class LanraragiPreferences(connectionId: Long) {
    private val preferences = sourcePreferences("source_$connectionId")
    val address: String get() = preferences.getString("address", "").orEmpty()
    val apiKey: String get() = preferences.getString(Preference.privateKey("api_key"), "").orEmpty()
    val indexedAddress: String get() = preferences.getString(Preference.appStateKey("indexed_address"), "").orEmpty()
    val defaultCategory: String get() = preferences.getString("default_category", "").orEmpty()
    val groupCollections: Boolean get() = preferences.getBoolean("group_collections", true)
    val archiveOpenMode: LanraragiArchiveOpenMode get() = LanraragiArchiveOpenMode.entries
        .firstOrNull { it.name == preferences.getString("archive_open_mode", "") }
        ?: LanraragiArchiveOpenMode.READER
    fun markIndexed(address: String) {
        preferences.edit().putString(Preference.appStateKey("indexed_address"), address).apply()
    }
    fun save(
        address: String,
        apiKey: String,
        archiveOpenMode: LanraragiArchiveOpenMode = this.archiveOpenMode,
        defaultCategory: String = this.defaultCategory,
        groupCollections: Boolean = this.groupCollections,
    ) {
        check(
            preferences.edit().putString("address", address)
                .putString("archive_open_mode", archiveOpenMode.name)
                .putString("default_category", defaultCategory)
                .putBoolean("group_collections", groupCollections)
                .putString(Preference.privateKey("api_key"), apiKey).commit(),
        )
    }
}
