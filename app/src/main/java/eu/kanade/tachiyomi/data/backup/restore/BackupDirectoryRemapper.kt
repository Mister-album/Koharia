package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryIndex
import koharia.source.local.LocalLibraryLayout
import koharia.source.local.rebindFolderLocations
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object BackupDirectoryRemapper {
    private const val LOCAL_CONFIG_KEY = "local_library_config"
    private val json = Json { ignoreUnknownKeys = true }

    fun remap(
        context: Context,
        preferences: List<BackupSourcePreferences>,
        bindings: Map<String, String>,
    ): List<BackupSourcePreferences> = preferences.map { source ->
        val remote = source.prefs.firstOrNull { it.key == "network_storage_configuration" }
            ?.let { (it.value as? StringPreferenceValue)?.value }
            ?.let { json.decodeFromString<koharia.storage.NetworkStorageConfiguration>(it) }
            ?.mode?.let { it != koharia.storage.LibraryStorageMode.LOCAL } == true
        fun bindRoot(uri: String, writable: Boolean): String {
            if (!remote) return bind(context, uri, bindings, writable)
            if (uri.isBlank()) return uri
            val parsed = android.net.Uri.parse(uri)
            require(parsed.scheme == "content" && parsed.authority?.endsWith(".library-storage") == true)
            return android.provider.DocumentsContract.buildDocumentUri(
                koharia.storage.NetworkStorageRuntime.authority(context),
                android.provider.DocumentsContract.getDocumentId(parsed),
            ).toString()
        }
        source.copy(
            prefs = source.prefs.filterNot { it.key == "local_folder_operation" }.map { preference ->
                if (preference.key == "local_library_index" && preference.value is StringPreferenceValue) {
                    val index = runCatching {
                        json.decodeFromString<LocalLibraryIndex>(preference.value.value)
                    }.getOrDefault(LocalLibraryIndex())
                    BackupPreference(
                        preference.key,
                        StringPreferenceValue(
                            json.encodeToString(
                                index.rebindFolderLocations(
                                    index.items.mapTo(mutableSetOf()) { it.rootId },
                                    markMissing = false,
                                ),
                            ),
                        ),
                    )
                } else if (preference.key != LOCAL_CONFIG_KEY || preference.value !is StringPreferenceValue) {
                    preference
                } else {
                    val config = json.decodeFromString<LocalLibraryConfig>(preference.value.value)
                    BackupPreference(
                        preference.key,
                        StringPreferenceValue(
                            json.encodeToString(
                                config.copy(
                                    roots = config.roots.map { root ->
                                        root.copy(treeUri = bindRoot(root.treeUri, root.managed))
                                    },
                                    treeUri = bindRoot(
                                        config.treeUri,
                                        config.layout == LocalLibraryLayout.KOHARIA,
                                    ),
                                    managedBaseTreeUri = bindRoot(config.managedBaseTreeUri, true),
                                ),
                            ),
                        ),
                    )
                }
            },
        )
    }

    private fun bind(context: Context, uri: String, bindings: Map<String, String>, requireWrite: Boolean): String {
        if (uri.isBlank()) return uri
        val selected = checkNotNull(bindings[uri]) { "Storage access must be granted before restore" }
        check(hasTreePermission(context, selected, requireWrite)) { "Storage access was revoked before restore" }
        return selected
    }

    fun hasTreePermission(context: Context, uri: String, requireWrite: Boolean): Boolean =
        RestoreDirectoryAccess.hasPersistedPermission(context, uri, requireWrite)
}
