package koharia.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class StorageDirectorySnapshot(val directory: StorageEntry, val children: List<StorageEntry>, val generation: Long)

@Serializable
private data class StorageScanCheckpoint(val completed: Set<String> = emptySet())

/** Persist complete directory generations; never interpret a failed listing as a deletion. */
class StorageSnapshot(private val backend: LibraryStorageBackend, private val store: StorageRecordStore) {
    private val mutex = Mutex()
    private val scanMutex = Mutex()

    suspend fun hasPendingScan(): Boolean = store.list("scans").isNotEmpty()
    suspend fun directory(path: String, refresh: Boolean = false): StorageDirectorySnapshot {
        val normalized = StoragePath.normalize(path)
        return mutex.withLock {
            val saved = store.get<StorageDirectorySnapshot>("directories", normalized)
            if (saved != null && !refresh) return@withLock saved
            val info = backend.stat(normalized)
            if (!info.directory) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            val entries = backend.list(normalized)
            require(entries.all { StoragePath.parent(it.path) == normalized && it.path != normalized })
            val result = StorageDirectorySnapshot(info, entries, System.currentTimeMillis())
            store.put("directories", normalized, result)
            result
        }
    }

    suspend fun cachedEntry(path: String): StorageEntry? {
        val normalized = StoragePath.normalize(path)
        if (normalized.isEmpty()) return store.get<StorageDirectorySnapshot>("directories", "")?.directory
        val own = store.get<StorageDirectorySnapshot>("directories", normalized)
        val parent = store.get<StorageDirectorySnapshot>("directories", StoragePath.parent(normalized))
        return if (own != null && (parent == null || own.generation > parent.generation)) {
            own.directory
        } else {
            parent?.children?.firstOrNull { it.path == normalized }
        }
    }

    suspend fun scan(
        path: String,
        refresh: Boolean = false,
        onDirectory: suspend (StorageDirectorySnapshot) -> Unit = {
        },
    ) = scanMutex.withLock {
        val root = StoragePath.normalize(path)
        var checkpoint = if (refresh) store.get<StorageScanCheckpoint>("scans", root) else null
        if (refresh && checkpoint == null) {
            checkpoint = StorageScanCheckpoint()
            store.put("scans", root, checkpoint)
        }
        val pending = ArrayDeque<String>()
        pending.add(root)
        val seen = hashSetOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            val directory = directory(next, refresh && next !in checkpoint?.completed.orEmpty())
            if (refresh) {
                checkpoint = StorageScanCheckpoint(checkpoint?.completed.orEmpty() + next)
                store.put("scans", root, checkpoint)
            }
            onDirectory(directory)
            if (next.count { it == '/' } > 128) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            directory.children.filter {
                it.directory && it.path !in setOf(".koharia/progress", ".koharia/identities")
            }.forEach { pending.add(it.path) }
        }
        if (refresh) {
            store.list("scans").firstOrNull { it.key == root }?.let { store.remove("scans", root, it.revision) }
        }
    }
}
