package koharia.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class StorageTreeCopyEntry(val entry: StorageEntry, val hash: String? = null)

@Serializable
data class StorageMutation(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val source: StorageEntry? = null,
    val destination: String = "",
    val phase: String = "prepared",
    val contentHash: String? = null,
    val contentSize: Long? = null,
    val sourceHash: String? = null,
    val identities: List<StorageIdentityBinding> = emptyList(),
    val tree: List<StorageTreeCopyEntry> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Journal before requests; leave ambiguous outcomes pending for reconciliation, never blind replay. */
class StorageMutations(
    private val backend: LibraryStorageBackend,
    private val records: StorageRecordStore,
    private val identities: StorageResourceIdentities? = null,
    private val scratchDirectory: File? = null,
) {
    private val mutex = Mutex()
    private suspend fun record(
        operation: StorageMutation,
    ) = records.put("mutations", operation.id, operation, operation.updatedAt)
    private suspend fun complete(
        operation: StorageMutation,
    ) = records.remove("mutations", operation.id, operation.updatedAt)

    suspend fun createDirectory(path: String) = mutex.withLock {
        val operation = StorageMutation(kind = "mkdir", destination = StoragePath.normalize(path))
        record(operation)
        backend.createDirectory(operation.destination)
        complete(operation)
    }
    suspend fun move(entry: StorageEntry, destination: String) = mutex.withLock {
        require(entry.path.isNotEmpty() && StoragePath.normalize(destination).isNotEmpty())
        require(destination != entry.path && !destination.startsWith(entry.path + "/"))
        val capturedTree = if (entry.directory) captureTree(entry) else emptyList()
        var operation = StorageMutation(
            kind = "move",
            source = entry,
            destination = StoragePath.normalize(destination),
            sourceHash = if (entry.directory) null else checksum(entry),
            identities = identities?.prepareMove(entry, destination, capturedTree.map { it.entry }).orEmpty(),
            tree = capturedTree,
        )
        record(operation)
        try {
            backend.move(entry, operation.destination)
        } catch (error: StorageFailure) {
            if (error.reason != StorageFailure.Reason.UNSUPPORTED || scratchDirectory == null) {
                throw error
            }
            if (statOrNull(operation.destination) != null) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            if (entry.directory) {
                val tree = capturedTree
                operation = operation.copy(
                    phase = "tree-prepared",
                    tree = tree,
                    identities = identities?.prepareMove(entry, destination, tree.map { it.entry }).orEmpty(),
                )
                record(operation)
                backend.createDirectory(operation.destination)
                operation = operation.copy(phase = "tree-copying")
                record(operation)
                recoverTreeMove(operation)
                return@withLock
            }
            check(scratchDirectory.mkdirs() || scratchDirectory.isDirectory)
            if (scratchDirectory.usableSpace < entry.size + 16L * 1024 * 1024) {
                throw StorageFailure(StorageFailure.Reason.SPACE)
            }
            val temporary = File.createTempFile("storage-move-", ".part", scratchDirectory)
            try {
                temporary.outputStream().use { backend.copyTo(entry, it) }
                if (temporary.length() != entry.size) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                operation = operation.copy(phase = "copying")
                record(operation)
                temporary.inputStream().use { backend.write(operation.destination, it, entry.size) }
                if (checksum(backend.stat(operation.destination)) != operation.sourceHash) {
                    throw StorageFailure(StorageFailure.Reason.CONFLICT)
                }
                operation = operation.copy(phase = "copied")
                record(operation)
                backend.delete(entry)
            } finally {
                temporary.delete()
            }
        }
        operation = operation.copy(phase = "moved")
        record(operation)
        identities?.commitMove(operation.id, operation.identities)
        complete(operation)
    }
    suspend fun delete(entry: StorageEntry) = mutex.withLock {
        if (entry.directory &&
            backend.list(entry.path).isNotEmpty()
        ) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
        val operation = StorageMutation(kind = "delete", source = entry)
        record(operation)
        backend.delete(entry)
        complete(operation)
    }
    suspend fun write(path: String, file: File, expected: StorageEntry?) = mutex.withLock {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        var operation = StorageMutation(
            kind = "write",
            source = expected,
            destination = StoragePath.normalize(path),
            contentHash = hash.digest().joinToString("") { "%02x".format(it) },
            contentSize = file.length(),
            sourceHash = expected?.let { checksum(it) },
        )
        record(operation)
        val parent = StoragePath.parent(path)
        val temporary = StoragePath.child(parent, ".koharia-write-${operation.id}.tmp")
        val backup = StoragePath.child(parent, ".koharia-write-${operation.id}.bak")
        file.inputStream().use { backend.write(temporary, it, file.length()) }
        verifyContent(backend.stat(temporary), operation)
        operation = operation.copy(phase = "uploaded")
        record(operation)
        if (expected != null) {
            if (backend.stat(path).version != expected.version) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            backend.move(expected, backup)
            operation = operation.copy(phase = "backed-up")
            record(operation)
        }
        backend.move(backend.stat(temporary), path)
        verifyContent(backend.stat(path), operation)
        operation = operation.copy(phase = "installed")
        record(operation)
        if (expected != null) backend.delete(backend.stat(backup))
        complete(operation)
    }

    suspend fun reconcile(): List<StorageMutation> = mutex.withLock {
        val unresolved = mutableListOf<StorageMutation>()
        for (record in records.list("mutations")) {
            val operation = runCatching { records.json.decodeFromString<StorageMutation>(record.payload) }
                .onFailure { logcat(LogPriority.WARN) { "Unreadable storage mutation record stays unresolved" } }
                .getOrNull() ?: continue
            val recovered = try {
                reconcile(operation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: StorageFailure) {
                // One stuck operation must not block the recovery of every other one.
                logcat(LogPriority.WARN, error) { "Storage operation ${operation.id} remains unresolved" }
                false
            }
            if (!recovered) unresolved += operation
        }
        unresolved
    }

    /** Returns true when the operation is provably finished, false while it stays pending. */
    private suspend fun reconcile(operation: StorageMutation): Boolean {
        if (operation.kind == "move" && operation.phase in setOf("tree-copying", "tree-copied")) {
            recoverTreeMove(operation)
            return true
        }
        if (operation.kind == "write") {
            return recoverWrite(operation)
        }
        val source = operation.source?.let { statOrNull(it.path) }
        val destination = operation.destination.takeIf(String::isNotBlank)?.let { statOrNull(it) }
        if (operation.kind == "move" && source == null && destination?.directory == true &&
            operation.tree.isNotEmpty()
        ) {
            try {
                verifyTree(operation, operation.destination, allowMissing = false)
                identities?.commitMove(operation.id, operation.identities)
                complete(operation)
            } catch (error: StorageFailure) {
                if (error.reason != StorageFailure.Reason.CONFLICT) throw error
                return false
            }
            return true
        }
        if (operation.kind == "move" && operation.phase in setOf("copying", "copied") &&
            source != null && destination != null && !destination.directory &&
            source.version == operation.source?.version && operation.sourceHash != null &&
            checksum(destination) == operation.sourceHash && checksum(source) == operation.sourceHash
        ) {
            backend.delete(source)
            identities?.commitMove(operation.id, operation.identities)
            complete(operation)
            return true
        }
        val done = when (operation.kind) {
            "delete" -> source == null
            "move" ->
                source == null && destination != null && (
                    operation.phase == "moved" ||
                        (operation.source?.identity != null && destination.identity == operation.source.identity) ||
                        (
                            !destination.directory && operation.sourceHash != null &&
                                checksum(destination) == operation.sourceHash
                            )
                    )
            // Existence alone does not prove that this operation created or wrote a file.
            else -> false
        }
        if (!done) return false
        if (operation.kind == "move") identities?.commitMove(operation.id, operation.identities)
        complete(operation)
        return true
    }

    private suspend fun captureTree(root: StorageEntry): List<StorageTreeCopyEntry> {
        val result = mutableListOf(StorageTreeCopyEntry(root))
        val pending = ArrayDeque<StorageEntry>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            if (directory.path.count { it == '/' } - root.path.count { it == '/' } > 128) {
                throw StorageFailure(StorageFailure.Reason.PROTOCOL)
            }
            for (child in backend.list(directory.path)) {
                require(StoragePath.parent(child.path) == directory.path && child.path != directory.path)
                result += StorageTreeCopyEntry(child, if (child.directory) null else checksum(child))
                if (child.directory) pending.add(child)
            }
        }
        return result
    }

    /** Copy and validate the complete tree before deleting even its first source file. */
    private suspend fun recoverTreeMove(operation: StorageMutation) {
        val source = checkNotNull(operation.source)
        fun destination(entry: StorageEntry) = operation.destination + entry.path.removePrefix(source.path)
        checkNotNull(scratchDirectory)
        check(scratchDirectory.mkdirs() || scratchDirectory.isDirectory)
        if (operation.phase == "tree-copying") {
            for (item in operation.tree) {
                val entry = item.entry
                val path = destination(entry)
                val existing = statOrNull(path)
                if (entry.directory) {
                    if (existing == null) {
                        backend.createDirectory(path)
                    } else if (!existing.directory) {
                        throw StorageFailure(StorageFailure.Reason.CONFLICT)
                    }
                } else if (existing == null) {
                    if (scratchDirectory.usableSpace < entry.size + 16L * 1024 * 1024) {
                        throw StorageFailure(StorageFailure.Reason.SPACE)
                    }
                    val file = File.createTempFile("storage-tree-", ".part", scratchDirectory)
                    try {
                        file.outputStream().use { backend.copyTo(entry, it) }
                        if (file.length() != entry.size) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
                        file.inputStream().use { backend.write(path, it, entry.size) }
                    } finally {
                        file.delete()
                    }
                }
                if (!entry.directory && checksum(backend.stat(path)) != item.hash) {
                    throw StorageFailure(StorageFailure.Reason.CONFLICT)
                }
            }
            // Check both manifests before crossing the destructive phase boundary.
            verifyTree(operation, source.path, allowMissing = false)
            verifyTree(operation, operation.destination, allowMissing = false)
            record(operation.copy(phase = "tree-copied"))
        }
        verifyTree(operation, operation.destination, allowMissing = false)
        verifyTree(operation, source.path, allowMissing = true)
        for (item in operation.tree.filterNot { it.entry.directory }) {
            statOrNull(item.entry.path)?.let { current ->
                if (current.version != item.entry.version || checksum(current) != item.hash) {
                    throw StorageFailure(StorageFailure.Reason.CONFLICT)
                }
                backend.delete(current)
            }
        }
        for (item in operation.tree.filter { it.entry.directory }.sortedByDescending { it.entry.path.length }) {
            statOrNull(item.entry.path)?.let { current ->
                if (!current.directory || backend.list(current.path).isNotEmpty()) {
                    throw StorageFailure(StorageFailure.Reason.CONFLICT)
                }
                backend.delete(current)
            }
        }
        identities?.commitMove(operation.id, operation.identities)
        complete(operation)
    }

    private suspend fun verifyTree(operation: StorageMutation, root: String, allowMissing: Boolean) {
        val originalRoot = checkNotNull(operation.source).path
        val expected = operation.tree.associateBy { root + it.entry.path.removePrefix(originalRoot) }
        for ((path, item) in expected) {
            val current = statOrNull(path)
            if (current == null && allowMissing) continue
            if (current == null || current.directory != item.entry.directory) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
            if (current.directory) {
                if (backend.list(path).any {
                        it.path !in expected
                    }
                ) {
                    throw StorageFailure(StorageFailure.Reason.CONFLICT)
                }
            } else if (checksum(current) != item.hash) {
                throw StorageFailure(StorageFailure.Reason.CONFLICT)
            }
        }
    }

    private suspend fun checksum(entry: StorageEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        backend.copyTo(
            entry,
            object : OutputStream() {
                override fun write(value: Int) {
                    digest.update(value.toByte())
                    count++
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    digest.update(bytes, offset, length)
                    count += length
                }
            },
        )
        if (count != entry.size) throw StorageFailure(StorageFailure.Reason.PROTOCOL)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun verifyContent(entry: StorageEntry, operation: StorageMutation) {
        if (entry.directory || entry.size != operation.contentSize || checksum(entry) != operation.contentHash) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
    }

    /** Re-observe every step: a request may have committed even if its response never arrived. */
    private suspend fun recoverWrite(operation: StorageMutation): Boolean {
        val parent = StoragePath.parent(operation.destination)
        val temporary = StoragePath.child(parent, ".koharia-write-${operation.id}.tmp")
        val backup = StoragePath.child(parent, ".koharia-write-${operation.id}.bak")
        val staged = statOrNull(temporary)
        var saved = statOrNull(backup)
        var target = statOrNull(operation.destination)
        if (target != null && target.size == operation.contentSize && checksum(target) == operation.contentHash) {
            if (saved != null) {
                if (operation.sourceHash == null || checksum(saved) != operation.sourceHash) return false
                backend.delete(saved)
            }
            if (staged != null) {
                verifyContent(staged, operation)
                backend.delete(staged)
            }
            complete(operation)
            return true
        }
        if (staged == null) return false
        verifyContent(staged, operation)
        if (saved != null) {
            if (target != null || operation.sourceHash == null || checksum(saved) != operation.sourceHash) return false
        } else if (operation.source != null) {
            if (target == null || target.version != operation.source.version ||
                operation.sourceHash == null || checksum(target) != operation.sourceHash
            ) {
                return false
            }
            backend.move(target, backup)
            saved = backend.stat(backup)
            record(operation.copy(phase = "backed-up"))
            target = null
        }
        if (target != null) return false
        backend.move(staged, operation.destination)
        verifyContent(backend.stat(operation.destination), operation)
        record(operation.copy(phase = "installed"))
        if (saved != null) backend.delete(saved)
        complete(operation)
        return true
    }
    private suspend fun statOrNull(
        path: String,
    ): StorageEntry? = try {
        backend.stat(path)
    } catch (error: StorageFailure) {
        if (error.reason != StorageFailure.Reason.NOT_FOUND) throw error
        null
    }
}
