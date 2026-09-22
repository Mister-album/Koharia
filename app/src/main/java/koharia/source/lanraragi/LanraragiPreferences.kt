package koharia.source.lanraragi

import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.lanraragi.LanraragiArchiveOpenMode
import koharia.lanraragi.LanraragiFilter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference

class LanraragiPreferences(connectionId: Long) {
    private val preferences = sourcePreferences("source_$connectionId")
    val address: String get() = preferences.getString("address", "").orEmpty()
    val internalAddress: String get() = preferences.getString("internal_address", "").orEmpty()
    val apiKey: String get() = preferences.getString(Preference.privateKey("api_key"), "").orEmpty()
    val indexedAddress: String get() = preferences.getString(Preference.appStateKey("indexed_address"), "").orEmpty()
    val defaultCategory: String get() = preferences.getString("default_category", "").orEmpty()
    val groupCollections: Boolean get() = preferences.getBoolean("group_collections", true)
    val archiveOpenMode: LanraragiArchiveOpenMode get() = LanraragiArchiveOpenMode.entries
        .firstOrNull { it.name == preferences.getString("archive_open_mode", "") }
        ?: LanraragiArchiveOpenMode.READER
    val rememberFilters: Boolean get() = preferences.getBoolean("remember_filters", false)
    fun savedFilter(): LanraragiFilter? = preferences.getString("saved_filter", null)
        ?.let { value ->
            runCatching { Json.decodeFromString<LanraragiFilterSnapshot>(value) }
                .getOrNull()
                ?.takeIf { it.version == FILTER_SNAPSHOT_VERSION }
                ?.filter
                ?: runCatching { Json.decodeFromString<LanraragiFilter>(value) }.getOrNull()
        }

    fun saveFilter(filter: LanraragiFilter, enabled: Boolean) {
        preferences.edit()
            .putBoolean("remember_filters", enabled)
            .apply {
                if (enabled) {
                    putString(
                        "saved_filter",
                        Json.encodeToString(
                            LanraragiFilterSnapshot(
                                filter = filter.copy(query = "", randomSeed = 0),
                            ),
                        ),
                    )
                } else {
                    remove("saved_filter")
                }
            }
            .apply()
    }
    fun markIndexed(address: String) {
        preferences.edit().putString(Preference.appStateKey("indexed_address"), address).apply()
    }
    fun save(
        address: String,
        apiKey: String,
        archiveOpenMode: LanraragiArchiveOpenMode = this.archiveOpenMode,
        defaultCategory: String = this.defaultCategory,
        groupCollections: Boolean = this.groupCollections,
        internalAddress: String = this.internalAddress,
    ) {
        check(
            preferences.edit().putString("address", address)
                .putString("internal_address", internalAddress)
                .putString("archive_open_mode", archiveOpenMode.name)
                .putString("default_category", defaultCategory)
                .putBoolean("group_collections", groupCollections)
                .putString(Preference.privateKey("api_key"), apiKey).commit(),
        )
    }

    @Serializable
    private data class LanraragiFilterSnapshot(
        val version: Int = FILTER_SNAPSHOT_VERSION,
        val filter: LanraragiFilter = LanraragiFilter(),
    )

    private companion object {
        const val FILTER_SNAPSHOT_VERSION = 1
    }
}
