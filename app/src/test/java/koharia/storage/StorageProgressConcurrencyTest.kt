package koharia.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StorageProgressConcurrencyTest {
    private fun store(repository: MemoryStorageRepository, connection: Long = 1) =
        StorageRecordStore(StorageSession(connection, "account", "root") { true }, repository, Json)

    @Test fun adoptionWaitsForAnInFlightUploadThenRejectsTheObsoleteRemoteChoice() = runBlocking {
        val store = store(MemoryStorageRepository())
        val remote = MemoryStorageBackend()
        val cloud = StorageProgressRecord("book", "v1", "remote", 2, 200, locator = "remote")
        remote.seed(
            ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-2.json",
            Json.encodeToString(cloud),
        )
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun write(
                path: String,
                data: java.io.InputStream,
                length: Long,
                expectedVersion: String?,
            ) {
                entered.complete(Unit)
                release.await()
                remote.write(path, data, length, expectedVersion)
            }
        }
        val progress = StorageProgress(backend, store, "installation")
        val local = progress.record("book", "v1", 900, locator = "local")
        val review = progress.reviewSnapshot("book", "v1")
        kotlinx.coroutines.coroutineScope {
            val upload = async { progress.flush() }
            entered.await()
            val adoption = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                runCatching {
                    progress.acceptRemote("book", "v1", 200, review, checkAcceptance = {}) { it == "remote" }
                }
            }
            try {
                assertEquals(false, adoption.isCompleted)
            } finally {
                release.complete(Unit)
            }
            upload.await()
            assertTrue(adoption.await().exceptionOrNull() is StorageFailure)
        }
        assertEquals(StoragePendingProgress(local, false), store.get<StoragePendingProgress>("progress", "book"))
        assertEquals(1, remote.uploads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["newer", "held", "removed"])
    fun deliberateAdoptionPreservesChangesDuringRemoteRead(change: String) = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val local =
            StoragePendingProgress(StorageProgressRecord("book", "v1", "local", 1, 900, locator = "local"), true)
        val replacement = local.copy(
            record = local.record.copy(modifiedAt = 1_000),
            requiresConfirmation =
            change == "held",
        )
        store.put("progress", "book", local, 1)
        val remote = MemoryStorageBackend()
        val cloud = StorageProgressRecord("book", "v1", "remote", 2, 200, locator = "remote")
        remote.seed(
            ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-2.json",
            Json.encodeToString(cloud),
        )
        var accepting = false
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun list(path: String): List<StorageEntry> {
                if (!accepting) return remote.list(path)
                if (change == "removed") {
                    store.remove("progress", "book", 1)
                } else {
                    store.put("progress", "book", replacement, 1)
                }
                return remote.list(path)
            }
        }
        val progress = StorageProgress(backend, store, "installation")
        val review = progress.reviewSnapshot("book", "v1")
        accepting = true
        val failed =
            runCatching {
                progress.acceptRemote("book", "v1", 200, review, checkAcceptance = {}) {
                    it ==
                        "remote"
                }
            }
        assertTrue(failed.exceptionOrNull() is StorageFailure)
        assertEquals(
            if (change ==
                "removed"
            ) {
                null
            } else {
                replacement
            },
            store.get<StoragePendingProgress>("progress", "book"),
        )
        assertEquals(0, remote.uploads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["newer", "held", "removed", "initial"])
    fun pullDoesNotPublishOverAChangeMadeDuringTheRequest(change: String) = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val old = StoragePendingProgress(StorageProgressRecord("book", "v1", "writer", 1, 100, 1, 10), true)
        val replacement = old.copy(
            record = old.record.copy(modifiedAt = 200, page = 8),
            requiresConfirmation =
            change == "held",
        )
        if (change != "initial") store.put("progress", "book", old, 1)
        val remote = MemoryStorageBackend()
        val cloud = old.record.copy(device = "remote", modifiedAt = 300, page = 9)
        remote.seed(
            ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-1.json",
            Json.encodeToString(cloud),
        )
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun list(path: String): List<StorageEntry> {
                if (change == "removed") {
                    store.remove("progress", "book", 1)
                } else {
                    store.put("progress", "book", replacement, 1)
                }
                return remote.list(path)
            }
        }
        val result = StorageProgress(backend, store, "writer").pull("book", "v1")
        if (change == "removed") {
            assertNull(store.get<StoragePendingProgress>("progress", "book"))
            assertNull(result)
        } else {
            assertEquals(replacement, store.get<StoragePendingProgress>("progress", "book"))
            assertEquals(replacement.record, result)
        }
        assertEquals(0, remote.uploads)
    }

    @Test
    fun aConcurrentRestoreHoldSurvivesRecordingANewLocalPosition() = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val old = StoragePendingProgress(StorageProgressRecord("book", "v1", "old-writer", 1, 100), true)
        store.put("progress", "book", old, 1)
        var armed = true
        repository.afterSnapshotRead = { namespace, key ->
            if (armed && namespace == "progress" && key == "book") {
                armed = false
                store.put("progress", "book", old.copy(requiresConfirmation = true), 1)
            }
        }
        val event = StorageProgress(MemoryStorageBackend(), store, "writer").record("book", "v1", 200, 8, 10)
        assertEquals(200L, event.modifiedAt)
        assertEquals(8, event.page)
        assertTrue(store.get<StoragePendingProgress>("progress", "book")!!.requiresConfirmation)
    }

    @Test
    fun concurrentResourcesCannotReuseTheDeviceSequence() = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val backend = MemoryStorageBackend()
        var armed = true
        repository.afterSnapshotRead = { namespace, _ ->
            if (armed && namespace == "progress-device") {
                armed = false
                StorageProgress(backend, store, "writer").record("second", "v1", 200)
            }
        }
        val first = StorageProgress(backend, store, "writer").record("first", "v1", 100)
        val second = store.get<StoragePendingProgress>("progress", "second")!!.record
        assertNotEquals(first.sequence, second.sequence)
        assertEquals(first.device, second.device)
        assertEquals(maxOf(first.sequence, second.sequence), store.get<Long>("progress-device", first.device))
    }

    @Test
    fun restoreReconsidersAConcurrentNewerLocalRecord() = runBlocking {
        val repository = MemoryStorageRepository()
        val store = store(repository)
        val donor = store(repository, 2)
        val old = StoragePendingProgress(StorageProgressRecord("book", "v1", "writer", 1, 100), true)
        val incoming = old.copy(record = old.record.copy(modifiedAt = 200))
        val newer = old.copy(record = old.record.copy(modifiedAt = 400, sequence = 2))
        store.put("progress", "book", old, 1)
        donor.put("progress", "book", incoming, 1)
        val backup = StorageBackup(repository, Json)
        val payload = backup.create(2, "account", "root")
        var armed = true
        repository.afterSnapshotRead = { namespace, _ ->
            if (armed && namespace == "progress") {
                armed = false
                store.put("progress", "book", newer, 2)
            }
        }
        backup.restore(1, "root", payload)
        assertEquals(newer, store.get<StoragePendingProgress>("progress", "book"))
    }
}
