package koharia.source.local

import android.content.SharedPreferences
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

enum class LocalLibraryContentType {
    COMICS,
    BOOKS,
    MIXED,
}

enum class LocalLibraryLayout {
    COMPATIBLE,
    KOHARIA,
}

enum class LocalLibraryOrganizationMode {
    SERIES,
    INDIVIDUAL_FILES,
}

enum class LocalMetadataStorage {
    DATABASE,
    ADJACENT_SIDECAR,
    UNIFIED_DIRECTORY,
}

@Serializable
data class LocalLibraryRootConfig(
    val id: String = "",
    val treeUri: String = "",
    val displayPath: String = "",
    val contentType: LocalLibraryContentType = LocalLibraryContentType.MIXED,
    val bookshelfId: String = "",
    val relativePath: String = "",
    val managed: Boolean = false,
)

@Serializable
data class LocalBookshelf(
    val id: String,
    val name: String,
    val contentType: LocalLibraryContentType,
    val organizationMode: LocalLibraryOrganizationMode = LocalLibraryOrganizationMode.SERIES,
)

data class ResolvedLocalLibraryRoot(
    val config: LocalLibraryRootConfig,
    val directory: UniFile,
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class LocalLibraryConfig(
    val roots: List<LocalLibraryRootConfig> = emptyList(),
    val bookshelves: List<LocalBookshelf> = emptyList(),
    val enabledContentTypes: Set<LocalLibraryContentType> = setOf(
        LocalLibraryContentType.COMICS,
        LocalLibraryContentType.BOOKS,
    ),
    val managedBaseTreeUri: String = "",
    val managedBaseDisplayPath: String = "",
    val metadataStorage: LocalMetadataStorage = LocalMetadataStorage.DATABASE,
    val libraryId: String = "",
    @EncodeDefault val setupCompleted: Boolean = false,
    val schemaVersion: Int = 7,
    // Version 1 fields are retained only so existing development builds can migrate in place.
    val treeUri: String = "",
    val displayPath: String = "",
    val contentType: LocalLibraryContentType = LocalLibraryContentType.MIXED,
    val layout: LocalLibraryLayout = LocalLibraryLayout.COMPATIBLE,
)

@Serializable
data class LocalLibraryItem(
    val itemKey: String,
    val rootId: String = "",
    val relativePath: String,
    val contentType: LocalLibraryContentType = LocalLibraryContentType.MIXED,
    val kind: Kind,
    val format: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val fingerprint: String? = null,
) {
    @Serializable
    enum class Kind {
        SERIES,
        CHAPTER,
        FILE_ENTRY,
    }
}

@Serializable
data class LocalLibraryIndex(
    val schemaVersion: Int = 5,
    val scannedAt: Long = 0L,
    val items: List<LocalLibraryItem> = emptyList(),
    val pendingChapterRefreshItemKeys: Set<String> = emptySet(),
)

@Serializable
data class LocalLibraryManifest(
    val libraryId: String,
    val schemaVersion: Int = 1,
    val contentType: LocalLibraryContentType = LocalLibraryContentType.MIXED,
    val capabilities: Set<String> = setOf("metadata", "read-only-scan"),
)

@Serializable
data class LocalMetadataOverride(
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Int? = null,
    val lockedFields: Set<String> = emptySet(),
    val source: String = "user",
    val updatedAt: Long = 0L,
)

class LocalLibraryPreferences(
    private val sourceId: Long,
    private val json: Json,
) {
    private val preferences: SharedPreferences = sourcePreferences("source_$sourceId")

    fun getConfig(): LocalLibraryConfig {
        val storedValue = preferences.getString(KEY_CONFIG, null)
        val stored = storedValue
            ?.let { runCatching { json.decodeFromString<LocalLibraryConfig>(it) }.getOrNull() }
            ?: LocalLibraryConfig()
        val migrated = stored.migrate(
            sourceId = sourceId,
            inferConfiguredSetup = storedValue != null &&
                !storedValue.contains("\"setupCompleted\"") &&
                stored.roots.isNotEmpty(),
        )
        if (migrated != stored || (storedValue != null && !storedValue.contains("\"setupCompleted\""))) {
            setConfig(migrated)
        }
        return migrated
    }

    fun setConfig(config: LocalLibraryConfig) {
        val lockedModes = preferences.getString(KEY_CONFIG, null)
            ?.let { runCatching { json.decodeFromString<LocalLibraryConfig>(it) }.getOrNull() }
            ?.takeIf { it.setupCompleted }
            ?.bookshelves
            .orEmpty()
            .associate { it.id to it.organizationMode }
        val modeSafeConfig = config.copy(
            bookshelves = config.bookshelves.map { shelf ->
                lockedModes[shelf.id]?.let { shelf.copy(organizationMode = it) } ?: shelf
            },
        )
        preferences.edit().putString(KEY_CONFIG, json.encodeToString(modeSafeConfig.migrate(sourceId))).apply()
    }

    fun configChanges(): Flow<LocalLibraryConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CONFIG) {
                trySend(getConfig())
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(getConfig())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun libraryStateChanges(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_CONFIG || key == KEY_BOOKSHELF_ASSIGNMENTS) trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getIndex(): LocalLibraryIndex {
        return preferences.getString(KEY_INDEX, null)
            ?.let { runCatching { json.decodeFromString<LocalLibraryIndex>(it) }.getOrNull() }
            ?: LocalLibraryIndex()
    }

    @Synchronized
    fun setIndex(index: LocalLibraryIndex) {
        preferences.edit().putString(KEY_INDEX, json.encodeToString(index)).apply()
    }

    @Synchronized
    fun clearPendingChapterRefresh(itemKey: String) {
        val index = getIndex()
        if (itemKey !in index.pendingChapterRefreshItemKeys) return
        setIndex(
            index.copy(
                pendingChapterRefreshItemKeys = index.pendingChapterRefreshItemKeys - itemKey,
            ),
        )
    }

    fun getMetadataOverrides(): Map<String, LocalMetadataOverride> {
        return preferences.getString(KEY_METADATA_OVERRIDES, null)
            ?.let {
                runCatching {
                    json.decodeFromString<Map<String, LocalMetadataOverride>>(it)
                }.getOrNull()
            }
            ?: emptyMap()
    }

    fun setMetadataOverride(itemKey: String, value: LocalMetadataOverride) {
        val values = getMetadataOverrides().toMutableMap()
        values[itemKey] = value
        preferences.edit().putString(KEY_METADATA_OVERRIDES, json.encodeToString(values)).apply()
    }

    fun getBookshelfAssignments(): Map<String, String> {
        return preferences.getString(KEY_BOOKSHELF_ASSIGNMENTS, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: emptyMap()
    }

    fun setBookshelfAssignment(itemKey: String, bookshelfId: String) {
        val assignments = getBookshelfAssignments().toMutableMap()
        assignments[itemKey] = bookshelfId
        preferences.edit().putString(KEY_BOOKSHELF_ASSIGNMENTS, json.encodeToString(assignments)).apply()
    }

    fun clearBookshelfAssignment(itemKey: String) {
        val assignments = getBookshelfAssignments().toMutableMap()
        if (assignments.remove(itemKey) == null) return
        preferences.edit().putString(KEY_BOOKSHELF_ASSIGNMENTS, json.encodeToString(assignments)).apply()
    }

    fun removeBookshelf(bookshelfId: String) {
        val config = getConfig()
        val assignments = getBookshelfAssignments()
        val removal = config.withoutBookshelf(bookshelfId, assignments) ?: return
        setConfig(removal.config)
        preferences.edit()
            .putString(KEY_BOOKSHELF_ASSIGNMENTS, json.encodeToString(removal.assignments))
            .apply()
    }

    fun rootDirectories(context: android.content.Context): List<ResolvedLocalLibraryRoot> {
        return getConfig().roots.mapNotNull { root ->
            resolveRoot(context, root)?.let { ResolvedLocalLibraryRoot(root, it) }
        }
    }

    fun resolveRoot(context: android.content.Context, root: LocalLibraryRootConfig): UniFile? {
        val tree = selectedTreeDirectory(context, root.treeUri) ?: return null
        return LocalLibraryLocator.normalize(root.relativePath)
            .split('/')
            .filter(String::isNotBlank)
            .fold(tree) { parent, segment -> parent.findFile(segment) ?: return null }
            .takeIf { it.isDirectory }
    }

    fun managedBaseDirectory(context: android.content.Context): UniFile? {
        val config = getConfig()
        return selectedTreeDirectory(context, config.managedBaseTreeUri)
    }

    fun metadataBaseDirectory(context: android.content.Context): UniFile? {
        return managedBaseDirectory(context)
            ?: getConfig().roots.firstNotNullOfOrNull { selectedTreeDirectory(context, it.treeUri) }
    }

    fun removeRoot(rootId: String) {
        val config = getConfig()
        val removed = config.roots.firstOrNull { it.id == rootId } ?: return
        val remaining = config.roots.filterNot { it.id == rootId }
        val keepManagedBase = config.managedBaseTreeUri != removed.treeUri ||
            remaining.any { it.treeUri == config.managedBaseTreeUri && it.managed }
        setConfig(
            config.copy(
                roots = remaining,
                managedBaseTreeUri = config.managedBaseTreeUri.takeIf { keepManagedBase }.orEmpty(),
                managedBaseDisplayPath = config.managedBaseDisplayPath.takeIf { keepManagedBase }.orEmpty(),
            ),
        )
        val removedKeys = getIndex().items.filter { it.rootId == rootId }.mapTo(mutableSetOf()) { it.itemKey }
        setIndex(getIndex().copy(items = getIndex().items.filterNot { it.rootId == rootId }))
        if (removedKeys.isNotEmpty()) {
            val overrides = getMetadataOverrides().filterKeys { it !in removedKeys }
            val assignments = getBookshelfAssignments().filterKeys { it !in removedKeys }
            preferences.edit()
                .putString(KEY_METADATA_OVERRIDES, json.encodeToString(overrides))
                .putString(KEY_BOOKSHELF_ASSIGNMENTS, json.encodeToString(assignments))
                .apply()
        }
    }

    private fun selectedTreeDirectory(context: android.content.Context, value: String): UniFile? {
        val uri = value.takeIf(String::isNotBlank)?.let(Uri::parse) ?: return null
        return UniFile.fromUri(context, uri)?.takeIf { it.isDirectory }
    }

    companion object {
        private const val KEY_CONFIG = "local_library_config"
        private const val KEY_INDEX = "local_library_index"
        private const val KEY_METADATA_OVERRIDES = "local_library_metadata_overrides"
        private const val KEY_BOOKSHELF_ASSIGNMENTS = "local_library_bookshelf_assignments"
    }
}

internal const val DEFAULT_COMICS_BOOKSHELF_ID = "default-comics"
internal const val DEFAULT_BOOKS_BOOKSHELF_ID = "default-books"

private fun builtInDefaultBookshelfId(contentType: LocalLibraryContentType): String = when (contentType) {
    LocalLibraryContentType.COMICS -> DEFAULT_COMICS_BOOKSHELF_ID
    LocalLibraryContentType.BOOKS -> DEFAULT_BOOKS_BOOKSHELF_ID
    LocalLibraryContentType.MIXED -> ""
}

internal fun LocalLibraryConfig.bookshelvesFor(contentType: LocalLibraryContentType): List<LocalBookshelf> {
    if (contentType == LocalLibraryContentType.MIXED || contentType !in enabledContentTypes) return emptyList()
    return bookshelves.filter { it.contentType == contentType }.ifEmpty {
        listOf(LocalBookshelf(builtInDefaultBookshelfId(contentType), "", contentType))
    }
}

internal fun LocalLibraryConfig.defaultBookshelfId(contentType: LocalLibraryContentType): String {
    return bookshelvesFor(contentType).firstOrNull()?.id.orEmpty()
}

internal fun LocalLibraryConfig.isDefaultBookshelf(bookshelfId: String): Boolean {
    val shelf = bookshelf(bookshelfId) ?: return false
    return this.defaultBookshelfId(shelf.contentType) == bookshelfId
}

internal fun LocalLibraryConfig.withDefaultBookshelf(
    contentType: LocalLibraryContentType,
    bookshelfId: String,
): LocalLibraryConfig {
    val typedShelves = bookshelvesFor(contentType)
    val selected = typedShelves.firstOrNull { it.id == bookshelfId } ?: return this
    val previousDefaultId = typedShelves.first().id
    val reordered = listOf(selected) + typedShelves.filterNot { it.id == bookshelfId }
    return copy(
        bookshelves = LocalLibraryContentType.entries
            .filterNot { it == LocalLibraryContentType.MIXED }
            .flatMap { type -> if (type == contentType) reordered else bookshelvesFor(type) },
        roots = roots.map { root ->
            if (root.contentType == contentType && root.bookshelfId.isBlank()) {
                root.copy(bookshelfId = previousDefaultId)
            } else {
                root
            }
        },
    )
}

internal fun LocalLibraryConfig.canRemoveBookshelf(
    bookshelfId: String,
    assignments: Map<String, String>,
): Boolean {
    return withoutBookshelf(bookshelfId, assignments) != null
}

internal fun LocalLibraryConfig.withoutBookshelf(
    bookshelfId: String,
    assignments: Map<String, String>,
): LocalBookshelfRemoval? {
    val shelf = bookshelf(bookshelfId) ?: return null
    val remaining = bookshelvesFor(shelf.contentType).filterNot { it.id == shelf.id }
    if (remaining.isEmpty()) return null
    val isReferenced = roots.any { it.bookshelfId == shelf.id } || assignments.values.any { it == shelf.id }
    val replacement = if (isReferenced) {
        remaining.firstOrNull { it.organizationMode == shelf.organizationMode }
    } else {
        remaining.first()
    } ?: return null
    return LocalBookshelfRemoval(
        config = copy(
            bookshelves = bookshelves.filterNot { it.id == bookshelfId },
            roots = roots.map { root ->
                if (root.bookshelfId == bookshelfId) root.copy(bookshelfId = replacement.id) else root
            },
        ),
        assignments = assignments.mapValues { (_, assignedShelfId) ->
            if (assignedShelfId == bookshelfId) replacement.id else assignedShelfId
        },
    )
}

internal data class LocalBookshelfRemoval(
    val config: LocalLibraryConfig,
    val assignments: Map<String, String>,
)

internal fun LocalLibraryConfig.effectiveBookshelfId(
    root: LocalLibraryRootConfig,
    itemKey: String,
    assignments: Map<String, String>,
    contentType: LocalLibraryContentType = root.contentType,
): String {
    val rootMode = organizationMode(root)
    val available = bookshelvesFor(contentType)
        .filter { it.organizationMode == rootMode }
        .mapTo(mutableSetOf()) { it.id }
    return assignments[itemKey]
        ?.takeIf { it in available }
        ?: root.bookshelfId.takeIf { root.contentType == contentType && it in available }
        ?: bookshelvesFor(contentType).firstOrNull { it.organizationMode == rootMode }?.id
        ?: this.defaultBookshelfId(contentType)
}

internal fun LocalLibraryConfig.bookshelf(id: String): LocalBookshelf? {
    return bookshelvesFor(LocalLibraryContentType.COMICS).firstOrNull { it.id == id }
        ?: bookshelvesFor(LocalLibraryContentType.BOOKS).firstOrNull { it.id == id }
}

internal fun LocalLibraryConfig.organizationMode(root: LocalLibraryRootConfig): LocalLibraryOrganizationMode {
    val shelfId = root.bookshelfId.ifBlank { this.defaultBookshelfId(root.contentType) }
    return bookshelf(shelfId)?.organizationMode ?: LocalLibraryOrganizationMode.SERIES
}

internal fun mergeLocalLibraryScanItems(
    scannedItems: List<LocalLibraryItem>,
    previousItems: List<LocalLibraryItem>,
    configuredRootIds: Set<String>,
    successfulRootIds: Set<String>,
): List<LocalLibraryItem> {
    val preservedItems = previousItems.filter { item ->
        item.rootId in configuredRootIds && item.rootId !in successfulRootIds
    }
    return (scannedItems + preservedItems).distinctBy(LocalLibraryItem::itemKey)
}

internal fun recoverLocalLibraryItems(
    sourceId: Long,
    config: LocalLibraryConfig,
    mangaUrls: Collection<String>,
): List<LocalLibraryItem> {
    val rootsById = config.roots.associateBy(LocalLibraryRootConfig::id)
    return mangaUrls.mapNotNull { url ->
        val location = LocalLibraryLocator.location(url, sourceId) ?: return@mapNotNull null
        val root = location.rootId?.let(rootsById::get) ?: return@mapNotNull null
        val kind = when (config.organizationMode(root)) {
            LocalLibraryOrganizationMode.SERIES -> LocalLibraryItem.Kind.SERIES
            LocalLibraryOrganizationMode.INDIVIDUAL_FILES -> LocalLibraryItem.Kind.FILE_ENTRY
        }
        LocalLibraryItem(
            itemKey = LocalLibraryLocator.itemKey(root.id, location.relativePath),
            rootId = root.id,
            relativePath = location.relativePath,
            contentType = root.contentType,
            kind = kind,
            format = if (kind == LocalLibraryItem.Kind.SERIES ||
                location.relativePath == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY
            ) {
                "directory"
            } else {
                location.relativePath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            },
            sizeBytes = 0L,
            modifiedAt = 0L,
        )
    }.distinctBy(LocalLibraryItem::itemKey)
}

object LocalLibraryLocator {
    private const val SCHEME_V1 = "koharia-local-v1"
    private const val SCHEME_V2 = "koharia-local-v2"
    const val ROOT_DIRECTORY_ENTRY = ".koharia/@root"

    data class Location(
        val rootId: String?,
        val relativePath: String,
    )

    fun seriesUrl(sourceId: Long, rootId: String, relativePath: String): String {
        return "$SCHEME_V2://$sourceId/${encodeSegment(rootId)}/${encodePath(relativePath)}"
    }

    fun entryUrl(sourceId: Long, rootId: String, relativePath: String): String {
        return "$SCHEME_V2://$sourceId/${encodeSegment(rootId)}/${encodePath(relativePath)}"
    }

    fun chapterUrl(sourceId: Long, rootId: String, relativePath: String): String {
        return "$SCHEME_V2://$sourceId/${encodeSegment(rootId)}/${encodePath(relativePath)}"
    }

    fun location(url: String, sourceId: Long): Location? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != sourceId.toString()) return null
        val segments = uri.rawPath.orEmpty().removePrefix("/").split('/').filter(String::isNotBlank)
        return when (uri.scheme) {
            SCHEME_V2 -> {
                if (segments.size < 2) return null
                Location(
                    rootId = decodeSegment(segments.first()).takeIf(String::isNotBlank),
                    relativePath = decodePath(segments.drop(1)).takeIf(String::isNotBlank) ?: return null,
                )
            }
            SCHEME_V1 -> Location(
                rootId = null,
                relativePath = decodePath(segments).takeIf(String::isNotBlank) ?: return null,
            )
            else -> null
        }
    }

    fun relativePath(url: String, sourceId: Long): String? = location(url, sourceId)?.relativePath

    private fun encodePath(path: String): String {
        return normalize(path).split('/').joinToString("/") {
            encodeSegment(it)
        }
    }

    private fun decodePath(segments: List<String>): String {
        return normalize(segments.joinToString("/") { decodeSegment(it) })
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun decodeSegment(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    fun normalize(path: String): String {
        return path.replace('\\', '/').trim('/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
    }

    fun itemKey(rootId: String, relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$rootId/${normalize(relativePath)}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun legacyItemKey(relativePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalize(relativePath).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

internal fun LocalLibraryConfig.migrate(
    sourceId: Long,
    inferConfiguredSetup: Boolean = false,
): LocalLibraryConfig {
    val normalizedBookshelves = bookshelves
        .filter {
            it.id.isNotBlank() &&
                (
                    it.name.isNotBlank() ||
                        it.id == DEFAULT_COMICS_BOOKSHELF_ID ||
                        it.id == DEFAULT_BOOKS_BOOKSHELF_ID
                    ) &&
                it.contentType != LocalLibraryContentType.MIXED &&
                when (it.id) {
                    DEFAULT_COMICS_BOOKSHELF_ID -> it.contentType == LocalLibraryContentType.COMICS
                    DEFAULT_BOOKS_BOOKSHELF_ID -> it.contentType == LocalLibraryContentType.BOOKS
                    else -> true
                }
        }
        .distinctBy(LocalBookshelf::id)
    val normalizedEnabledContentTypes = (
        enabledContentTypes + roots.mapNotNull { root ->
            root.contentType.takeIf { it != LocalLibraryContentType.MIXED }
        }
        )
        .filterTo(linkedSetOf()) { it != LocalLibraryContentType.MIXED }
        .ifEmpty {
            linkedSetOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)
        }
    val migratedBookshelves = buildList {
        normalizedEnabledContentTypes.forEach { type ->
            val typedShelves = normalizedBookshelves.filter { it.contentType == type }
            val legacyDefaultId = builtInDefaultBookshelfId(type)
            val orderedShelves = if (schemaVersion < 6) {
                val legacyDefault = typedShelves.firstOrNull { it.id == legacyDefaultId }
                    ?: LocalBookshelf(legacyDefaultId, "", type)
                listOf(legacyDefault) + typedShelves.filterNot { it.id == legacyDefault.id }
            } else {
                typedShelves.ifEmpty { listOf(LocalBookshelf(legacyDefaultId, "", type)) }
            }
            addAll(orderedShelves)
        }
    }
    if (roots.isNotEmpty()) {
        val normalizedRoots = roots.mapIndexed { index, root ->
            val availableBookshelves = migratedBookshelves.filter { it.contentType == root.contentType }
            val availableBookshelfIds = availableBookshelves.mapTo(mutableSetOf()) { it.id }
            val fallbackBookshelfId = availableBookshelves.firstOrNull()?.id.orEmpty()
            root.copy(
                id = root.id.ifBlank {
                    stableRootId(sourceId, root.treeUri, root.relativePath, root.contentType, index)
                },
                bookshelfId = root.bookshelfId.takeIf { it in availableBookshelfIds }
                    ?: fallbackBookshelfId,
                relativePath = LocalLibraryLocator.normalize(root.relativePath),
            )
        }
        return copy(
            roots = normalizedRoots,
            setupCompleted = setupCompleted || schemaVersion < 3 || inferConfiguredSetup,
            bookshelves = migratedBookshelves,
            enabledContentTypes = normalizedEnabledContentTypes,
            schemaVersion = 7,
            treeUri = "",
            displayPath = "",
            contentType = LocalLibraryContentType.MIXED,
            layout = LocalLibraryLayout.COMPATIBLE,
        )
    }
    if (treeUri.isBlank()) {
        return copy(
            bookshelves = migratedBookshelves,
            enabledContentTypes = normalizedEnabledContentTypes,
            schemaVersion = 7,
        )
    }

    val migratedRoots = if (layout == LocalLibraryLayout.KOHARIA) {
        listOf(
            legacyRoot(sourceId, treeUri, displayPath, LocalLibraryContentType.COMICS, "Comics", managed = true),
            legacyRoot(sourceId, treeUri, displayPath, LocalLibraryContentType.BOOKS, "Books", managed = true),
        )
    } else {
        listOf(legacyRoot(sourceId, treeUri, displayPath, contentType, "", managed = false))
    }
    return copy(
        roots = migratedRoots,
        managedBaseTreeUri = treeUri.takeIf { layout == LocalLibraryLayout.KOHARIA }.orEmpty(),
        managedBaseDisplayPath = displayPath.takeIf { layout == LocalLibraryLayout.KOHARIA }.orEmpty(),
        setupCompleted = true,
        bookshelves = migratedBookshelves,
        enabledContentTypes = normalizedEnabledContentTypes,
        schemaVersion = 7,
        treeUri = "",
        displayPath = "",
        contentType = LocalLibraryContentType.MIXED,
        layout = LocalLibraryLayout.COMPATIBLE,
    )
}

private fun legacyRoot(
    sourceId: Long,
    treeUri: String,
    displayPath: String,
    contentType: LocalLibraryContentType,
    relativePath: String,
    managed: Boolean,
): LocalLibraryRootConfig {
    return LocalLibraryRootConfig(
        id = stableRootId(sourceId, treeUri, relativePath, contentType, 0),
        treeUri = treeUri,
        displayPath = displayPath,
        contentType = contentType,
        relativePath = relativePath,
        managed = managed,
    )
}

private fun stableRootId(
    sourceId: Long,
    treeUri: String,
    relativePath: String,
    contentType: LocalLibraryContentType,
    index: Int,
): String {
    val value = "$sourceId|$treeUri|${LocalLibraryLocator.normalize(relativePath)}|$contentType|$index"
    return UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()
}
