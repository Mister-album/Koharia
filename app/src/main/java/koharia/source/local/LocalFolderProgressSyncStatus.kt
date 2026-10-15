package koharia.source.local

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import koharia.connection.ConnectionIdentityChanges
import koharia.connection.ConnectionProgressSyncStatus
import koharia.connection.ProgressStatuses
import koharia.connection.withProgressStatusOwner
import koharia.domain.storage.LibraryStorageRepository
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.storage.StoragePath
import koharia.storage.StorageRecordStore
import koharia.storage.StorageSession
import koharia.storage.observeStorageProgressSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Resolve persisted locators without constructing UniFile/network runtimes or touching SAF grants. */
internal fun observeLocalFolderProgressSyncStatus(
    context: Context,
    sourceId: Long,
    urls: List<String>,
    config: LocalLibraryConfig,
    index: LocalLibraryIndex,
    checkOwner: () -> Unit,
): Flow<ProgressStatuses> {
    val networkRoots = config.roots.filter { NetworkStorageRuntime.isRemote(context, Uri.parse(it.treeUri)) }
    if (networkRoots.isEmpty()) return flowOf(emptyMap())
    val preferences = NetworkStoragePreferences(sourceId)
    val network = preferences.configuration
    val account = preferences.account
    val username = preferences.username
    val password = preferences.password
    val session = StorageSession(sourceId, account, network.rootIdentity) {
        checkOwner()
        preferences.configuration == network && preferences.account == account &&
            preferences.username == username && preferences.password == password
    }
    session.checkActive()
    val paths = mutableMapOf<String, String>()
    val unavailable = mutableMapOf<String, Set<ConnectionProgressSyncStatus>>()
    for (url in urls.distinct()) {
        try {
            val location = requireNotNull(LocalLibraryLocator.location(url, sourceId))
            // Legacy rootless URLs cannot silently select a different root when multiple roots match.
            val candidates = config.roots.filter { location.rootId == null || it.id == location.rootId }
            require(candidates.size == 1)
            val root = candidates.single()
            if (root !in networkRoots) continue
            require(network.mode != LibraryStorageMode.LOCAL && network.rootIdentity.isNotBlank())
            val document = DocumentsContract.getDocumentId(Uri.parse(root.treeUri)).split('|', limit = 3)
            require(document.size == 3 && document[0].toLong() == sourceId && document[1] == account)
            val relative = if (location.rootId == null && root.relativePath.isNotBlank()) {
                location.relativePath.removePrefix("${root.relativePath}/")
            } else {
                location.relativePath
            }
            val item = index.itemsByLocator[root.id to relative]
                ?: index.itemsByKey[LocalLibraryLocator.itemKey(root.id, relative)]
            require(item?.missing != true && (!relative.startsWith(".koharia/nodes/") || item != null))
            val physical = (item?.physicalPath() ?: relative)
                .takeUnless { it == LocalLibraryLocator.ROOT_DIRECTORY_ENTRY }.orEmpty()
            paths[url] = StoragePath.normalize(
                listOf(document[2], root.relativePath, physical).filter(String::isNotBlank).joinToString("/"),
            )
        } catch (_: IllegalArgumentException) {
            unavailable[url] = setOf(ConnectionProgressSyncStatus.UNVERIFIED_OWNER)
        }
    }
    val store = StorageRecordStore(session, Injekt.get<LibraryStorageRepository>(), Injekt.get<Json>())
    return observeStorageProgressSyncStatus(store, paths, network.persistentIdentity, network.needsValidation)
        .map { it + unavailable }
        .withProgressStatusOwner(sourceId, Injekt.get<ConnectionIdentityChanges>().pausedIds, session::checkActive)
}
