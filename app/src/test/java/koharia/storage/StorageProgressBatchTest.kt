package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream

class StorageProgressBatchTest {
    private fun store() = StorageRecordStore(
        StorageSession(1, "account", "root") { true },
        MemoryStorageRepository(),
        Json,
    )

    private fun path(record: StorageProgressRecord) =
        ".koharia/progress/${storageDigest("account")}-${storageDigest(record.resource)}-" +
            "${record.device}-${record.sequence}.json"

    @ParameterizedTest
    @ValueSource(ints = [1, 100, 1000])
    fun batchPruningEnumeratesHistoryOnce(count: Int) = runBlocking {
        val store = store()
        val remote = MemoryStorageBackend()
        var listings = 0
        var enumerated = 0
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun list(path: String): List<StorageEntry> = remote.list(path).also {
                listings++
                enumerated += it.size
            }
        }
        val progress = StorageProgress(backend, store, "installation")
        val expected = (1..count).map { index ->
            val older = progress.record("book-$index", "v1", 100, 1, 10)
            remote.seed(path(older), Json.encodeToString(older))
            progress.record("book-$index", "v1", 200, 2, 10)
        }
        progress.flush()
        assertEquals(count, remote.uploads)
        assertEquals(
            expected.toSet(),
            remote.contents.values.map {
                Json.decodeFromString<StorageProgressRecord>(it.decodeToString())
            }.toSet(),
        )
        expected.forEach {
            assertEquals(StoragePendingProgress(it, false), store.get<StoragePendingProgress>("progress", it.resource))
        }
        println("PROGRESS_SCALE count=$count listings=$listings enumerated=$enumerated uploads=${remote.uploads}")
        assertTrue(listings <= 1, "A batch must not list the remote directory for each upload: $listings")
        assertTrue(enumerated <= 2 * count, "Remote history enumeration must scale linearly: $enumerated")
    }

    @ParameterizedTest
    @ValueSource(strings = ["beforeRead", "beforeDelete"])
    fun snapshotPreservesReplacementsAndLateArrivalsUntilAFreshPrune(change: String) = runBlocking {
        val store = store()
        val remote = MemoryStorageBackend()
        val recorder = StorageProgress(remote, store, "installation")
        val firstOld = recorder.record("first", "v1", 100)
        remote.seed(path(firstOld), Json.encodeToString(firstOld))
        val first = recorder.record("first", "v1", 200)
        val secondOld = recorder.record("second", "v1", 100)
        remote.seed(path(secondOld), Json.encodeToString(secondOld))
        val second = recorder.record("second", "v1", 200)
        val replacement = secondOld.copy(modifiedAt = 500, page = 9)
        val late = secondOld.copy(sequence = 0, modifiedAt = 150, page = 3)
        var listings = 0
        var replaced = false
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun list(path: String) = remote.list(path).also { listings++ }
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                remote.write(path, data, length, expectedVersion)
                if (path == path(second)) {
                    remote.seed(path(late), Json.encodeToString(late))
                    if (change == "beforeRead") {
                        remote.seed(path(secondOld), Json.encodeToString(replacement))
                        replaced = true
                    }
                }
            }
            override suspend fun read(entry: StorageEntry, offset: Long, length: Int): ByteArray {
                val bytes = remote.read(entry, offset, length)
                if (change == "beforeDelete" && entry.path == path(secondOld) && !replaced) {
                    remote.seed(path(secondOld), Json.encodeToString(replacement))
                    replaced = true
                }
                return bytes
            }
        }
        val progress = StorageProgress(backend, store, "installation")
        val outcome = runCatching { progress.flush() }
        if (change == "beforeDelete") {
            assertEquals(StorageFailure.Reason.CONFLICT, (outcome.exceptionOrNull() as? StorageFailure)?.reason)
        } else {
            outcome.getOrThrow()
        }
        assertTrue(replaced)
        assertEquals(1, listings)
        assertEquals(setOf(path(first), path(second), path(secondOld), path(late)), remote.contents.keys)
        assertEquals(
            replacement,
            Json.decodeFromString<StorageProgressRecord>(
                remote.contents.getValue(path(secondOld)).decodeToString(),
            ),
        )
        assertEquals(StoragePendingProgress(second, false), store.get<StoragePendingProgress>("progress", "second"))

        val latest = progress.record("second", "v1", 600, 10)
        progress.flush()
        assertEquals(2, listings)
        assertEquals(setOf(path(first), path(latest)), remote.contents.keys)
        assertEquals(3, remote.uploads)
    }

    @Test
    fun lostSecondUploadResponseRetainsPendingAndResumesWithFreshHistory() = runBlocking {
        val store = store()
        val remote = MemoryStorageBackend()
        val progress = StorageProgress(remote, store, "installation")
        val expected = (1..3).map {
            val older = progress.record("book-$it", "v1", 100)
            remote.seed(path(older), Json.encodeToString(older))
            progress.record("book-$it", "v1", 200)
        }
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                remote.write(path, data, length, expectedVersion)
                if (path == path(expected[1])) throw StorageFailure(StorageFailure.Reason.NETWORK)
            }
        }
        val outcome = runCatching { StorageProgress(backend, store, "installation").flush() }
        assertEquals(StorageFailure.Reason.NETWORK, (outcome.exceptionOrNull() as? StorageFailure)?.reason)
        assertEquals(
            StoragePendingProgress(expected[0], false),
            store.get<StoragePendingProgress>("progress", "book-1"),
        )
        for (event in expected.drop(1)) {
            assertEquals(
                StoragePendingProgress(event, true),
                store.get<StoragePendingProgress>("progress", event.resource),
            )
        }
        StorageProgress(remote, store, "installation").flush()
        assertEquals(3, remote.uploads)
        assertEquals(expected.map(::path).toSet(), remote.contents.keys)
        expected.forEach {
            assertEquals(StoragePendingProgress(it, false), store.get<StoragePendingProgress>("progress", it.resource))
        }
    }

    @Test
    fun resourceIndexKeepsExistingWriterAndSuffixCompatibility() = runBlocking {
        val store = store()
        val remote = MemoryStorageBackend()
        val progress = StorageProgress(remote, store, "installation")
        val older = progress.record("book", "v1", 100)
        val prefix = path(older).substringBeforeLast('-') + "-"
        remote.seed("${prefix}extra-suffix.json", Json.encodeToString(older))
        val other = older.copy(device = "other-writer")
        remote.seed(path(other), Json.encodeToString(other))
        val current = progress.record("book", "v1", 200)
        progress.flush()
        assertEquals(setOf(path(other), path(current)), remote.contents.keys)
    }
}
