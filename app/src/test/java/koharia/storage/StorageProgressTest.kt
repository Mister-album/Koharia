package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageProgressTest {
    @Test fun `path reuse cannot overwrite pending progress of a moved file`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("first.cbz", "original") }
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val identities = StorageResourceIdentities(backend, store)
        val progress = StorageProgress(backend, store, "device")
        val original = backend.stat("first.cbz")
        val originalKey = identities.identity(original)
        progress.record(originalKey, "v1", 100, 8, 10, locator = "original locator")
        StorageMutations(backend, store, identities).move(original, "second.cbz")
        backend.seed("first.cbz", "replacement")
        val replacementKey = identities.identity(backend.stat("first.cbz"))
        progress.record(replacementKey, "v1", 200, 1, 10, locator = "replacement locator")
        assertEquals(8, progress.cached(originalKey)?.page)
        assertEquals("original locator", progress.cached(originalKey)?.locator)
        assertEquals(1, progress.cached(replacementKey)?.page)
        assertEquals("replacement locator", progress.cached(replacementKey)?.locator)
        assertEquals(2, store.list("progress").size)
        assertTrue(store.list("progress").all { Json.decodeFromString<StoragePendingProgress>(it.payload).pending })

        progress.flush()
        val otherStore =
            StorageRecordStore(StorageSession(2, "account", "root") { true }, MemoryStorageRepository(), Json)
        val otherIdentities = StorageResourceIdentities(backend, otherStore)
        otherIdentities.refresh()
        val otherProgress = StorageProgress(backend, otherStore, "other-device")
        assertEquals(8, otherProgress.pull(otherIdentities.identity(backend.stat("second.cbz")), "v1")?.page)
        assertEquals(1, otherProgress.pull(otherIdentities.identity(backend.stat("first.cbz")), "v1")?.page)
    }

    @Test fun `compaction preserves other devices versions and newer timestamps`() = runBlocking {
        val backend = MemoryStorageBackend()
        fun progress(device: String) = StorageProgress(
            backend,
            StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json),
            device,
        )
        val first = progress("a")
        val second = progress("b")
        first.record("book", "v1", 100, 8)
        first.flush()
        second.record("book", "v1", 150, 6)
        second.flush()
        first.record("book", "v1", 200, 0)
        first.flush()
        assertEquals(2, backend.contents.size)
        first.record("book", "v1", 190, 3)
        first.flush()
        assertEquals(3, backend.contents.size)
        assertEquals(0, second.pull("book", "v1")?.page)
        first.record("book", "v2", 300, 1)
        first.flush()
        assertEquals(4, backend.contents.size)
        assertEquals(0, second.pull("book", "v1")?.page)
    }

    @Test fun `two offline devices converge after restart including rereading and unread`() = runBlocking {
        val backend = MemoryStorageBackend()
        fun store(connection: Long) = StorageRecordStore(
            StorageSession(connection, "account", "root") { true },
            MemoryStorageRepository(),
            Json,
        )
        val firstStore = store(1)
        val secondStore = store(2)
        var first = StorageProgress(backend, firstStore, "a")
        val second = StorageProgress(backend, secondStore, "b")
        first.record("book", "v1", 100, 80, 100, false)
        second.record("book", "v1", 200, 1, 100, false)
        first = StorageProgress(backend, firstStore, "a")
        first.flush()
        second.flush()
        assertEquals(1, first.pull("book", "v1")?.page)
        second.record("book", "v1", 300, 0, 100, false)
        second.flush()
        backend.seed(
            ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-broken.json",
            "broken record",
        )
        assertEquals(0, first.pull("book", "v1")?.page)
        assertEquals(false, first.cached("book")?.completed)
        assertEquals(false, firstStore.get<StoragePendingProgress>("progress", "book")?.pending)
    }

    @Test fun `newest event wins even when progress moves backwards`() {
        val old = StorageProgressRecord("book", "v1", "a", 1, 100, 90, 100, true)
        val reread = StorageProgressRecord("book", "v1", "b", 1, 101, 0, 100, false)
        assertEquals(reread, latestStorageProgress(listOf(old, reread), "book", "v1"))
    }

    @Test fun `shared root isolates accounts even with the same writer and sequence`() = runBlocking {
        val backend = MemoryStorageBackend()
        fun store(account: String) = StorageRecordStore(
            StorageSession(1, account, "root") { true },
            MemoryStorageRepository(),
            Json,
        )
        val firstStore = store("a")
        val secondStore = store("b")
        val entry = StorageEntry("book.cbz", false, 10, version = "v1")
        val firstKey = StorageResourceIdentities(backend, firstStore).identity(entry)
        val secondKey = StorageResourceIdentities(backend, secondStore).identity(entry)
        assertEquals(firstKey, secondKey)
        val first = StorageProgress(backend, firstStore, "device")
        val second = StorageProgress(backend, secondStore, "device")
        first.record(firstKey, "v1", 100, 8, 10)
        first.flush()
        assertNull(second.pull(secondKey, "v1"))
        second.record(secondKey, "v1", 200, 1, 10)
        second.flush()
        assertEquals(8, first.pull(firstKey, "v1")?.page)
        first.record(firstKey, "v1", 300, 9, 10)
        first.flush()
        assertEquals(1, second.pull(secondKey, "v1")?.page)
        assertEquals(2, backend.contents.size)
    }

    @Test fun `unscoped legacy progress is neither imported nor deleted`() = runBlocking {
        val backend = MemoryStorageBackend()
        val legacy = StorageProgressRecord("book", "v1", "device", 1, 100, 8, 10)
        val path = ".koharia/progress/${storageDigest("book")}-device-1.json"
        backend.seed(path, Json.encodeToString(legacy))
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "device")
        assertNull(progress.pull("book", "v1"))
        progress.record("book", "v1", 200, 1, 10)
        progress.flush()
        assertTrue(backend.contents.containsKey(path))
        assertEquals(1, progress.pull("book", "v1")?.page)
    }

    @Test fun `ties converge regardless of arrival order`() {
        val a = StorageProgressRecord("book", "v1", "a", 10, 100)
        val b = StorageProgressRecord("book", "v1", "b", 1, 100)
        assertEquals(b, latestStorageProgress(listOf(a, b), "book", "v1"))
        assertEquals(b, latestStorageProgress(listOf(b, a), "book", "v1"))
    }

    @Test fun `different content versions and unknown schemas cannot overwrite reading state`() {
        val incompatible = listOf(
            StorageProgressRecord("book", "old", "a", 1, 100),
            StorageProgressRecord("book", "v1", "a", 2, 101, schema = 2),
        )
        assertNull(latestStorageProgress(incompatible, "book", "v1"))
    }
}
