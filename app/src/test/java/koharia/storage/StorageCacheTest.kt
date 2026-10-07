package koharia.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class StorageCacheTest {
    @TempDir lateinit var temporary: Path

    @Test fun `open ranged file can exceed cache budget and reread evicted blocks`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("book", "x".repeat(StorageBlockCache.BLOCK_SIZE * 3)) }
        val entry = backend.stat("book")
        val budget = StorageBlockCache.BLOCK_SIZE.toLong() * 2
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { budget }
        cache.acquire(entry).use {
            coroutineScope {
                (0..20).map { index ->
                    async {
                        assertArrayEquals(
                            byteArrayOf('x'.code.toByte()),
                            cache.read(entry, (index % 3).toLong() * StorageBlockCache.BLOCK_SIZE, 1),
                        )
                    }
                }.awaitAll()
            }
            assertArrayEquals(byteArrayOf('x'.code.toByte()), cache.read(entry, 0, 1))
            org.junit.jupiter.api.Assertions.assertTrue(
                temporary.toFile().walkTopDown().filter { it.extension == "block" }.sumOf(File::length) <= budget,
            )
        }
    }

    @Test fun `complete file remains protected while its reader holds a lease`() = runBlocking {
        val backend = MemoryStorageBackend().apply {
            seed("book", "x".repeat(StorageBlockCache.BLOCK_SIZE * 2))
            seed("other", "y".repeat(StorageBlockCache.BLOCK_SIZE))
        }
        val entry = backend.stat("book")
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { entry.size }
        val complete = cache.prepareComplete(entry)
        cache.acquire(entry).use {
            val error =
                assertThrows(StorageFailure::class.java) { runBlocking { cache.read(backend.stat("other"), 0, 1) } }
            assertEquals(StorageFailure.Reason.SPACE, error.reason)
            org.junit.jupiter.api.Assertions.assertTrue(complete.isFile)
            assertArrayEquals(byteArrayOf('x'.code.toByte()), cache.read(entry, 0, 1))
        }
        assertArrayEquals(byteArrayOf('y'.code.toByte()), cache.read(backend.stat("other"), 0, 1))
    }

    @Test fun `concurrent ranges share a block and corrupt blocks are refetched`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "x".repeat(400000)) }
        val reads = AtomicInteger()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
                reads.incrementAndGet()
                return memory.read(entry, offset, length)
            }
        }
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { true }, backend) { 1024 * 1024 }
        val entry = backend.stat("book")
        coroutineScope { (1..12).map { async { cache.read(entry, 100, 32) } }.awaitAll() }
        assertEquals(1, reads.get())
        val block = temporary.toFile().walkTopDown().single { it.extension == "block" }
        block.writeBytes(ByteArray(block.length().toInt()))
        assertArrayEquals(ByteArray(32) { 'x'.code.toByte() }, cache.read(entry, 100, 32))
        assertEquals(2, reads.get())
    }

    @Test fun `full fallback validates content after corruption and respects budget`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "z".repeat(400000)) }
        val copies = AtomicInteger()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray =
                throw StorageFailure(StorageFailure.Reason.UNSUPPORTED)
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                copies.incrementAndGet()
                memory.copyTo(entry, output)
            }
        }
        val session = StorageSession(1, "a", "r") { true }
        val cache = StorageBlockCache(temporary.toFile(), session, backend) { 1024 * 1024 }
        val entry = backend.stat("book")
        assertArrayEquals(ByteArray(32) { 'z'.code.toByte() }, cache.read(entry, 100, 32))
        val complete = temporary.toFile().walkTopDown().single { it.name == "complete.bin" }
        complete.writeBytes(ByteArray(400000))
        complete.setLastModified(1)
        assertArrayEquals(ByteArray(32) { 'z'.code.toByte() }, cache.read(entry, 100, 32))
        assertEquals(2, copies.get())
        val limited = StorageBlockCache(File(temporary.toFile(), "small"), session, backend) { 100 }
        val error = assertThrows(StorageFailure::class.java) { runBlocking { limited.prepareComplete(entry) } }
        assertEquals(StorageFailure.Reason.SPACE, error.reason)
        assertEquals(2, copies.get())
    }

    @Test fun `obsolete session cannot serve or populate cache`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("book", "data") }
        val cache = StorageBlockCache(temporary.toFile(), StorageSession(1, "a", "r") { false }, backend) { 1024 }
        assertThrows(CancellationException::class.java) { runBlocking { cache.read(backend.stat("book"), 0, 4) } }
        assertEquals(0, temporary.toFile().walkTopDown().count { it.isFile })
    }

    @Test fun `stable resource identity reuses cached bytes after a move`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("old", "cached") }
        val cache = StorageBlockCache(
            temporary.toFile(),
            StorageSession(1, "a", "r") { true },
            backend,
            resourceIdentity = { "stable-resource" },
        ) { 1024 }
        val original = backend.stat("old")
        assertArrayEquals("cached".encodeToByteArray(), cache.read(original, 0, 6))
        backend.move(original, "new")
        val moved = backend.stat("new")
        backend.failReads = true
        assertArrayEquals("cached".encodeToByteArray(), cache.read(moved, 0, 6))
        assertEquals(1, temporary.toFile().walkTopDown().count { it.extension == "block" })
    }

    @Test fun `reused paths with matching versions cannot serve a moved files cached bytes`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("first.cbz", "oldone") }
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun stat(path: String) = memory.stat(path).copy(version = "same-version")
            override suspend fun list(path: String) = memory.list(path).map { it.copy(version = "same-version") }
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int) =
                memory.read(memory.stat(entry.path), offset, length)
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) =
                memory.copyTo(memory.stat(entry.path), output)
            override suspend fun move(entry: StorageEntry, destination: String) =
                memory.move(memory.stat(entry.path), destination)
        }
        val session = StorageSession(1, "account", "root") { true }
        val records = StorageRecordStore(session, MemoryStorageRepository(), Json)
        val identities = StorageResourceIdentities(backend, records)
        val cache = StorageBlockCache(
            temporary.toFile(),
            session,
            backend,
            resourceIdentity = { runBlocking { identities.identity(it) } },
        ) { 1024 }
        val original = backend.stat("first.cbz")
        assertArrayEquals("oldone".encodeToByteArray(), cache.read(original, 0, 6))
        StorageMutations(backend, records, identities).move(original, "second.cbz")
        memory.seed("first.cbz", "newone")
        val replacement = backend.stat("first.cbz")
        val moved = backend.stat("second.cbz")
        assertArrayEquals("newone".encodeToByteArray(), cache.read(replacement, 0, 6))
        memory.failReads = true
        assertArrayEquals("oldone".encodeToByteArray(), cache.read(moved, 0, 6))
        assertArrayEquals("newone".encodeToByteArray(), cache.read(replacement, 0, 6))
    }
}
