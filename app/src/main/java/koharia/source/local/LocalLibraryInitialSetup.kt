package koharia.source.local

import android.content.Context
import android.net.Uri
import koharia.connection.LibraryConnectionProfile
import kotlinx.serialization.json.Json
import java.util.UUID

internal fun shouldCreateInitialLocalDirectories(
    sourceId: Long,
    config: LocalLibraryConfig,
    profiles: List<LibraryConnectionProfile>,
): Boolean = !config.setupCompleted && config.roots.isEmpty() && profiles.none {
    it.id != sourceId && it.providerId == LocalFolderConnectionProvider.ID
}

internal fun LocalLibraryConfig.hasSelectedDefaultModes(selectedShelfIds: Collection<String>): Boolean {
    if (setupCompleted) return true
    val types = enabledContentTypes.filter { it != LocalLibraryContentType.MIXED }
    return types.isNotEmpty() && types.all {
        defaultBookshelfId(it).let { id -> id.isNotBlank() && id in selectedShelfIds }
    }
}

internal fun LocalLibraryConfig.enabledLibraryConfiguration(): LocalLibraryConfig {
    val types = enabledContentTypes.filterTo(linkedSetOf()) { it != LocalLibraryContentType.MIXED }
    require(types.isNotEmpty())
    return copy(
        enabledContentTypes = types,
        bookshelves = types.flatMap(::bookshelvesFor),
        roots = roots.filter { it.contentType in types },
    )
}

internal fun prepareInitialLocalDirectories(
    context: Context,
    config: LocalLibraryConfig,
    dataDirectory: String,
    displayPath: String,
    json: Json,
): LocalLibraryConfig {
    if (config.setupCompleted) return config
    val missingTypes = config.missingInitialDirectoryTypes()
    if (missingTypes.isEmpty()) return config
    require(dataDirectory.isNotBlank()) { "App data directory is not configured" }
    val layout = checkNotNull(
        createManagedLayout(
            context,
            Uri.parse(dataDirectory),
            displayPath,
            config.libraryId.ifBlank { UUID.randomUUID().toString() },
            missingTypes,
            json,
        ),
    ) { "Unable to create local library directories in app data directory" }
    return config.copy(
        roots = config.roots + layout.roots.map { root ->
            root.copy(bookshelfId = config.defaultBookshelfId(root.contentType).also { require(it.isNotBlank()) })
        },
        managedBaseTreeUri = dataDirectory,
        managedBaseDisplayPath = displayPath,
        libraryId = layout.libraryId,
    )
}

internal fun LocalLibraryConfig.missingInitialDirectoryTypes(): Set<LocalLibraryContentType> =
    if (setupCompleted) {
        emptySet()
    } else {
        enabledContentTypes.filterTo(linkedSetOf()) { type ->
            type != LocalLibraryContentType.MIXED && roots.none { it.contentType == type }
        }
    }

internal suspend fun prepareInitialNetworkDirectories(
    backend: koharia.storage.LibraryStorageBackend,
    config: LocalLibraryConfig,
): LocalLibraryConfig {
    val roots = config.missingInitialDirectoryTypes().map { type ->
        val name = if (type == LocalLibraryContentType.COMICS) "Comics" else "Books"
        val existing = try {
            backend.stat(name)
        } catch (error: koharia.storage.StorageFailure) {
            if (error.reason != koharia.storage.StorageFailure.Reason.NOT_FOUND) throw error
            null
        }
        if (existing != null) {
            if (!existing.directory) {
                throw koharia.storage.StorageFailure(
                    koharia.storage.StorageFailure.Reason.CONFLICT,
                )
            }
        } else {
            createStorageDirectory(backend, "", name)
        }
        LocalLibraryRootConfig(
            id = UUID.randomUUID().toString(),
            displayPath = name,
            contentType = type,
            bookshelfId = config.defaultBookshelfId(type).also { require(it.isNotBlank()) },
            relativePath = name,
            managed = true,
        )
    }
    return config.copy(roots = config.roots + roots)
}
