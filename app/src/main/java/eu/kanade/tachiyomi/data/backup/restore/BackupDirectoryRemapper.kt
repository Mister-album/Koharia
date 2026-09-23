package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryLayout
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
        source.copy(
            prefs = source.prefs.map { preference ->
                if (preference.key != LOCAL_CONFIG_KEY || preference.value !is StringPreferenceValue) {
                    preference
                } else {
                    val config = json.decodeFromString<LocalLibraryConfig>(preference.value.value)
                    BackupPreference(
                        preference.key,
                        StringPreferenceValue(
                            json.encodeToString(
                                config.copy(
                                    roots = config.roots.map { root ->
                                        root.copy(treeUri = bind(context, root.treeUri, bindings, root.managed))
                                    },
                                    treeUri = bind(
                                        context,
                                        config.treeUri,
                                        bindings,
                                        config.layout == LocalLibraryLayout.KOHARIA,
                                    ),
                                    managedBaseTreeUri = bind(context, config.managedBaseTreeUri, bindings, true),
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
