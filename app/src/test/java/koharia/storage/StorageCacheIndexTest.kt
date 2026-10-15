package koharia.storage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class StorageCacheIndexTest {
    @TempDir lateinit var temporary: Path

    @Test fun `large roots reserve incrementally and share canonical accounting`() {
        for (count in listOf(512, 4096, 16384)) {
            val root = File(temporary.toFile(), "$count").apply { mkdirs() }
            repeat(count) { File(root, "$it.block").writeBytes(byteArrayOf(1)) }
            val index = StorageCacheIndex.get(root)
            assertSame(index, StorageCacheIndex.get(File(root, ".")))
            index.access { assertEquals(count.toLong(), measuredBytes()) }
            repeat(128) { number ->
                val file = File(root, "new-$number.block")
                index.access {
                    reserve(file, 1, count.toLong())
                    file.writeBytes(byteArrayOf(2))
                    release(file)
                    assertEquals(count.toLong(), measuredBytes())
                    assertEquals(0L, reservedBytes())
                }
            }
            assertEquals(1, index.scans, "root size $count")
            assertEquals(count.toLong(), root.walkTopDown().filter { it.extension == "block" }.sumOf(File::length))
        }
    }

    @Test fun `external deletion and smaller budget reconcile without directory scan`() {
        val root = temporary.toFile()
        val files = (0..3).map {
            File(root, "$it.block").apply {
                writeBytes(ByteArray(8))
                setLastModified(it + 1L)
            }
        }
        val index = StorageCacheIndex.get(root)
        index.access { assertEquals(32L, measuredBytes()) }
        assertTrue(files.first().delete())
        val next = File(root, "next.block")
        index.access {
            reserve(next, 8, 16)
            next.writeBytes(ByteArray(8))
            release(next)
            assertEquals(16L, measuredBytes())
        }
        assertEquals(1, index.scans)
        assertEquals(16L, root.walkTopDown().filter { it.extension == "block" }.sumOf(File::length))
        val interrupted = File(root, "complete.part").apply { writeBytes(ByteArray(9)) }
        index.invalidate()
        index.access { assertEquals(16L, measuredBytes()) }
        assertFalse(interrupted.exists())
        assertEquals(2, index.scans)
    }

    @Test fun `concurrent cache instances share network fetch and block accounting`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "cached") }
        val reads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
                reads.incrementAndGet()
                started.complete(Unit)
                release.await()
                return memory.read(entry, offset, length)
            }
        }
        val entry = memory.stat("book")
        val caches = List(2) { StorageBlockCache(temporary.toFile(), session(), backend) { 6 } }
        coroutineScope {
            val first = async { caches[0].read(entry, 0, 6) }
            started.await()
            val second = async { caches[1].read(entry, 0, 6) }
            release.complete(Unit)
            listOf(first, second).awaitAll().forEach { assertArrayEquals("cached".encodeToByteArray(), it) }
        }
        assertEquals(1, reads.get())
        StorageCacheIndex.get(temporary.toFile()).access {
            assertEquals(6L, measuredBytes())
            assertEquals(0L, reservedBytes())
        }
    }

    @Test fun `complete transfer reserves shared budget and releases after failure`() = runBlocking {
        val memory = MemoryStorageBackend().apply {
            seed("book", "12345678")
            seed("other", "abcdefgh")
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                output.write(byteArrayOf(1, 2))
                started.complete(Unit)
                release.await()
                throw StorageFailure(StorageFailure.Reason.NETWORK)
            }
        }
        val first = StorageBlockCache(temporary.toFile(), session(), backend) { 8 }
        val second = StorageBlockCache(temporary.toFile(), session().copy(connectionId = 2), memory) { 8 }
        coroutineScope {
            val transfer = async { runCatching { first.prepareComplete(memory.stat("book")) } }
            started.await()
            try {
                StorageCacheIndex.get(temporary.toFile()).access { assertEquals(8L, reservedBytes()) }
                StorageCacheIndex.get(temporary.toFile()).invalidate()
                StorageCacheIndex.get(temporary.toFile()).access { assertEquals(8L, reservedBytes()) }
                assertEquals(1, temporary.toFile().walkTopDown().count { it.name == "complete.part" })
                val error = assertThrows(StorageFailure::class.java) {
                    runBlocking { second.read(memory.stat("other"), 0, 1) }
                }
                assertEquals(StorageFailure.Reason.SPACE, error.reason)
            } finally {
                release.complete(Unit)
            }
            assertTrue(transfer.await().exceptionOrNull() is StorageFailure)
        }
        assertArrayEquals(byteArrayOf('a'.code.toByte()), second.read(memory.stat("other"), 0, 1))
        StorageCacheIndex.get(temporary.toFile()).access {
            assertEquals(8L, measuredBytes())
            assertEquals(0L, reservedBytes())
        }
        assertFalse(temporary.toFile().walkTopDown().any { it.extension == "part" })
    }

    @Test fun `cancelled complete transfer releases its lease and reservation`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "12345678") }
        val started = CompletableDeferred<Unit>()
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                output.write(1)
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val cache = StorageBlockCache(temporary.toFile(), session(), backend) { 8 }
        val job = launch { cache.prepareComplete(memory.stat("book")) }
        started.await()
        job.cancelAndJoin()
        StorageCacheIndex.get(temporary.toFile()).access {
            assertEquals(0L, reservedBytes())
            assertEquals(0L, measuredBytes())
        }
        assertFalse(temporary.toFile().walkTopDown().any { it.extension == "part" })
        val fresh = StorageBlockCache(temporary.toFile(), session(), memory) { 8 }
        assertEquals("12345678", fresh.prepareComplete(memory.stat("book")).readText())
    }

    @Test fun `single byte complete writes cannot exceed reserved size`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "12") }
        var written = 0
        val backend = object : LibraryStorageBackend by memory {
            override suspend fun copyTo(entry: StorageEntry, output: OutputStream) {
                repeat(100) {
                    output.write(1)
                    written++
                }
            }
        }
        val cache = StorageBlockCache(temporary.toFile(), session(), backend) { 2 }
        val error = assertThrows(StorageFailure::class.java) {
            runBlocking { cache.prepareComplete(memory.stat("book")) }
        }
        assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        assertEquals(2, written)
        StorageCacheIndex.get(temporary.toFile()).access {
            assertEquals(0L, reservedBytes())
            assertEquals(0L, measuredBytes())
        }
    }

    @Test fun `interrupted complete promotion does not block ranged recovery at full budget`() = runBlocking {
        val memory = MemoryStorageBackend().apply { seed("book", "abcdefgh") }
        val cache = StorageBlockCache(temporary.toFile(), session(), memory) { 8 }
        val entry = memory.stat("book")
        val complete = cache.prepareComplete(entry)
        assertTrue(File(complete.parentFile, "complete.ok").delete())
        assertArrayEquals("abcdefgh".encodeToByteArray(), cache.read(entry, 0, 8))
        assertFalse(complete.exists())
        StorageCacheIndex.get(temporary.toFile()).access {
            assertEquals(8L, measuredBytes())
            assertEquals(0L, reservedBytes())
        }
    }

    @Test fun `externally deleted leased complete file cannot hold the budget hostage`() = runBlocking {
        val memory = MemoryStorageBackend().apply {
            seed("book", "abcdefgh")
            seed("other", "12345678")
        }
        val cache = StorageBlockCache(temporary.toFile(), session(), memory) { 8 }
        val entry = memory.stat("book")
        val complete = cache.prepareComplete(entry)
        cache.acquire(entry).use {
            assertTrue(complete.delete())
            assertArrayEquals("12345678".encodeToByteArray(), cache.read(memory.stat("other"), 0, 8))
        }
        StorageCacheIndex.get(temporary.toFile()).access { assertEquals(8L, measuredBytes()) }
    }

    private fun session() = StorageSession(1, "account", "root") { true }
}
