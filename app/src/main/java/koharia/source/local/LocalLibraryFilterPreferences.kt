package koharia.source.local

import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class LocalLibraryFilterPreferences(sourceId: Long, parentUrl: String? = null) {
    private val preferences = sourcePreferences("source_$sourceId")
    private val scope = parentUrl?.let {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(it.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> "%02x".format(byte) }
    }?.let { "_$it" }.orEmpty()
    private val enabledKey = "local_remember_filters$scope"
    private val snapshotKey = "local_saved_filters$scope"

    val enabled: Boolean get() = preferences.getBoolean(enabledKey, false)

    fun read(): Snapshot? = preferences.getString(snapshotKey, null)
        ?.let { runCatching { Json.decodeFromString<Snapshot>(it) }.getOrNull() }
        ?.takeIf { it.version == FILTER_SNAPSHOT_VERSION }

    fun write(filters: LocalLibraryFilters, bookshelfId: String?, enabled: Boolean) {
        preferences.edit()
            .putBoolean(enabledKey, enabled)
            .apply {
                if (enabled) {
                    putString(
                        snapshotKey,
                        Json.encodeToString(Snapshot(filters = filters, bookshelfId = bookshelfId)),
                    )
                } else {
                    remove(snapshotKey)
                }
            }
            .apply()
    }

    @Serializable
    data class Snapshot(
        val version: Int = FILTER_SNAPSHOT_VERSION,
        val filters: LocalLibraryFilters = LocalLibraryFilters(),
        val bookshelfId: String? = null,
    )

    private companion object {
        const val FILTER_SNAPSHOT_VERSION = 1
    }
}
