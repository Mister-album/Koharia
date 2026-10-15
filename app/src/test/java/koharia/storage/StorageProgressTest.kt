package koharia.storage

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream

class StorageProgressTest {
    @ParameterizedTest
    @ValueSource(strings = ["remote-sequence", "remote-page", "owner", "held", "removed", "consumed"])
    fun remoteAdoptionRequiresTheWholeComparedRecordAndOwner(change: String) = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "installation")
        val local = progress.record("book", "v1", 900, locator = "local")
        val remote = StorageProgressRecord("book", "v1", "remote-writer", 7, 200, locator = "remote")
        val path = ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-7.json"
        backend.seed(path, Json.encodeToString(remote))
        val review = progress.reviewSnapshot("book", "v1")
        when (change) {
            "remote-sequence" -> backend.seed(path, Json.encodeToString(remote.copy(sequence = 8)))
            "remote-page" -> backend.seed(path, Json.encodeToString(remote.copy(page = 2, total = 4)))
            "held" -> store.put("progress", "book", StoragePendingProgress(local, true, true))
            "removed" -> store.remove("progress", "book", local.sequence)
            "consumed" -> progress.acceptRemote("book", "v1", 200, review, {}) { it == "remote" }
        }
        val before = store.snapshot("progress", "book")
        val receiver = if (change == "owner") StorageProgress(backend, store, "installation") else progress
        val failure = runCatching {
            receiver.acceptRemote("book", "v1", 200, review, {}) { it == "remote" }
        }.exceptionOrNull()
        assertTrue(failure is StorageFailure)
        assertEquals(before, store.snapshot("progress", "book"))
        assertEquals(0, backend.uploads)
    }

    @Test fun `old remote comparison cannot replace reading recorded after review`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "installation")
        progress.record("book", "v1", 900, locator = "local")
        val remote = StorageProgressRecord("book", "v1", "remote-writer", 7, 200, locator = "remote")
        backend.seed(
            ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-7.json",
            Json.encodeToString(remote),
        )
        val review = progress.reviewSnapshot("book", "v1")
        assertEquals(remote, review?.remote)
        val newer = progress.record("book", "v1", 1_000, locator = "next")
        val failure = runCatching {
            progress.acceptRemote("book", "v1", 200, review, checkAcceptance = {}) { it == "remote" }
        }.exceptionOrNull()
        assertTrue(failure is StorageFailure)
        assertEquals(StoragePendingProgress(newer, true), store.get<StoragePendingProgress>("progress", "book"))
        assertEquals(0, backend.uploads)
    }

    @Test fun `explicit remote adoption retires only the selected pending value and survives restart`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "installation")
        val previous = progress.record("book", "v1", 900, locator = "local")
        val remote = StorageProgressRecord("book", "v1", "remote-writer", 7, 200, locator = "remote")
        backend.seed(
            ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-7.json",
            Json.encodeToString(remote),
        )
        val review = progress.reviewSnapshot("book", "v1")
        progress.acceptRemote("book", "v1", 200, review, checkAcceptance = {}) { it == "remote" }
        assertEquals(StoragePendingProgress(remote, false), store.get<StoragePendingProgress>("progress", "book"))
        val restarted = StorageProgress(backend, store, "installation")
        restarted.flush()
        assertEquals(0, backend.uploads)
        assertEquals(remote, restarted.pull("book", "v1"))
        val next = restarted.record("book", "v1", 1_000, locator = "next")
        assertEquals(previous.device, next.device)
        assertTrue(next.device != remote.device)
        assertTrue(next.sequence > remote.sequence)
        restarted.flush()
        assertEquals(1, backend.uploads)
        assertEquals(koharia.connection.ConnectionReadStatus.SYNCED, restarted.readStatus("book"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["held", "disabled", "version", "locator", "time", "missing"])
    fun remoteAdoptionCannotDropUnverifiedOrUnmatchedPendingState(change: String) = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val local =
            StoragePendingProgress(
                StorageProgressRecord("book", "v1", "local-writer", 1, 900, locator = "local"),
                true,
                change == "held",
            )
        store.put("progress", "book", local, 1)
        if (change != "missing") {
            val remote = StorageProgressRecord("book", "v1", "remote-writer", 7, 200, locator = "remote")
            backend.seed(
                ".koharia/progress/${storageDigest("account")}-${storageDigest("book")}-remote-7.json",
                Json.encodeToString(remote),
            )
        }
        val progress = StorageProgress(backend, store, "installation", remoteEnabled = change != "disabled")
        val failure = runCatching {
            progress.acceptRemote(
                "book",
                if (change ==
                    "version"
                ) {
                    "v2"
                } else {
                    "v1"
                },
                if (change == "time") 201 else 200,
                review = progress.reviewSnapshot("book", "v1"),
                checkAcceptance = {},
            ) {
                it == if (change == "locator") "different" else "remote"
            }
        }.exceptionOrNull()
        assertTrue(failure is StorageFailure)
        assertEquals(local, store.get<StoragePendingProgress>("progress", "book"))
        assertEquals(0, backend.uploads)
    }

    @Test fun `EPUB acknowledgement requires the exact unheld uploaded record`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "writer")
        assertEquals(false, progress.isEpubConfirmed("book", "v1", "position", 100))
        progress.record("book", "v1", 100, locator = "position")
        assertEquals(false, progress.isEpubConfirmed("book", "v1", "position", 100))
        progress.flush()
        assertTrue(progress.isEpubConfirmed("book", "v1", "position", 100))
        assertEquals(false, progress.isEpubConfirmed("book", "v2", "position", 100))
        assertEquals(false, progress.isEpubConfirmed("book", "v1", "other", 100))
        assertEquals(false, progress.isEpubConfirmed("book", "v1", "position", 101))
        assertEquals(
            false,
            StorageProgress(backend, store, "writer", false).isEpubConfirmed("book", "v1", "position", 100),
        )
        val confirmed = checkNotNull(store.get<StoragePendingProgress>("progress", "book"))
        store.put("progress", "book", confirmed.copy(requiresConfirmation = true))
        assertEquals(false, progress.isEpubConfirmed("book", "v1", "position", 100))
        progress.record("book", "v1", 200, locator = "next")
        assertEquals(false, progress.isEpubConfirmed("book", "v1", "position", 100))
    }

    @Test
    fun `review reads remote evidence without substituting or adopting local progress`() = runBlocking {
        val backend = MemoryStorageBackend()
        val repository = MemoryStorageRepository()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, repository, Json)
        val remoteStore = StorageRecordStore(StorageSession(2, "account", "root") { true }, repository, Json)
        val remote = StorageProgress(backend, remoteStore, "other-device")
        val progress = StorageProgress(backend, store, "local-device")
        progress.record("book", "v1", 300, locator = "local")
        val original = store.snapshot("progress", "book")
        assertNull(progress.review("book", "v1"))
        remote.record("book", "v1", 200, locator = "remote")
        remote.flush()
        assertEquals(remote.cached("book"), progress.review("book", "v1"))
        assertEquals(original, store.snapshot("progress", "book"))
        assertNull(progress.review("book", "v2"))
        assertNull(StorageProgress(backend, store, "local-device", false).review("book", "v1"))
        val held = decodeStoragePendingProgress(checkNotNull(original), Json).copy(requiresConfirmation = true)
        store.put("progress", "book", held, original.revision)
        assertNull(progress.review("book", "v1"))
        assertEquals(held, store.get<StoragePendingProgress>("progress", "book"))
        assertEquals(1, backend.uploads)
    }

    @Test
    fun `review rejects a local restore during remote IO without adopting the response`() = runBlocking {
        val remote = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val held = StoragePendingProgress(StorageProgressRecord("book", "v1", "old-writer", 1, 100), true, true)
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun list(path: String): List<StorageEntry> {
                store.put("progress", "book", held)
                return emptyList()
            }
        }
        val result = runCatching { StorageProgress(backend, store, "writer").review("book", "v1") }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(held, store.get<StoragePendingProgress>("progress", "book"))
        assertEquals(0, remote.uploads)
    }

    @ParameterizedTest
    @ValueSource(strings = ["newer", "restored", "removed"])
    fun uploadConfirmationPreservesConcurrentChange(change: String) = runBlocking {
        val repository = MemoryStorageRepository()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, repository, Json)
        val donor = StorageRecordStore(StorageSession(2, "account", "root") { true }, repository, Json)
        val incoming =
            StoragePendingProgress(StorageProgressRecord("book", "v1", "restored-writer", 1, 200, 8, 10), true)
        donor.put("progress", "book", incoming, 1)
        val backup = StorageBackup(repository, Json)
        val snapshot = backup.create(2, "account", "root")
        val remote = MemoryStorageBackend()
        var changed = false
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                remote.write(path, data, length, expectedVersion)
                if (changed) return
                changed = true
                when (change) {
                    "newer" -> StorageProgress(remote, store, "writer").record("book", "v1", 200, 8, 10)
                    "restored" -> backup.restore(1, "root", snapshot)
                    "removed" -> store.remove("progress", "book", store.list("progress").single().revision)
                }
            }
        }
        val progress = StorageProgress(backend, store, "writer")
        progress.record("book", "v1", 100, 1, 10)
        progress.flush()
        assertEquals(1, remote.uploads)
        val retained = store.get<StoragePendingProgress>("progress", "book")
        when (change) {
            "newer" -> {
                assertEquals(200L, retained!!.record.modifiedAt)
                assertEquals(2L, retained.record.sequence)
                assertTrue(retained.pending)
            }
            "restored" -> assertEquals(incoming.copy(requiresConfirmation = true), retained)
            "removed" -> assertNull(retained)
        }
        progress.flush()
        assertEquals(if (change == "newer") 2 else 1, remote.uploads)
    }

    @Test
    fun pendingSnapshotIsRecheckedAfterDirectoryLookup() = runBlocking {
        val remote = MemoryStorageBackend().apply { directories += ".koharia/progress" }
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun stat(path: String): StorageEntry {
                if (path == ".koharia/progress") {
                    val current = store.get<StoragePendingProgress>("progress", "book")!!
                    store.put("progress", "book", current.copy(requiresConfirmation = true), current.record.sequence)
                }
                return remote.stat(path)
            }
        }
        val progress = StorageProgress(backend, store, "writer")
        progress.record("book", "v1", 100, 1, 10)
        progress.flush()
        assertEquals(0, remote.uploads)
        assertTrue(store.get<StoragePendingProgress>("progress", "book")!!.requiresConfirmation)
    }

    @Test
    fun aLaterQueuedRecordHeldDuringAnEarlierUploadIsNeverSent() = runBlocking {
        val remote = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val backend = object : LibraryStorageBackend by remote {
            override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
                val bytes = data.readBytes()
                remote.write(path, bytes.inputStream(), length, expectedVersion)
                val uploaded = Json.decodeFromString<StorageProgressRecord>(bytes.decodeToString())
                for (row in store.list("progress").filter { it.key != uploaded.resource }) {
                    val current = decodeStoragePendingProgress(row, Json)
                    store.put("progress", row.key, current.copy(requiresConfirmation = true), row.revision)
                }
            }
        }
        val progress = StorageProgress(backend, store, "writer")
        progress.record("first", "v1", 100, 1, 10)
        progress.record("later", "v1", 200, 2, 10)
        progress.flush()
        assertEquals(1, remote.uploads)
        assertEquals(StoragePendingSummary(held = 1), storagePendingSummary(store.list("progress"), 0, true, Json))
        progress.flush()
        assertEquals(1, remote.uploads)
    }

    @Test fun `unreadable and misowned records stay intact while valid unrelated progress uploads`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val progress = StorageProgress(backend, store, "writer")
        store.put("progress", "corrupt", "malformed")
        store.put(
            "progress",
            "wrong-key",
            StoragePendingProgress(StorageProgressRecord("other-book", "v1", "writer", 1, 100), true),
        )
        store.put(
            "progress",
            "future",
            StoragePendingProgress(StorageProgressRecord("future", "v1", "writer", 1, 100, schema = 2), true),
        )
        store.put(
            "progress",
            "held",
            StoragePendingProgress(StorageProgressRecord("held", "v1", "old-writer", 1, 100), true, true),
        )
        val retained = store.list("progress")
        progress.flush()
        assertEquals(0, backend.uploads)
        progress.record("valid-book", "v1", 200, 2, 10)
        progress.flush()
        assertEquals(1, backend.uploads)
        assertEquals(koharia.connection.ConnectionReadStatus.SYNCED, progress.readStatus("valid-book"))
        assertEquals(retained, store.list("progress").filter { it.key != "valid-book" })
        assertEquals(
            StoragePendingSummary(held = 1, unavailable = 3),
            storagePendingSummary(store.list("progress"), 0, true, Json),
        )
        progress.flush()
        assertEquals(1, backend.uploads)
    }

    @Test fun `held restore survives remote pull new local reading and disabled synchronization`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        val held = StoragePendingProgress(StorageProgressRecord("book", "v1", "old-device", 1, 100, 3, 10), true, true)
        store.put("progress", "book", held)
        val remote = StorageProgress(
            backend,
            StorageRecordStore(StorageSession(2, "account", "root") { true }, MemoryStorageRepository(), Json),
            "other-device",
        )
        remote.record("book", "v1", 200, 8, 10)
        remote.flush()
        val progress = StorageProgress(backend, store, "current-device")
        assertEquals(held.record, progress.pull("book", "v1"))
        assertEquals(held, store.get<StoragePendingProgress>("progress", "book"))
        assertNull(progress.pull("book", "v2"))
        assertEquals(held, store.get<StoragePendingProgress>("progress", "book"))
        val disabled = StorageProgress(backend, store, "current-device", remoteEnabled = false)
        disabled.record("book", "v1", 300, locator = "new local locator")
        assertEquals(koharia.connection.ConnectionReadStatus.HELD, disabled.readStatus("book"))
        progress.flush()
        assertEquals(1, backend.uploads)
        assertEquals("new local locator", progress.cached("book")?.locator)
        assertTrue(store.get<StoragePendingProgress>("progress", "book")!!.pending)
        progress.record("unrelated", "v1", 400, 2, 10)
        progress.flush()
        assertEquals(2, backend.uploads)
        assertEquals(koharia.connection.ConnectionReadStatus.SYNCED, progress.readStatus("unrelated"))
        assertEquals(koharia.connection.ConnectionReadStatus.HELD, progress.readStatus("book"))
    }

    @Test fun `pre-hold payloads keep live pending semantics`() {
        val payload = """
            {"record":{"resource":"book","version":"v1","device":"device","sequence":1,"modifiedAt":100},
            "pending":true}
        """.trimIndent()
        val restored = Json.decodeFromString<StoragePendingProgress>(payload)
        assertEquals(false, restored.requiresConfirmation)
        assertTrue(restored.pending)
    }

    @Test
    fun `sync outcome distinguishes missing local-only pending and confirmed records after restart`() = runBlocking {
        val backend = MemoryStorageBackend()
        val store = StorageRecordStore(StorageSession(1, "account", "root") { true }, MemoryStorageRepository(), Json)
        var progress = StorageProgress(backend, store, "device")
        assertEquals(koharia.connection.ConnectionReadStatus.UNAVAILABLE, progress.readStatus("book"))
        progress.record("book", "v1", 100, 0, 10, false)
        progress = StorageProgress(backend, store, "device")
        assertEquals(koharia.connection.ConnectionReadStatus.PENDING, progress.readStatus("book"))
        progress.flush()
        assertEquals(koharia.connection.ConnectionReadStatus.SYNCED, progress.readStatus("book"))
        val local = StorageProgress(backend, store, "device", remoteEnabled = false)
        local.record("local", "v1", 100, 0, 10, false)
        assertEquals(koharia.connection.ConnectionReadStatus.LOCAL_ONLY, local.readStatus("local"))
    }

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
