package koharia.storage

import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.nio.file.Path

class StorageRecoveryTest {
    @TempDir lateinit var temporary: Path

    @Test fun `scanned nested root resolves without parent cache but newer parent deletion wins`() = runBlocking {
        val backend = MemoryStorageBackend().apply { directories += "Comics" }
        val records = store()
        val snapshot = StorageSnapshot(backend, records)
        snapshot.scan("Comics", refresh = true)
        assertTrue(snapshot.cachedEntry("Comics")?.directory == true)
        backend.failReads = true
        assertTrue(StorageSnapshot(backend, records).cachedEntry("Comics")?.directory == true)
        records.put("directories", "", StorageDirectorySnapshot(StorageEntry("", true), emptyList(), Long.MAX_VALUE))
        assertEquals(null, snapshot.cachedEntry("Comics"))
    }

    @Test fun `native directory move without native identifiers recovers a lost response`() = runBlocking {
        val memory = MemoryStorageBackend().apply {
            directories += "source"
            seed("source/book", "content")
        }
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun move(entry: StorageEntry, destination: String) {
                memory.directories.remove(entry.path)
                memory.directories.add(destination)
                memory.contents[destination + "/book"] = memory.contents.remove(entry.path + "/book")!!
                throw StorageFailure(StorageFailure.Reason.NETWORK)
            }
        }
        val records = store()
        val journal = StorageMutations(backend, records)
        assertThrows(StorageFailure::class.java) { runBlocking { journal.move(backend.stat("source"), "target") } }
        assertTrue(journal.reconcile().isEmpty())
        assertEquals("content", memory.contents.getValue("target/book").decodeToString())
    }

    @Test fun `lost mutation responses recover without losing the original or repeating a write`() = runBlocking {
        for (failedRequest in 1..4) {
            val backend = MemoryStorageBackend()
            backend.seed("metadata.json", "original")
            val store = store()
            val journal = StorageMutations(backend, store)
            val replacement = File(temporary.toFile(), "replacement").apply { writeText("replacement") }
            backend.failAfterMutation = failedRequest
            assertThrows(StorageFailure::class.java) {
                runBlocking { journal.write("metadata.json", replacement, backend.stat("metadata.json")) }
            }
            assertTrue(store.list("mutations").isNotEmpty())
            backend.failAfterMutation = null
            assertTrue(StorageMutations(backend, store).reconcile().isEmpty())
            assertEquals("replacement", backend.contents.getValue("metadata.json").decodeToString())
            assertEquals(setOf("metadata.json"), backend.contents.keys)
            assertEquals(1, backend.uploads)
        }
    }

    @Test fun `recovery preserves a conflicting target and original backup`() = runBlocking {
        val backend = MemoryStorageBackend()
        backend.seed("metadata.json", "original")
        val store = store()
        val replacement = File(temporary.toFile(), "replacement").apply { writeText("replacement") }
        backend.failAfterMutation = 2
        assertThrows(StorageFailure::class.java) {
            runBlocking {
                StorageMutations(backend, store).write("metadata.json", replacement, backend.stat("metadata.json"))
            }
        }
        backend.failAfterMutation = null
        backend.seed("metadata.json", "external edit")
        assertEquals(1, StorageMutations(backend, store).reconcile().size)
        assertEquals("external edit", backend.contents.getValue("metadata.json").decodeToString())
        assertTrue(backend.contents.any { it.key.endsWith(".bak") && it.value.decodeToString() == "original" })
    }

    @Test fun `warm empty snapshots avoid network and survive failed refresh`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = store()
        val snapshot = StorageSnapshot(backend, store)
        val initial = snapshot.directory("")
        assertTrue(initial.children.isEmpty())
        backend.failReads = true
        assertEquals(initial, StorageSnapshot(backend, store).directory(""))
        assertThrows(StorageFailure::class.java) { runBlocking { snapshot.directory("", true) } }
        assertEquals(initial, snapshot.directory(""))
    }

    @Test fun `existing identity directory does not need mkdir permission`() = runBlocking {
        val backend = MemoryStorageBackend()
        backend.directories += ".koharia"
        val identity = StorageIdentity.ensure(backend)
        assertEquals(identity, StorageIdentity.read(backend))
        assertFalse(backend.createdDirectories.contains(".koharia"))
    }

    @Test fun `interrupted scan resumes without requesting completed directories`() = runBlocking {
        val memory = MemoryStorageBackend()
        memory.directories += "books"
        memory.seed("books/chapter.cbz", "book")
        var fail = true
        val requests = mutableListOf<String>()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun list(path: String): List<StorageEntry> {
                requests += path
                if (path.isEmpty()) return listOf(memory.stat("books"))
                if (fail) throw StorageFailure(StorageFailure.Reason.NETWORK)
                return memory.list(path)
            }
        }
        val records = store()
        assertThrows(StorageFailure::class.java) {
            runBlocking { StorageSnapshot(backend, records).scan("", refresh = true) }
        }
        assertTrue(StorageSnapshot(backend, records).hasPendingScan())
        fail = false
        val restarted = StorageSnapshot(backend, records)
        restarted.scan("", refresh = true)
        assertEquals(listOf("", "books", "books"), requests)
        assertFalse(restarted.hasPendingScan())
        restarted.scan("")
        assertEquals(3, requests.size)
        restarted.scan("", refresh = true)
        assertEquals(listOf("", "books"), requests.takeLast(2))
    }

    @Test fun `copy fallback recovers a lost response before deleting the source`() = runBlocking {
        val memory = MemoryStorageBackend().apply {
            seed("source", "original")
            failAfterMutation = 1
        }
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun move(entry: StorageEntry, destination: String): Unit =
                throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
        }
        val records = store()
        val journal = StorageMutations(backend, records, scratchDirectory = temporary.toFile())
        assertThrows(StorageFailure::class.java) { runBlocking { journal.move(backend.stat("source"), "destination") } }
        assertEquals(setOf("source", "destination"), memory.contents.keys)
        memory.failAfterMutation = null
        assertTrue(journal.reconcile().isEmpty())
        assertEquals(setOf("destination"), memory.contents.keys)
        assertEquals("original", memory.contents.getValue("destination").decodeToString())
    }

    @Test fun `directory copy fallback survives lost responses and preserves all content`() = runBlocking {
        for (failure in 2..8) {
            val memory = MemoryStorageBackend().apply {
                directories += setOf("source", "source/sub")
                seed("source/book.cbz", "book")
                seed("source/sub/info.json", "metadata")
                failAfterMutation = failure
            }
            val backend = object : LibraryStorageBackend by memory {
                override suspend fun move(entry: StorageEntry, destination: String): Unit =
                    throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            }
            val records = store()
            val journal = StorageMutations(backend, records, scratchDirectory = temporary.toFile())
            assertThrows(StorageFailure::class.java) { runBlocking { journal.move(backend.stat("source"), "target") } }
            memory.failAfterMutation = null
            assertTrue(StorageMutations(backend, records, scratchDirectory = temporary.toFile()).reconcile().isEmpty())
            assertEquals(setOf("target/book.cbz", "target/sub/info.json"), memory.contents.keys)
            assertEquals("book", memory.contents.getValue("target/book.cbz").decodeToString())
            assertEquals("metadata", memory.contents.getValue("target/sub/info.json").decodeToString())
            assertEquals(setOf("", "target", "target/sub"), memory.directories)
        }
    }

    private fun store() = StorageRecordStore(
        StorageSession(1, "account", "root") {
            true
        },
        MemoryStorageRepository(),
        Json,
    )
}

