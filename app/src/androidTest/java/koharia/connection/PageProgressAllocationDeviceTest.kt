package koharia.connection

import android.content.Context
import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.kavita.KavitaApiClient
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaReadingCoordinator
import koharia.kavita.KavitaReadingState
import koharia.source.lanraragi.LanraragiSource
import koharia.suwayomi.SuwayomiApi
import koharia.suwayomi.SuwayomiAuthMode
import koharia.suwayomi.SuwayomiIdentity
import koharia.suwayomi.SuwayomiReadState
import koharia.suwayomi.SuwayomiReadingCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.CoroutineContext

class PageProgressAllocationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun owner(): Long {
        check(context.packageName == "app.koharia.dev.devicefixture")
        return System.nanoTime().also { id ->
            check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
            check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id + 1))
        }
    }

    @Test
    fun kavitaFixedBookPageRecordingHasBoundedMaterialization(): Unit = runBlocking(Dispatchers.IO) {
        val id = owner()
        val actual = Injekt.get<KavitaRepository>()
        check(actual.operations(id, "a").isEmpty() && actual.operations(id, "b").isEmpty())
        val counters = Counters()
        val counted = object : KavitaRepository by actual {
            override suspend fun operation(connectionId: Long, account: String, key: String): KavitaOperation? {
                check(connectionId == id && account == "a" && key == "progress/1")
                counters.points++
                return actual.operation(connectionId, account, key).also { if (it != null) counters.rows++ }
            }
            override suspend fun putOperation(connectionId: Long, account: String, operation: KavitaOperation) {
                check(connectionId == id && account == "a" && operation.key == "progress/1")
                actual.putOperation(connectionId, account, operation)
                counters.writes++
            }
            override suspend fun operations(connectionId: Long, account: String): List<KavitaOperation> =
                error("Page recording must not load historical operations")
            override suspend fun pendingOperations(connectionId: Long, account: String): List<KavitaOperation> =
                error("Held sync must not enter the page recording window")
        }
        val gate = HeldDispatcher()
        val scope = CoroutineScope(SupervisorJob() + gate)
        val client = noNetworkClient()
        val api = KavitaApiClient(client, "http://127.0.0.1:1/", "dummy", "owned-$id")
        try {
            val foreign = KavitaOperation("progress/1", "foreign", 77, true)
            actual.putOperation(id, "b", foreign)
            val coordinator = KavitaReadingCoordinator(id, "a", counted, api, scope, {}) {}
            var seeded = 0
            var sequence = 0L
            for (history in listOf(1000, 10000, 50000)) {
                Injekt.get<Database>().transaction {
                    for (index in seeded until history) {
                        actual.putOperation(id, "a", KavitaOperation("history/$index", "{}", 1, false))
                    }
                }
                seeded = history
                val before = actual.operations(id, "a").filter { it.key != "progress/1" }
                measure("kavita", history, counters) {
                    sequence++
                    check(coordinator.record(KavitaChapterRef(1, 1, 1, 1, 1), (sequence % 999).toInt(), 1000, sequence))
                }
                val saved = checkNotNull(actual.operation(id, "a", "progress/1"))
                val state = api.json.decodeFromString<KavitaReadingState>(saved.payload)
                check(saved.pending && saved.revision == sequence && state.progress.pageNum == (sequence % 999).toInt())
                check(actual.operations(id, "a").filter { it.key != "progress/1" } == before)
                check(actual.operations(id, "b") == listOf(foreign))
            }
        } finally {
            scope.cancel()
            gate.release()
            scope.coroutineContext[Job]!!.join()
            api.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            actual.removeConnection(id)
            check(actual.operations(id, "a").isEmpty() && actual.operations(id, "b").isEmpty())
            reportCleanup("kavita")
        }
    }

    @Test
    fun suwayomiFixedBookPageRecordingHasBoundedMaterialization(): Unit = runBlocking(Dispatchers.IO) {
        val id = owner()
        val actual = Injekt.get<SuwayomiRepository>()
        check(actual.operations(id, "a").isEmpty() && actual.operations(id, "b").isEmpty())
        val counters = Counters()
        val counted = object : SuwayomiRepository by actual {
            override suspend fun operation(connectionId: Long, account: String, key: String): SuwayomiOperation? {
                check(connectionId == id && account == "a" && key == "1")
                counters.points++
                return actual.operation(connectionId, account, key).also { if (it != null) counters.rows++ }
            }
            override suspend fun putOperation(connectionId: Long, account: String, operation: SuwayomiOperation) {
                check(connectionId == id && account == "a" && operation.key == "1")
                actual.putOperation(connectionId, account, operation)
                counters.writes++
            }
            override suspend fun operations(connectionId: Long, account: String): List<SuwayomiOperation> =
                error("Page recording must not load historical operations")
            override suspend fun pendingOperations(connectionId: Long, account: String): List<SuwayomiOperation> =
                error("Held sync must not enter the page recording window")
        }
        val gate = HeldDispatcher()
        val scope = CoroutineScope(SupervisorJob() + gate)
        val client = noNetworkClient()
        val json = Json { encodeDefaults = true }
        val api = SuwayomiApi(client, json, "http://127.0.0.1:1/", SuwayomiAuthMode.NONE, "", "")
        try {
            val foreign = SuwayomiOperation("1", "foreign", 77, true)
            actual.putOperation(id, "b", foreign)
            val coordinator = SuwayomiReadingCoordinator(SuwayomiIdentity(id, "a"), counted, api, json, scope, {}) {}
            var seeded = 0
            var sequence = 0L
            var previousRevision = 0L
            for (history in listOf(1000, 10000, 50000)) {
                Injekt.get<Database>().transaction {
                    for (index in seeded until history) {
                        actual.putOperation(id, "a", SuwayomiOperation("history/$index", "{}", 1, false))
                    }
                }
                seeded = history
                val before = actual.operations(id, "a").filter { it.key != "1" }
                measure("suwayomi", history, counters) {
                    sequence++
                    coordinator.record(1, 1, (sequence % 999).toInt(), 1000, sequence, false)
                }
                val saved = checkNotNull(actual.operation(id, "a", "1"))
                val state = json.decodeFromString<SuwayomiReadState>(saved.payload)
                check(saved.pending && saved.revision > previousRevision)
                check(state.page == (sequence % 999).toInt() && state.readAt == sequence)
                previousRevision = saved.revision
                check(actual.operations(id, "a").filter { it.key != "1" } == before)
                check(actual.operations(id, "b") == listOf(foreign))
            }
        } finally {
            scope.cancel()
            gate.release()
            scope.coroutineContext[Job]!!.join()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            actual.removeConnection(id)
            check(actual.operations(id, "a").isEmpty() && actual.operations(id, "b").isEmpty())
            reportCleanup("suwayomi")
        }
    }

    @Test
    fun lanraragiFixedBookPageRecordingHasBoundedMaterialization(): Unit = runBlocking(Dispatchers.IO) {
        val id = owner()
        val actual = Injekt.get<LanraragiRepository>()
        check(actual.readStates(id).isEmpty() && actual.readStates(id + 1).isEmpty())
        val prefsName = "source_$id"
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        check(prefs.all.isEmpty())
        val counters = Counters()
        val counted = object : LanraragiRepository by actual {
            override suspend fun readState(connectionId: Long, archiveId: String): LanraragiReadState? {
                check(connectionId == id && archiveId == "book")
                counters.points++
                return actual.readState(connectionId, archiveId).also { if (it != null) counters.rows++ }
            }
            override suspend fun record(connectionId: Long, state: LanraragiReadState) {
                check(connectionId == id && state.archiveId == "book")
                actual.record(connectionId, state)
                counters.writes++
            }
            override suspend fun readStates(connectionId: Long): List<LanraragiReadState> =
                error("Page recording must not load historical states")
            override suspend fun pendingReadStates(connectionId: Long): List<LanraragiReadState> =
                error("Unregistered Source must not sync")
        }
        val source = LanraragiSource(context, LibraryConnectionProfile(id, "lanraragi", "Owned page benchmark"))
        LanraragiSource::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(source, counted)
        try {
            source.preferences.save("http://127.0.0.1:1", "dummy")
            val foreign = LanraragiReadState("book", 77, 1000, 77)
            actual.record(id + 1, foreign)
            val foreignBefore = actual.readStates(id + 1)
            var seeded = 0
            var sequence = 0L
            for (history in listOf(1000, 10000, 50000)) {
                Injekt.get<Database>().transaction {
                    for (index in seeded until history) {
                        actual.record(id, LanraragiReadState("history-$index", 1, 1000, 1, pending = false))
                    }
                }
                seeded = history
                val before = actual.readStates(id).filter { it.archiveId != "book" }
                measure("lanraragi", history, counters) {
                    sequence++
                    source.recordLocalPageProgress(
                        "/lanraragi/$id/archive/book",
                        (sequence % 999).toInt(),
                        1000,
                        sequence,
                        false,
                    )
                }
                val saved = checkNotNull(actual.readState(id, "book"))
                check(saved.pending && saved.revision == sequence && saved.readAt == sequence)
                check(saved.pageIndex == (sequence % 999).toInt())
                check(actual.readStates(id).filter { it.archiveId != "book" } == before)
                check(actual.readStates(id + 1) == foreignBefore)
            }
        } finally {
            source.close()
            actual.remove(id)
            actual.remove(id + 1)
            check(actual.readStates(id).isEmpty() && actual.readStates(id + 1).isEmpty())
            check(prefs.edit().clear().commit())
            context.deleteSharedPreferences(prefsName)
            reportCleanup("lanraragi")
        }
    }

    private fun noNetworkClient() = OkHttpClient.Builder().addInterceptor {
        error("Local page recording must not send HTTP")
    }.build()

    private class Counters(var points: Int = 0, var rows: Int = 0, var writes: Int = 0)

    private suspend fun measure(provider: String, history: Int, counters: Counters, record: suspend () -> Unit) {
        repeat(20) { record() }
        repeat(5) { sample ->
            val before = counters.copy()
            val allocated = runtime("art.gc.bytes-allocated")
            val gc = runtime("art.gc.gc-count")
            val started = System.nanoTime()
            repeat(100) { record() }
            val elapsed = System.nanoTime() - started
            val bytes = runtime("art.gc.bytes-allocated") - allocated
            val collections = runtime("art.gc.gc-count") - gc
            check(counters.points - before.points == 100 && counters.rows - before.rows == 100)
            check(counters.writes - before.writes == 100 && bytes >= 0)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "page_allocation",
                        "provider=$provider;history=$history;sample=$sample;events=100;" +
                            "points=100;rows=100;writes=100;" +
                            "allocated_bytes=$bytes;gc=$collections;elapsed_ns=$elapsed",
                    )
                },
            )
        }
    }

    private fun Counters.copy() = Counters(points, rows, writes)
    private fun runtime(key: String) = checkNotNull(Debug.getRuntimeStat(key)?.toLongOrNull())
    private fun reportCleanup(provider: String) = instrumentation.sendStatus(
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
