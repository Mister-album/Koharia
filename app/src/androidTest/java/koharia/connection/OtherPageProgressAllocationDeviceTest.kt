package koharia.connection

import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import koharia.domain.storage.LibraryStorageRepository
import koharia.domain.storage.StorageRecord
import koharia.domain.storage.StorageRecordUpdate
import koharia.smanga.SmangaApi
import koharia.smanga.SmangaChapter
import koharia.smanga.SmangaReadingCoordinator
import koharia.storage.StoragePendingProgress
import koharia.storage.StorageProgress
import koharia.storage.StorageProgressRecord
import koharia.storage.StorageRecordStore
import koharia.storage.StorageSession
import koharia.storage.WebDavStorageBackend
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.CoroutineContext

class OtherPageProgressAllocationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun owner() = System.nanoTime().also {
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(it))
    }

    @Test
    fun smangaPageRecordingDoesNotReloadHistory(): Unit = runBlocking(Dispatchers.IO) {
        val id = owner()
        val actual = Injekt.get<SmangaRepository>()
        for (account in listOf("a", "b")) {
            check(actual.readStates(id, account).isEmpty() && actual.historyEvents(id, account).isEmpty())
        }
        val counts = Counts()
        var historyWrites = 0
        val counted = object : SmangaRepository by actual {
            override suspend fun readState(connectionId: Long, accountKey: String, chapterId: Long) =
                actual.readState(connectionId, accountKey, chapterId).also {
                    check(connectionId == id && accountKey == "a" && chapterId == 1L)
                    counts.points++
                    if (it != null) counts.rows++
                }
            override suspend fun putReadState(connectionId: Long, accountKey: String, state: SmangaReadState) {
                check(connectionId == id && accountKey == "a" && state.chapterId == 1L)
                actual.putReadState(connectionId, accountKey, state)
                counts.writes++
            }
            override suspend fun enqueueHistory(connectionId: Long, accountKey: String, event: SmangaHistoryEvent) {
                check(connectionId == id && accountKey == "a" && event.chapterId == 1L)
                actual.enqueueHistory(connectionId, accountKey, event)
                historyWrites++
            }
            override suspend fun readStates(connectionId: Long, accountKey: String): List<SmangaReadState> =
                error("Page must not enumerate read states")
            override suspend fun historyEvents(connectionId: Long, accountKey: String): List<SmangaHistoryEvent> =
                error("Page must not enumerate historical events")
            override suspend fun pendingReadStates(connectionId: Long, accountKey: String): List<SmangaReadState> =
                error("Held sync must not execute")
            override suspend fun pendingHistoryEvents(
                connectionId: Long,
                accountKey: String,
            ): List<SmangaHistoryEvent> =
                error("Held sync must not execute")
        }
        val gate = HeldDispatcher()
        val scope = CoroutineScope(SupervisorJob() + gate)
        val client = client()
        val api = SmangaApi(client, Json, "http://127.0.0.1:1/", "dummy", "fixture", "owned-$id")
        try {
            val foreign = SmangaReadState(1, 1, 77, 1000, false, 77, 77, true)
            actual.putReadState(id, "b", foreign)
            val coordinator = SmangaReadingCoordinator(id, "a", api, counted, scope, {}, {}) {}
            coordinator.beginSession(1)
            val chapter = SmangaChapter(1, 1, 1, "Owned book", pageCount = 1000)
            var seeded = 0
            var sequence = 0L
            for (history in listOf(1000, 10000, 50000)) {
                Injekt.get<Database>().transaction {
                    for (index in seeded until history) {
                        val key = index + 100L
                        actual.putReadState(id, "a", SmangaReadState(key, key, 1, 1000, false, 1, 1, false))
                        actual.enqueueHistory(id, "a", SmangaHistoryEvent("history-$index", key, key, key, 1, 1))
                    }
                }
                seeded = history
                val before = actual.readStates(id, "a").filter { it.chapterId != 1L }
                val beforeHistory = actual.historyEvents(id, "a").filter { it.chapterId != 1L }
                measure("smanga", history, counts, 100, 100, 100, 0) {
                    sequence++
                    coordinator.record(chapter, (sequence % 999).toInt(), 1000, sequence, false)
                }
                val saved = checkNotNull(actual.readState(id, "a", 1))
                check(saved.pageIndex == (sequence % 999).toInt() && saved.readAt == sequence)
                check(saved.revision == sequence && saved.pending)
                check(actual.readStates(id, "a").filter { it.chapterId != 1L } == before)
                check(actual.historyEvents(id, "a").filter { it.chapterId != 1L } == beforeHistory)
                val ownEvent = actual.historyEvents(id, "a").filter { it.chapterId == 1L }
                check(historyWrites == 1 && ownEvent.size == 1 && ownEvent.single().status == 0)
                check(actual.readStates(id, "b") == listOf(foreign))
            }
        } finally {
            scope.cancel()
            gate.release()
            scope.coroutineContext[Job]!!.join()
            api.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            actual.removeConnection(id)
            for (account in listOf("a", "b")) {
                check(actual.readStates(id, account).isEmpty() && actual.historyEvents(id, account).isEmpty())
            }
            cleanup("smanga")
        }
    }

    @Test
    fun networkStoragePageRecordingUsesPointSnapshots(): Unit = runBlocking(Dispatchers.IO) {
        val id = owner()
        val actual = Injekt.get<LibraryStorageRepository>()
        val counts = Counts()
        val counted = object : LibraryStorageRepository by actual {
            override suspend fun get(connection: Long, account: String, root: String, namespace: String, key: String) =
                actual.get(connection, account, root, namespace, key).also {
                    check(connection == id && account == "a" && root == "root")
                    counts.points++
                    if (it != null) counts.rows++
                }
            override suspend fun getSnapshot(
                connection: Long,
                account: String,
                root: String,
                namespace: String,
                key: String,
            ) = actual.getSnapshot(connection, account, root, namespace, key).also {
                check(connection == id && account == "a" && root == "root")
                counts.points++
                if (it != null) counts.rows++
            }
            override suspend fun compareAndSet(
                connection: Long,
                account: String,
                root: String,
                updates: List<StorageRecordUpdate>,
                checkActive: () -> Unit,
            ) = actual.compareAndSet(connection, account, root, updates, checkActive).also {
                check(it && connection == id && account == "a" && root == "root")
                counts.commits++
                counts.writes += updates.size
            }
            override suspend fun list(connection: Long, account: String, root: String, namespace: String):
                List<StorageRecord> = error("Single page persistence must not enumerate the namespace")
        }
        val session = StorageSession(id, "a", "root") { true }
        val store = StorageRecordStore(session, counted, Json)
        val control = StorageRecordStore(session, actual, Json)
        val foreign = listOf(session.copy(account = "b"), session.copy(rootIdentity = "other-root"))
            .map { StorageRecordStore(it, actual, Json) }
        for (scoped in listOf(control) + foreign) {
            for (namespace in listOf("progress", "progress-device", "progress-writer")) {
                check(scoped.list(namespace).isEmpty())
            }
        }
        val client = client()
        try {
            WebDavStorageBackend(client, "http://127.0.0.1:1/", "", "").use { backend ->
                val progress = StorageProgress(backend, store, "owned-installation")
                val held = StoragePendingProgress(StorageProgressRecord("book", "v1", "foreign", 77, 77), true, true)
                foreign.forEach { it.put("progress", "book", held, 77) }
                val foreignBefore = foreign.map { it.list("progress") }
                var seeded = 0
                var sequence = 0L
                for (history in listOf(1000, 10000, 50000)) {
                    Injekt.get<Database>().transaction {
                        for (index in seeded until history) {
                            val resource = "history-$index"
                            control.put(
                                "progress",
                                resource,
                                StoragePendingProgress(StorageProgressRecord(resource, "v1", "history", 1, 1), false),
                                1,
                            )
                        }
                    }
                    seeded = history
                    val before = control.list("progress").filter { it.key != "book" }
                    measure("storage", history, counts, 300, 300, 200, 100) {
                        sequence++
                        val event = progress.record("book", "v1", sequence, (sequence % 999).toInt(), 1000)
                        check(event.sequence == sequence)
                    }
                    val saved = checkNotNull(control.snapshot("progress", "book"))
                    val pending = Json.decodeFromString<StoragePendingProgress>(saved.payload)
                    check(pending.pending && pending.record.sequence == sequence && saved.revision == sequence)
                    check(pending.record.page == (sequence % 999).toInt() && pending.record.modifiedAt == sequence)
                    check(control.list("progress").filter { it.key != "book" } == before)
                    check(foreign.map { it.list("progress") } == foreignBefore)
                }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            actual.removeConnection(id)
            for (scoped in listOf(control) + foreign) {
                for (namespace in listOf("progress", "progress-device", "progress-writer")) {
                    check(scoped.list(namespace).isEmpty())
                }
            }
            cleanup("storage")
        }
    }

    private data class Counts(var points: Int = 0, var rows: Int = 0, var writes: Int = 0, var commits: Int = 0)
    private fun client() = OkHttpClient.Builder().addInterceptor {
        error("Page persistence must not send HTTP")
    }.build()
    private fun runtime(key: String) = checkNotNull(Debug.getRuntimeStat(key)?.toLongOrNull())

    private suspend fun measure(
        provider: String,
        history: Int,
        counts: Counts,
        points: Int,
        rows: Int,
        writes: Int,
        commits: Int,
        record: suspend () -> Unit,
    ) {
        repeat(20) { record() }
        repeat(5) { sample ->
            val before = counts.copy()
            val allocated = runtime("art.gc.bytes-allocated")
            val gc = runtime("art.gc.gc-count")
            val started = System.nanoTime()
            repeat(100) { record() }
            val elapsed = System.nanoTime() - started
            val bytes = runtime("art.gc.bytes-allocated") - allocated
            val collections = runtime("art.gc.gc-count") - gc
            check(counts.points - before.points == points && counts.rows - before.rows == rows)
            check(counts.writes - before.writes == writes && counts.commits - before.commits == commits)
            check(bytes >= 0)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "page_allocation",
                        "provider=$provider;history=$history;sample=$sample;events=100;points=$points;" +
                            "rows=$rows;writes=$writes;commits=$commits;allocated_bytes=$bytes;" +
                            "gc=$collections;elapsed_ns=$elapsed",
                    )
                },
            )
        }
    }

    private fun cleanup(provider: String) = instrumentation.sendStatus(
        0,
        Bundle().apply { putString("page_allocation_cleanup", provider) },
    )

    private class HeldDispatcher : CoroutineDispatcher() {
        private val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var released = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queued) {
                if (!released) {
                    queued += context to block
                    return
                }
            }
            Dispatchers.IO.dispatch(context, block)
        }
        fun release() {
            val pending = synchronized(queued) {
                released = true
                queued.toList().also { queued.clear() }
            }
            pending.forEach { (context, block) -> Dispatchers.IO.dispatch(context, block) }
        }
    }
}