internal class MemoryStorageRepository : LibraryStorageRepository {
    private val values = mutableMapOf<List<Any>, StorageRecord>()
    private fun key(connection: Long, account: String, root: String, namespace: String, key: String) =
        listOf(connection, account, root, namespace, key)
    override suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String) =
        values[key(connection, account, root, namespace, key)]?.payload
    override suspend fun list(connection: Long, account: String, root: String, namespace: String) =
        values.filterKeys { it.take(4) == listOf(connection, account, root, namespace) }.values.toList()
    override suspend fun put(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        record: StorageRecord,
        checkActive: () -> Unit,
    ) {
        checkActive()
        values[key(connection, account, root, namespace, record.key)] = record
    }
    override suspend fun remove(
        connection: Long,
        account: String,
        root: String,
        namespace: String,
        key: String,
        revision: Long,
        checkActive: () -> Unit,
    ) {
        checkActive()
        val identity = key(connection, account, root, namespace, key)
        if (values[identity]?.revision == revision) values.remove(identity)
    }
    override suspend fun removeConnection(connection: Long) {
        values.keys.removeAll { it.first() == connection }
    }
}

internal class MemoryStorageBackend : LibraryStorageBackend {
    val contents = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf("")
    val createdDirectories = mutableSetOf<String>()
    private val versions = mutableMapOf<String, Long>()
    private var revision = 0L
    var failAfterMutation: Int? = null
    private var mutations = 0
    var uploads = 0
    var failReads = false
    override val capabilities = StorageCapabilities(true, true, true, true)
    fun seed(path: String, content: String) {
        contents[path] = content.encodeToByteArray()
        versions[path] = ++revision
    }
    private fun mutated() {
        mutations++
        if (mutations == failAfterMutation) throw StorageFailure(StorageFailure.Reason.NETWORK)
    }
    override suspend fun stat(path: String): StorageEntry {
        if (failReads) throw StorageFailure(StorageFailure.Reason.NETWORK)
        if (path in directories) return StorageEntry(path, true)
        val bytes = contents[path] ?: throw StorageFailure(StorageFailure.Reason.NOT_FOUND)
        return StorageEntry(path, false, bytes.size.toLong(), version = versions[path].toString())
    }
    override suspend fun list(path: String): List<StorageEntry> =
        (contents.keys + directories).filter { it != path && StoragePath.parent(it) == path }.map { stat(it) }
    override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
        if (stat(entry.path).version != entry.version) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        return contents.getValue(entry.path).copyOfRange(offset.toInt(), offset.toInt() + length)
    }
    override suspend fun createDirectory(path: String) {
        if (!directories.add(path)) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        createdDirectories += path
        mutated()
    }
    override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
        if (path in contents && expectedVersion == null) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        contents[path] = data.readBytes().also { require(it.size.toLong() == length) }
        versions[path] = ++revision
        uploads++
        mutated()
    }
    override suspend fun move(entry: StorageEntry, destination: String) {
        if (destination in contents || stat(entry.path).version != entry.version) {
            throw StorageFailure(StorageFailure.Reason.CONFLICT)
        }
        contents[destination] = contents.remove(entry.path)!!
        versions[destination] = versions.remove(entry.path)!!
        mutated()
    }
    override suspend fun delete(entry: StorageEntry) {
        if (stat(entry.path).version != entry.version) throw StorageFailure(StorageFailure.Reason.CONFLICT)
        contents.remove(entry.path)
        if (entry.directory) {
            if (list(entry.path).isNotEmpty()) throw StorageFailure(StorageFailure.Reason.CONFLICT)
            directories.remove(entry.path)
        }
        versions.remove(entry.path)
        mutated()
    }
    override fun close() = Unit
}
