package koharia.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import koharia.domain.storage.StorageRecordUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

class StorageProgressSqlScaleDeviceTest {
    @Test
    fun realSqlPendingScalesKeepHistoryAndOwnersAcrossWebDav(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val proof = checkNotNull(InstrumentationRegistry.getArguments().getString("storageScaleProof"))
        val actual = Injekt.get<LibraryStorageRepository>()
        val client = OkHttpClient()
        try {
            for (count in listOf(1, 100, 1000)) {
                val id = System.nanoTime()
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
                check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id + 1))
                val owner = StorageSession(id, "owned-account", "owned-root") { true }
                val foreign = listOf(
                    owner.copy(account = "foreign-account"),
                    owner.copy(rootIdentity = "foreign-root"),
                    owner.copy(connectionId = id + 1),
                )
                val repository = CountedRepository(actual)
                val store = StorageRecordStore(owner, repository, Json)
                check(store.list("progress").isEmpty())
                val controls = foreign.map { StorageRecordStore(it, actual, Json) }
                check(controls.all { it.list("progress").isEmpty() })
                try {
                    WebDavStorageBackend(client, "http://127.0.0.1:18901/scale-$count/", "", "").use { remote ->
                        val entry = remote.stat(".fixture-proof")
                        check(remote.read(entry, 0, entry.size.toInt()).decodeToString() == proof)
                        check(remote.list(".koharia/progress").isEmpty())
                        val backend = CountedBackend(remote)
                        val progress = StorageProgress(backend, store, "owned-installation")
                        repeat(1000) { index ->
                            store.put(
                                "progress",
                                "history-$index",
                                StoragePendingProgress(
                                    StorageProgressRecord("history-$index", "v1", "history-writer", 1, 10),
                                    false,
                                ),
                                1,
                            )
                        }
                        val history = store.list("progress").toSet()
                        val held = StoragePendingProgress(
                            StorageProgressRecord("book-0", "v1", "foreign-writer", 7, 100, 4, 10),
                            true,
                            true,
                        )
                        controls.forEach { it.put("progress", "book-0", held, 7) }
                        val foreignBefore = controls.map { it.list("progress") }
                        repeat(count) { progress.record("book-$it", "v1", 100, 1, 10) }
                        progress.flush()
                        repository.reset()
                        val expected = (0 until count).map { progress.record("book-$it", "v1", 200, 2, 10) }
                        assertEquals(count, repository.gets)
                        assertEquals(2 * count, repository.snapshots)
                        assertEquals(count, repository.commits)
                        assertEquals(2 * count, repository.commitRecords)
                        repository.reset()
                        backend.reset()
                        val started = System.nanoTime()
                        progress.flush()
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        assertEquals(1, repository.lists)
                        assertEquals(1000 + count, repository.rows)
                        assertEquals(3 * count, repository.gets)
                        assertEquals(count, repository.acks)
                        assertEquals(0, repository.snapshots)
                        assertEquals(0, repository.commits)
                        assertEquals(1, backend.lists)
                        assertEquals(count + 1, backend.entries)
                        assertEquals(count, backend.reads)
                        assertEquals(count, backend.writes)
                        assertEquals(count, backend.deletes)
                        instrumentation.sendStatus(
                            0,
                            Bundle().apply {
                                putString(
                                    "storage_scale",
                                    "pending=$count;history=1000;lists=${repository.lists};rows=${repository.rows};" +
                                        "get=${repository.gets};ack=${repository.acks};remote_lists=${backend.lists};" +
                                        "remote_entries=${backend.entries};read=${backend.reads};" +
                                        "write=${backend.writes};" +
                                        "delete=${backend.deletes};elapsed_ms=$elapsed;writer=${expected.first().device}",
                                )
                            },
                        )
                        val after = store.list("progress")
                        assertEquals(history, after.filter { it.key.startsWith("history-") }.toSet())
                        expected.forEach { event ->
                            assertEquals(
                                StorageRecord(
                                    event.resource,
                                    Json.encodeToString(StoragePendingProgress(event, false)),
                                    event.sequence,
                                ),
                                store.snapshot("progress", event.resource),
                            )
                        }
                        assertEquals(foreignBefore, controls.map { it.list("progress") })
                        val remoteRecords = remote.list(".koharia/progress").map {
                            assertTrue(!it.directory && it.size in 1..65536)
                            Json.decodeFromString<StorageProgressRecord>(
                                remote.read(it, 0, it.size.toInt()).decodeToString(),
                            )
                        }
                        assertEquals(expected.toSet(), remoteRecords.toSet())
                        assertEquals(count, remoteRecords.size)
                    }
                } finally {
                    actual.removeConnection(id)
                    actual.removeConnection(id + 1)
                    for (scoped in listOf(StorageRecordStore(owner, actual, Json)) + controls) {
                        for (namespace in listOf("progress", "progress-device", "progress-writer")) {
                            check(scoped.list(namespace).isEmpty())
                        }
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("storage_scale_cleanup", count.toString())
                        },
                    )
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private class CountedRepository(val actual: LibraryStorageRepository) : LibraryStorageRepository by actual {
        var lists = 0
        var rows = 0
        var gets = 0
        var snapshots = 0
        var commits = 0
        var commitRecords = 0
        var acks = 0
        fun reset() {
            lists = 0
            rows = 0
            gets = 0
            snapshots = 0
            commits = 0
            commitRecords = 0
            acks = 0
        }
        override suspend fun list(connection: Long, account: String, root: String, namespace: String) =
            actual.list(connection, account, root, namespace).also {
                check(namespace == "progress")
                lists++
                rows += it.size
            }
        override suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String) =
            actual.get(connection, account, root, namespace, key).also { gets++ }
        override suspend fun getSnapshot(
            connection: Long,
            account: String,
            root: String,
            namespace: String,
            key: String,
        ) =
            actual.getSnapshot(connection, account, root, namespace, key).also { snapshots++ }
        override suspend fun compareAndSet(
            connection: Long,
            account: String,
            root: String,
            updates: List<StorageRecordUpdate>,
            checkActive: () -> Unit,
        ) = actual.compareAndSet(connection, account, root, updates, checkActive).also {
            check(it)
            commits++
            commitRecords += updates.size
        }
        override suspend fun replaceIfUnchanged(
            connection: Long,
            account: String,
            root: String,
            namespace: String,
            expected: StorageRecord,
            updated: StorageRecord,
            checkActive: () -> Unit,
        ) = actual.replaceIfUnchanged(connection, account, root, namespace, expected, updated, checkActive).also {
            check(it)
            acks++
        }
    }

    private class CountedBackend(val actual: LibraryStorageBackend) : LibraryStorageBackend by actual {
        var lists = 0
        var entries = 0
        var reads = 0
        var writes = 0
        var deletes = 0
        fun reset() {
            lists = 0
            entries = 0
            reads = 0
            writes = 0
            deletes = 0
        }
        override suspend fun list(path: String) = actual.list(path).also {
            lists++
            entries += it.size
        }
        override suspend fun read(entry: StorageEntry, offset: Long, length: Int) =
            actual.read(entry, offset, length).also { reads++ }
        override suspend fun write(path: String, data: InputStream, length: Long, expectedVersion: String?) {
            actual.write(path, data, length, expectedVersion)
            writes++
        }
        override suspend fun delete(entry: StorageEntry) {
            actual.delete(entry)
            deletes++
        }
    }
}
