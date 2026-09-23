package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.RestoreDirectoryAccess
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryLayout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class RestoreDirectoryRequirement(
    val originalUri: String,
    val displayPath: String,
    val forAppSettings: Boolean,
    val forConnectionSettings: Boolean,
    val writeForAppSettings: Boolean,
    val writeForConnectionSettings: Boolean,
) {
    fun isRequired(options: RestoreOptions): Boolean =
        (forAppSettings && options.appSettings) || (forConnectionSettings && options.connectionSettings)

    fun requiresWrite(options: RestoreOptions): Boolean =
        (writeForAppSettings && options.appSettings) || (writeForConnectionSettings && options.connectionSettings)
}

internal object DirectoryRestorePlan {
    private const val LOCAL_LIBRARY_CONFIG_KEY = "local_library_config"
    private val json = Json { ignoreUnknownKeys = true }

    fun requirements(backup: Backup): List<RestoreDirectoryRequirement> {
        val requirements = linkedMapOf<String, RestoreDirectoryRequirement>()

        fun add(
            uri: String,
            displayPath: String,
            requireWrite: Boolean,
            forAppSettings: Boolean = false,
            forConnectionSettings: Boolean = true,
        ) {
            if (uri.isBlank()) return
            val previous = requirements[uri]
            requirements[uri] = RestoreDirectoryRequirement(
                originalUri = uri,
                displayPath = displayPath.ifBlank { previous?.displayPath.orEmpty() },
                forAppSettings = forAppSettings || previous?.forAppSettings == true,
                forConnectionSettings = forConnectionSettings || previous?.forConnectionSettings == true,
                writeForAppSettings = (requireWrite && forAppSettings) || previous?.writeForAppSettings == true,
                writeForConnectionSettings = (requireWrite && forConnectionSettings) ||
                    previous?.writeForConnectionSettings == true,
            )
        }

        add(
            backup.backupStorageDirectory,
            displayPath = "",
            requireWrite = true,
            forAppSettings = true,
            forConnectionSettings = false,
        )

        backup.backupSourcePreferences.asSequence()
            .filter { source ->
                source.sourceKey.startsWith("source_") &&
                    source.sourceKey.removePrefix("source_").toLongOrNull() != null
            }
            .flatMap { it.prefs.asSequence() }
            .filter { it.key == LOCAL_LIBRARY_CONFIG_KEY }
            .forEach { preference ->
                val encoded = (preference.value as? StringPreferenceValue)?.value
                    ?: error("Invalid local library configuration in backup")
                val config = json.decodeFromString<LocalLibraryConfig>(encoded)

                config.roots.forEach { root ->
                    add(root.treeUri, root.displayPath, root.managed)
                }
                add(config.managedBaseTreeUri, config.managedBaseDisplayPath, requireWrite = true)

                // Version 1 configurations stored their sole directory outside roots.
                add(
                    config.treeUri,
                    config.displayPath,
                    requireWrite = config.layout == LocalLibraryLayout.KOHARIA,
                )
            }
        return requirements.values.toList()
    }

    fun hasPersistedPermission(context: Context, uri: String, requireWrite: Boolean): Boolean {
        return RestoreDirectoryAccess.hasPersistedPermission(context, uri, requireWrite)
    }

    fun existingBindings(
        context: Context,
        requirements: List<RestoreDirectoryRequirement>,
        options: RestoreOptions,
    ): Map<String, String> {
        return requirements.mapNotNull { requirement ->
            if (!requirement.isRequired(options)) return@mapNotNull null
            RestoreDirectoryAccess.resolveGrantedUri(
                context,
                requirement.originalUri,
                requirement.requiresWrite(options),
            )?.let { requirement.originalUri to it }
        }.toMap()
    }

    fun persistSelection(context: Context, uri: Uri, requireWrite: Boolean): Boolean {
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val writeFlag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val flags = if (requireWrite) {
            readFlag or writeFlag
        } else {
            readFlag
        }
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            hasPersistedPermission(context, uri.toString(), requireWrite)
        }.getOrDefault(false)
    }
}
