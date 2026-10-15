package koharia.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class StorageIdentityBinding(val path: String, val identity: String?)

@Serializable
private data class StorageIdentityManifest(
    val order: Long,
    val id: String,
    val bindings: List<StorageIdentityBinding>,
)

/** Immutable move manifests preserve identities across clients without concurrent manifest replacement. */
class StorageResourceIdentities(private val backend: LibraryStorageBackend, private val store: StorageRecordStore) {
    private val identityMutex = Mutex()
    private val resolved = object : LinkedHashMap<Pair<String, String?>, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String?>, String>) = size > 512
    }

    suspend fun identity(entry: StorageEntry): String = identityMutex.withLock {
        store.session.checkActive()
        val key = entry.path to entry.identity
        resolved[key]?.let { return@withLock it }
        val pathIdentity = store.get<String?>("identities", entry.path)
        val identity = cachedStorageResourceIdentity(
            store.session.rootIdentity,
            entry,
            pathIdentity,
            if (pathIdentity == null) entry.identity?.let { store.get<String>("native-identities", it) } else null,
        )
        entry.identity?.let { store.put("native-identities", it, identity) }
        resolved[key] = identity
        identity
    }

    suspend fun prepareMove(
        entry: StorageEntry,
        destination: String,
        descendants: List<StorageEntry> = emptyList(),
    ): List<StorageIdentityBinding> {
        val entries = mutableMapOf(entry.path to entry)
        descendants.forEach { entries[it.path] = it }
        if (entry.directory) {
            for (record in store.list("directories")) {
                val snapshot = store.json.decodeFromString<StorageDirectorySnapshot>(record.payload)
                (snapshot.children + snapshot.directory).filter { it.path.startsWith(entry.path + "/") }
                    .forEach { entries[it.path] = it }
            }
        }
        return entries.values.map { StorageIdentityBinding(it.path, null) } + entries.values.map {
            StorageIdentityBinding(destination + it.path.removePrefix(entry.path), identity(it))
        }
    }

    suspend fun commitMove(id: String, bindings: List<StorageIdentityBinding>) {
        if (bindings.isEmpty()) return
        ensureDirectory()
        val path = "$DIRECTORY/$id.json"
        val manifest = store.get<StorageIdentityManifest>("identity-events", id) ?: StorageIdentityManifest(
            maxOf(System.currentTimeMillis(), (store.get<Long>("identity-clock", "revision") ?: 0) + 1),
            id,
            bindings,
        ).also {
            store.put("identity-events", id, it)
            store.put("identity-clock", "revision", it.order)
        }
        val bytes = store.json.encodeToString(manifest).encodeToByteArray()
        require(bytes.size <= MAX_MANIFEST_SIZE)
        try {
            backend.write(path, bytes.inputStream(), bytes.size.toLong())
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.CONFLICT) throw error
            val existing = backend.stat(path)
            if (existing.size != bytes.size.toLong() ||
                !backend.read(existing, 0, bytes.size).contentEquals(bytes)
            ) {
                throw error
            }
        }
        manifest.bindings.forEach { applyBinding(manifest.id, it) }
    }

    suspend fun refresh() {
        val entries = try {
            backend.list(DIRECTORY)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
            return
        }
        val manifests = mutableListOf<StorageIdentityManifest>()
        for (entry in entries.filter { !it.directory && it.name.endsWith(".json") }) {
            if (entry.size !in 1..MAX_MANIFEST_SIZE) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            val manifest = store.json.decodeFromString<StorageIdentityManifest>(
                backend.read(entry, 0, entry.size.toInt()).decodeToString(),
            )
            require(entry.name == "${manifest.id}.json" && manifest.order >= 0)
            manifests += manifest
        }
        for (manifest in manifests.sortedWith(compareBy<StorageIdentityManifest> { it.order }.thenBy { it.id })) {
            manifest.bindings.forEach {
                require(StoragePath.normalize(it.path) == it.path && it.identity?.isBlank() != true)
                applyBinding(manifest.id, it)
            }
        }
        manifests.maxOfOrNull { it.order }?.let {
            store.put("identity-clock", "revision", maxOf(it, store.get<Long>("identity-clock", "revision") ?: 0))
        }
    }

    private suspend fun applyBinding(manifestId: String, binding: StorageIdentityBinding) = identityMutex.withLock {
        // Retired paths reserve a new generation, shared by every client replaying this move.
        val identity = binding.identity
            ?: "path:${storageDigest(store.session.rootIdentity + "\n" + binding.path + "\n" + manifestId)}"
        try {
            store.put("identities", binding.path, identity)
        } finally {
            resolved.keys.removeAll { it.first == binding.path }
        }
    }

    private suspend fun ensureDirectory() {
        try {
            if (backend.stat(DIRECTORY).directory) return
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
        }
        try {
            backend.createDirectory(DIRECTORY)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.CONFLICT || !backend.stat(DIRECTORY).directory) throw error
        }
    }

    companion object {
        private const val DIRECTORY = ".koharia/identities"
        private const val MAX_MANIFEST_SIZE = 4 * 1024 * 1024L
    }
}

internal fun cachedStorageResourceIdentity(
    root: String,
    entry: StorageEntry,
    pathIdentity: String?,
    nativeIdentity: String?,
): String = pathIdentity ?: nativeIdentity ?: "path:${storageDigest(root + "\n" + entry.path)}"
