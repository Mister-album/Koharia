package koharia.storage

import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import koharia.domain.storage.StorageRecordUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StorageProgressAcknowledgementDeviceTest {
    @Test
    fun remoteAdoptionUsesSqlCasAndNeverReplaysTheReplacedPendingRecord(): Unit = runBlocking(Dispatchers.IO) {
        for (change in listOf("none", "newer", "held", "removed", "paused", "after-review")) {
            fixture { store ->
                val old =
                    StoragePendingProgress(
                        StorageProgressRecord("book", "v1", "local", 1, 900, locator = "local"),
                        true,
                    )
                val newer = old.copy(
                    record = old.record.copy(modifiedAt = 1_000),
                    requiresConfirmation =
                    change == "held",
                )
                store.put("progress", "book", old, 1)
                val cloud = StorageProgressRecord("book", "v1", "remote", 2, 200, locator = "remote")
                val bytes = Json.encodeToString(cloud).encodeToByteArray()
                val remotePath = ".koharia/progress/${storageDigest(
                    store.session.account,
                )}-${storageDigest("book")}-remote-2.json"
                var accepting = false
                val backend = object : LibraryStorageBackend by NoStorageIo {
                    override suspend fun list(path: String): List<StorageEntry> {
                        if (accepting && change == "removed") {
                            store.remove("progress", "book", 1)
                        } else if (accepting && change in listOf("newer", "held")) {
                            store.put("progress", "book", newer, 1)
                        }
                        return listOf(StorageEntry(path = remotePath, directory = false, size = bytes.size.toLong()))
                    }
                    override suspend fun read(
                        entry: StorageEntry,
                        offset: Long,
                        length: Int,
                    ) = bytes.copyOfRange(
                        offset.toInt(),
                        offset.toInt() + length,
                    )
                }
                val progress = StorageProgress(backend, store, "installation")
                val review = progress.reviewSnapshot("book", "v1")
                if (change == "after-review") store.put("progress", "book", newer, 1)
                accepting = true
                var checks = 0
                val result = runCatching {
                    progress.acceptRemote("book", "v1", 200, review, checkAcceptance = {
                        if (++checks == 5 && change == "paused") throw koharia.connection.ConnectionWritesPaused()
                    }) { it == "remote" }
                }
                if (change == "none") {
                    result.getOrThrow()
                    assertEquals(
                        StoragePendingProgress(cloud, false),
                        store.get<StoragePendingProgress>("progress", "book"),
                    )
                    StorageProgress(NoStorageIo, store, "installation").flush()
                } else if (change == "paused") {
                    assertEquals(5, checks)
                    assertTrue(result.exceptionOrNull() is koharia.connection.ConnectionWritesPaused)
                    assertEquals(old, store.get<StoragePendingProgress>("progress", "book"))
                } else {
                    assertTrue(result.exceptionOrNull() is StorageFailure)
                    assertEquals(
                        if (change ==
                            "removed"
                        ) {
                            null
                        } else {
                            newer
                        },
                        store.get<StoragePendingProgress>("progress", "book"),
                    )
                }
            }
        }
    }

    @Test
    fun writerLifetimeFollowsLocalOwnerAndInstallationWithoutNetworkIo(): Unit = runBlocking(Dispatchers.IO) {
        fixture { first ->
            fixture { other ->
                val duplicate = StorageRecordStore(
                    other.session.copy(account = first.session.account, rootIdentity = first.session.rootIdentity),
                    first.repository,
                    Json,
                )
                val a = StorageProgress(NoStorageIo, first, "installation").record("book", "v1", 100)
                val b = StorageProgress(NoStorageIo, duplicate, "installation").record("book", "v1", 200)
                assertTrue(a.device != b.device)
                val restarted = StorageProgress(NoStorageIo, first, "installation").record("other", "v1", 300)
                assertEquals(a.device, restarted.device)
                assertEquals(a.sequence + 1, restarted.sequence)
                val reinstalled = StorageProgress(NoStorageIo, first, "new-installation").record("new", "v1", 400)
                assertTrue(a.device != reinstalled.device)
                for (session in listOf(
                    first.session.copy(account = "foreign-account"),
                    first.session.copy(rootIdentity = "foreign-root"),
                )) {
                    val foreign = StorageRecordStore(session, first.repository, Json)
                    val event = StorageProgress(NoStorageIo, foreign, "installation").record("book", "v1", 500)
                    assertTrue(a.device != event.device)
                }
                first.repository.removeConnection(first.session.connectionId)
                val recreated = StorageProgress(NoStorageIo, first, "installation").record("book", "v1", 600)
                assertTrue(a.device != recreated.device)
                assertEquals(1L, recreated.sequence)
                assertEquals(b, duplicate.get<StoragePendingProgress>("progress", "book")!!.record)
            }
        }
    }

    @Test
    fun groupedProgressAndCounterCommitIsAtomicAndInsertOnly(): Unit = runBlocking(Dispatchers.IO) {
        fixture { store ->
            val progress = StorageRecord("book", "new-event", 11)
            val counter = StorageRecord("writer", "10", 10)
            val advanced = counter.copy(payload = "11", revision = 11)
            store.repository.put(
                store.session.connectionId,
                store.session.account,
                store.session.rootIdentity,
                "progress-device",
                counter,
            )
            assertFalse(
                store.compareAndSet(
                    listOf(
                        StorageRecordUpdate("progress", null, progress),
                        StorageRecordUpdate("progress-device", null, advanced),
                    ),
                ),
            )
            assertNull(store.snapshot("progress", "book"))
            val updates =
                listOf(
                    StorageRecordUpdate("progress", null, progress),
                    StorageRecordUpdate("progress-device", counter, advanced),
                )
            var checks = 0
            val rejected = runCatching {
                store.repository.compareAndSet(
                    store.session.connectionId,
                    store.session.account,
                    store.session.rootIdentity,
                    updates,
                ) {
                    if (++checks == 3) throw CancellationException("Expire after both fixture writes")
                }
            }
            assertTrue(rejected.exceptionOrNull() is CancellationException)
            assertEquals(3, checks)
            assertNull(store.snapshot("progress", "book"))
            assertEquals(counter, store.snapshot("progress-device", "writer"))
            assertTrue(store.compareAndSet(updates))
            assertEquals(progress, store.snapshot("progress", "book"))
            assertEquals(advanced, store.snapshot("progress-device", "writer"))
            assertFalse(store.compareAndSet(updates))
        }
    }

    @Test
    fun independentReaderInstancesCommitDistinctDurableDeviceSequences(): Unit = runBlocking(Dispatchers.IO) {
        fixture { store ->
            val events = withTimeout(30_000) {
                coroutineScope {
                    (1..24).map { index ->
                        async(Dispatchers.IO) {
                            StorageProgress(
                                NoStorageIo,
                                store,
                                "writer",
                            ).record("book-$index", "v1", index.toLong(), index, 30)
                        }
                    }.awaitAll()
                }
            }
            assertEquals((1L..24L).toList(), events.map { it.sequence }.sorted())
            assertEquals(1, events.map { it.device }.distinct().size)
            assertEquals(24L, store.get<Long>("progress-device", events.first().device))
            assertEquals(24, store.list("progress").size)
            for (event in events) {
                assertEquals(event, store.get<StoragePendingProgress>("progress", event.resource)!!.record)
            }
        }
    }

    private object NoStorageIo : LibraryStorageBackend {
        override val capabilities = StorageCapabilities(true, true, true, true)
        override suspend fun stat(path: String): StorageEntry = error("Unexpected storage stat")
        override suspend fun list(path: String): List<StorageEntry> = error("Unexpected storage list")
        override suspend fun read(
            entry: StorageEntry,
            offset: Long,
            length: Int,
        ): ByteArray = error("Unexpected storage read")
        override suspend fun createDirectory(path: String): Unit = error("Unexpected directory mutation")
        override suspend fun write(
            path: String,
            data: java.io.InputStream,
            length: Long,
            expectedVersion: String?,
        ): Unit = error("Unexpected remote write")
        override suspend fun move(entry: StorageEntry, destination: String): Unit = error("Unexpected remote move")
        override suspend fun delete(entry: StorageEntry): Unit = error("Unexpected remote deletion")
        override fun close() = Unit
    }

    @Test
    fun completeSnapshotComparisonPreservesHoldsRevisionsAndRemovedRecords(): Unit = runBlocking(Dispatchers.IO) {
        fixture { store ->
            val value = StoragePendingProgress(StorageProgressRecord("book", "v1", "writer", 1, 100), true)
            store.put("progress", "book", value, 1)
            val expected = store.list("progress").single()
            val done = value.copy(pending = false)
            store.put("progress", "book", value.copy(requiresConfirmation = true), 1)
            assertFalse(store.replaceIfUnchanged("progress", expected, done))
            assertTrue(store.get<StoragePendingProgress>("progress", "book")!!.requiresConfirmation)
            store.put("progress", "book", value.copy(record = value.record.copy(modifiedAt = 200)), 1)
            assertFalse(store.replaceIfUnchanged("progress", expected, done))
            assertEquals(200L, store.get<StoragePendingProgress>("progress", "book")!!.record.modifiedAt)
            store.put("progress", "book", value, 2)
            assertFalse(store.replaceIfUnchanged("progress", expected, done))
            assertEquals(2L, store.list("progress").single().revision)
            store.remove("progress", "book", 2)
            assertFalse(store.replaceIfUnchanged("progress", expected, done))
            assertNull(store.get<StoragePendingProgress>("progress", "book"))
            store.put("progress", "book", value, 1)
            assertTrue(store.replaceIfUnchanged("progress", expected, done))
            assertEquals(done, store.get<StoragePendingProgress>("progress", "book"))
            assertEquals(1L, store.list("progress").single().revision)
            assertFalse(store.replaceIfUnchanged("progress", expected, done))
        }
    }

    @Test
    fun acknowledgementIsOwnerScopedAndRollsBackWhenSessionExpiresBeforeCommit(): Unit = runBlocking(Dispatchers.IO) {
        fixture { store ->
            val repository = store.repository
            val owner = store.session
            val value = StoragePendingProgress(StorageProgressRecord("book", "v1", "writer", 1, 100), true)
            val expected = StorageRecord("book", Json.encodeToString(value), 1)
            val updated = expected.copy(payload = Json.encodeToString(value.copy(pending = false)))
            for ((account, root) in listOf(
                owner.account to owner.rootIdentity,
                "foreign" to owner.rootIdentity,
                owner.account to "foreign",
            )) {
                repository.put(owner.connectionId, account, root, "progress", expected)
            }
            var checks = 0
            val rejected = runCatching {
                repository.replaceIfUnchanged(
                    owner.connectionId,
                    owner.account,
                    owner.rootIdentity,
                    "progress",
                    expected,
                    updated,
                ) {
                    if (++checks == 3) throw CancellationException("Expired fixture session before commit")
                }
            }
            assertTrue(rejected.exceptionOrNull() is CancellationException)
            assertEquals(3, checks)
            assertEquals(expected, store.list("progress").single())
            assertTrue(
                repository.replaceIfUnchanged(
                    owner.connectionId,
                    owner.account,
                    owner.rootIdentity,
                    "progress",
                    expected,
                    updated,
                ),
            )
            assertEquals(updated, store.list("progress").single())
            assertEquals(
                expected.payload,
                repository.get(owner.connectionId, "foreign", owner.rootIdentity, "progress", "book"),
            )
            assertEquals(
                expected.payload,
                repository.get(owner.connectionId, owner.account, "foreign", "progress", "book"),
            )
        }
    }

    private suspend fun fixture(block: suspend (StorageRecordStore) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<koharia.connection.ConnectionPreferences>().isKnownConnectionId(id))
        val repository = Injekt.get<LibraryStorageRepository>()
        val store = StorageRecordStore(StorageSession(id, "fixture", "root-$id") { true }, repository, Json)
        check(store.list("progress").isEmpty())
        try {
            block(store)
        } finally {
            repository.removeConnection(id)
            check(store.list("progress").isEmpty())
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                android.os.Bundle().apply { putString("progress_fixture_cleanup", id.toString()) },
            )
        }
    }
}
