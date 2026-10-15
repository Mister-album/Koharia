package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StorageWriterIdentityTest {
    private fun store(repository: MemoryStorageRepository, connection: Long = 1) =
        StorageRecordStore(StorageSession(connection, "account", "root") { true }, repository, Json)

    @Test
    fun concurrentFirstWritersConvergeAndRestartKeepsTheirCounter() = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val remote = MemoryStorageBackend()
        var armed = true
        repository.afterSnapshotRead = { namespace, _ ->
            if (armed && namespace == "progress-writer") {
                armed = false
                StorageProgress(remote, store, "installation").record("second", "v1", 200)
            }
        }
        val first = StorageProgress(remote, store, "installation").record("first", "v1", 100)
        val second = store.get<StoragePendingProgress>("progress", "second")!!.record
        val restarted = StorageProgress(remote, store, "installation").record("third", "v1", 300)
        assertEquals(second.device, first.device)
        assertEquals(first.device, restarted.device)
        assertEquals(listOf(1L, 2L, 3L), listOf(second.sequence, first.sequence, restarted.sequence))
        val reinstalled = StorageProgress(remote, store, "another-installation").record("fourth", "v1", 400)
        assertNotEquals(first.device, reinstalled.device)
        assertEquals(0, remote.uploads)
    }

    @Test
    fun corruptWriterStateIsRetainedAndDoesNotReplacePendingProgress() = runBlocking {
        val store = store(MemoryStorageRepository())
        val remote = MemoryStorageBackend()
        val pending = StoragePendingProgress(StorageProgressRecord("book", "v1", "old", 1, 100), true)
        store.put("progress", "book", pending, 1)
        store.put("progress-writer", "installation", "invalid", 1)
        val result = runCatching { StorageProgress(remote, store, "installation").record("book", "v1", 200) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(pending, store.get<StoragePendingProgress>("progress", "book"))
        assertEquals("invalid", store.get<String>("progress-writer", "installation"))
        assertTrue(store.list("progress-device").isEmpty())
        assertEquals(0, remote.uploads)
    }

    @Test
    fun duplicateConnectionsCanUploadIndependentOfflineEventsToTheSameRoot() = runBlocking {
        val repository = MemoryStorageRepository()
        val remote = MemoryStorageBackend()
        val first = StorageProgress(remote, store(repository, 1), "installation")
        val second = StorageProgress(remote, store(repository, 2), "installation")
        val a = first.record("book", "v1", 100, 1, 10)
        val b = second.record("book", "v1", 200, 8, 10)
        first.flush()
        second.flush()
        assertEquals(2, remote.contents.size)
        assertNotEquals(a.device, b.device)
        assertEquals(b, first.pull("book", "v1"))
        assertEquals(b, second.pull("book", "v1"))
    }

    @Test
    fun recreatingRemovedLocalOwnerStateCannotReuseAnExistingRemoteFilename() = runBlocking {
        val repository = MemoryStorageRepository()
        val remote = MemoryStorageBackend()
        val first = StorageProgress(remote, store(repository), "installation")
        val old = first.record("book", "v1", 100, 1, 10)
        first.flush()
        repository.removeConnection(1)
        val replacement = StorageProgress(remote, store(repository), "installation")
        val current = replacement.record("book", "v1", 200, 8, 10)
        replacement.flush()
        assertEquals(2, remote.contents.size)
        assertNotEquals(old.device, current.device)
        assertEquals(current, replacement.pull("book", "v1"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["identical", "conflicting", "held"])
    fun legacyQueuedEventsKeepTheirIdentityAndConflictProtection(kind: String) = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val remote = MemoryStorageBackend()
        val legacy = StorageProgressRecord("book", "v1", "installation", 1, 100, 1, 10)
        val pending = StoragePendingProgress(legacy, true, kind == "held")
        val path = ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-installation-1.json"
        val existing = if (kind == "conflicting") legacy.copy(page = 7) else legacy
        val payload = Json.encodeToString(existing)
        remote.seed(path, payload)
        store.put("progress", "book", pending, 1)
        val progress = StorageProgress(remote, store, "installation")
        val result = runCatching { progress.flush() }
        if (kind == "conflicting") {
            assertEquals(StorageFailure.Reason.CONFLICT, (result.exceptionOrNull() as StorageFailure).reason)
        } else {
            assertTrue(result.isSuccess)
        }
        assertEquals(payload, remote.contents.getValue(path).decodeToString())
        assertEquals(
            if (kind == "identical") pending.copy(pending = false) else pending,
            store.get<StoragePendingProgress>("progress", "book"),
        )
        assertEquals(0, remote.uploads)
        val fresh = progress.record("book", "v1", 200, 9, 10)
        assertNotEquals(legacy.device, fresh.device)
        progress.flush()
        assertEquals(payload, remote.contents.getValue(path).decodeToString())
        assertEquals(if (kind == "held") 0 else 1, remote.uploads)
        assertEquals(kind == "held", store.get<StoragePendingProgress>("progress", "book")!!.requiresConfirmation)
    }

    @Test
    fun restoredBackupNeverReusesTheExportingOwnersWriterEvenWithTheSameInstallationId() = runBlocking {
        val original = MemoryStorageRepository()
        val source = store(original)
        val backend = MemoryStorageBackend()
        val event = StorageProgress(backend, source, "installation").record("book", "v1", 100)
        val payload = StorageBackup(original, Json).create(1, "account", "root")
        assertFalse(payload.contains("progress-writer"))
        val restored = MemoryStorageRepository()
        StorageBackup(restored, Json).restore(1, "root", payload)
        val target = store(restored)
        val progress = StorageProgress(backend, target, "installation")
        val continued = progress.record("book", "v1", 200)
        assertNotEquals(event.device, continued.device)
        assertTrue(target.get<StoragePendingProgress>("progress", "book")!!.requiresConfirmation)
        progress.flush()
        assertEquals(0, backend.uploads)
    }
}
