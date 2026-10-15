package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class StorageBackupTest {
    @Test fun `backup preserves distinct moved and replacement identities while offline`() = runBlocking {
        val backend = MemoryStorageBackend().apply { seed("first.cbz", "original") }
        val repository = MemoryStorageRepository()
        val source = StorageRecordStore(StorageSession(1, "account", "root") { true }, repository, Json)
        val identities = StorageResourceIdentities(backend, source)
        val original = backend.stat("first.cbz")
        val originalKey = identities.identity(original)
        StorageMutations(backend, source, identities).move(original, "second.cbz")
        backend.seed("first.cbz", "replacement")
        val replacement = backend.stat("first.cbz")
        val moved = backend.stat("second.cbz")
        val replacementKey = identities.identity(replacement)
        val payload = StorageBackup(repository, Json).create(1, "account", "root")
        val restored = MemoryStorageRepository()
        StorageBackup(restored, Json).restore(2, "root", payload)
        val target = StorageRecordStore(StorageSession(2, "account", "root") { true }, restored, Json)
        val restoredIdentities = StorageResourceIdentities(backend, target)
        backend.failReads = true
        assertNotEquals(originalKey, replacementKey)
        assertEquals(originalKey, restoredIdentities.identity(moved))
        assertEquals(replacementKey, restoredIdentities.identity(replacement))
    }

    @Test fun `pending progress survives restore without copying device sequence or cache`() = runBlocking {
        val original = MemoryStorageRepository()
        val source = StorageRecordStore(StorageSession(1, "account", "root") { true }, original, Json)
        val event = StorageProgressRecord("book", "v1", "old-device", 42, 100, 7, 20)
        source.put("progress", "book", StoragePendingProgress(event, true))
        source.put("progress-device", "old-device", 42L)
        source.put("directories", "", "cache")
        val payload = StorageBackup(original, Json).create(1, "account", "root")
        val restored = MemoryStorageRepository()
        StorageBackup(restored, Json).restore(2, "root", payload)
        val target = StorageRecordStore(StorageSession(2, "account", "root") { true }, restored, Json)
        assertEquals(StoragePendingProgress(event, true, true), target.get<StoragePendingProgress>("progress", "book"))
        val backend = MemoryStorageBackend()
        var progress = StorageProgress(backend, target, "new-device")
        progress.flush()
        assertEquals(0, backend.uploads)
        assertEquals(emptySet<String>(), backend.createdDirectories)
        assertEquals(koharia.connection.ConnectionReadStatus.HELD, progress.readStatus("book"))
        val continued = progress.record("book", "v1", 200, 8, 20)
        progress = StorageProgress(backend, target, "new-device")
        progress.flush()
        assertEquals(0, backend.uploads)
        assertEquals(8, progress.cached("book")?.page)
        assertNotEquals(event.device, continued.device)
        assertEquals(continued.device, progress.cached("book")?.device)
        assertEquals(koharia.connection.ConnectionReadStatus.HELD, progress.readStatus("book"))
        val replay = StorageBackup(restored, Json).create(2, "account", "root")
        StorageBackup(restored, Json).restore(3, "root", replay)
        assertEquals(
            target.get<StoragePendingProgress>("progress", "book"),
            Json.decodeFromString<StoragePendingProgress>(restored.get(3, "account", "root", "progress", "book")!!),
        )
        assertNull(target.get<Long>("progress-device", "old-device"))
        assertNull(target.get<String>("directories", ""))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { StorageBackup(restored, Json).restore(2, "different-root", payload) }
        }
    }

    @Test fun `restoring old progress cannot overwrite a newer reread`() = runBlocking {
        val repository = MemoryStorageRepository()
        val source = StorageRecordStore(StorageSession(1, "account", "root") { true }, repository, Json)
        source.put(
            "progress",
            "book",
            StoragePendingProgress(
                StorageProgressRecord("book", "v1", "a", 1, 100, 19, 20, true),
                true,
            ),
        )
        val backup = StorageBackup(repository, Json)
        val payload = backup.create(1, "account", "root")
        val reread = StoragePendingProgress(StorageProgressRecord("book", "v1", "b", 1, 101, 0, 20), true)
        source.put("progress", "book", reread)
        backup.restore(1, "root", payload)
        assertEquals(reread, source.get<StoragePendingProgress>("progress", "book"))
    }

    @Test fun `repeated restore cannot pause an identical live event or revive its acknowledgement`() = runBlocking {
        val repository = MemoryStorageRepository()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, repository, Json)
        val pending = StoragePendingProgress(StorageProgressRecord("book", "v1", "device", 1, 100), true)
        store.put("progress", "book", pending)
        val backup = StorageBackup(repository, Json)
        val payload = backup.create(1, "account", "root")
        backup.restore(1, "root", payload)
        assertEquals(pending, store.get<StoragePendingProgress>("progress", "book"))
        store.put("progress", "book", pending.copy(pending = false))
        backup.restore(1, "root", payload)
        assertEquals(pending.copy(pending = false), store.get<StoragePendingProgress>("progress", "book"))
    }
}
