package koharia.smanga

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionReadStatus
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class SmangaReadStatusSqlScaleDeviceTest {
    @Test
    fun batchReadAndUnreadUseBoundedSqlAcrossThreeScales(): Unit = runBlocking(Dispatchers.IO) {
        for (count in listOf(1, 100, 1000)) exercise(count, failure = false)
    }

    @Test
    fun batchReadFailureRetriesOnlyUnconfirmedOwnerState(): Unit = runBlocking(Dispatchers.IO) {
        exercise(3, failure = true)
    }

    private suspend fun exercise(count: Int, failure: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val actual = Injekt.get<SmangaRepository>()
        val database = Injekt.get<Database>()
        val connections = Injekt.get<ConnectionPreferences>()
        val id = System.nanoTime()
        val neighbor = id + 1
        for (owner in listOf(id, neighbor)) {
            check(!connections.isKnownConnectionId(owner))
            for (account in listOf("a", "b")) {
                check(actual.readStates(owner, account).isEmpty() && actual.historyEvents(owner, account).isEmpty())
            }
        }
        val pendingQueries = AtomicInteger()
        val historyQueries = AtomicInteger()
        val returned = AtomicInteger()
        val points = AtomicInteger()
        val puts = AtomicInteger()
        val acknowledgements = AtomicInteger()
        val applied = AtomicInteger()
        val measured = object : SmangaRepository by actual {
            override suspend fun pendingReadStates(connectionId: Long, accountKey: String): List<SmangaReadState> {
                check(connectionId == id && accountKey == "a")
                pendingQueries.incrementAndGet()
                return actual.pendingReadStates(connectionId, accountKey).also { returned.addAndGet(it.size) }
            }
            override suspend fun pendingHistoryEvents(
                connectionId: Long,
                accountKey: String,
            ): List<SmangaHistoryEvent> {
                check(connectionId == id && accountKey == "a")
                historyQueries.incrementAndGet()
                return actual.pendingHistoryEvents(connectionId, accountKey).also { check(it.isEmpty()) }
            }
            override suspend fun readState(connectionId: Long, accountKey: String, chapterId: Long): SmangaReadState? {
                check(connectionId == id && accountKey == "a")
                points.incrementAndGet()
                return actual.readState(connectionId, accountKey, chapterId)
            }
            override suspend fun putReadState(connectionId: Long, accountKey: String, state: SmangaReadState) {
                check(connectionId == id && accountKey == "a")
                actual.putReadState(connectionId, accountKey, state)
                puts.incrementAndGet()
            }
            override suspend fun acknowledgeReadState(
                connectionId: Long,
                accountKey: String,
                chapterId: Long,
                revision: Long,
            ): Boolean {
                check(connectionId == id && accountKey == "a")
                return actual.acknowledgeReadState(connectionId, accountKey, chapterId, revision).also {
                    check(it)
                    acknowledgements.incrementAndGet()
                }
            }
            override suspend fun readStates(connectionId: Long, accountKey: String): List<SmangaReadState> =
                error("Producer and consumer must not load confirmed state")
            override suspend fun historyEvents(connectionId: Long, accountKey: String): List<SmangaHistoryEvent> =
                error("Marking must not load history")
        }
        val gate = PreparationDispatcher()
        val scope = CoroutineScope(SupervisorJob() + gate)
        val role = if (failure) "failure" else "scale-$count"
        val api = SmangaApi(OkHttpClient(), Json, "http://127.0.0.1:18891/", role, "fixture-229", "owned-$id")
        val foreign = SmangaReadState(1, 1, 4, 10, false, 91, 1, true)
        try {
            database.transaction {
                repeat(1000) { n ->
                    val key = n + 10_000L
                    actual.putReadState(id, "a", SmangaReadState(key, key, 1, 10, false, 1, 1, false))
                }
                actual.putReadState(id, "b", foreign)
                actual.putReadState(neighbor, "a", foreign)
            }
            val before = actual.readStates(id, "a")
            val coordinator = SmangaReadingCoordinator(
                id,
                "a",
                api,
                measured,
                scope,
                checkSession = { check(!connections.isKnownConnectionId(id)) },
                checkRemoteWrite = { check(!connections.isKnownConnectionId(id)) },
                applyState = { applied.incrementAndGet() },
            )
            val start = System.nanoTime()
            for (n in 1..count) {
                check(
                    coordinator.setRead(
                        SmangaChapter(n.toLong(), n.toLong(), 30, "Owned", pageCount = 10),
                        n % 2 == 0,
                    ) ==
                        ConnectionReadStatus.PENDING,
                )
            }
            val queued = actual.pendingReadStates(id, "a")
            check(queued.size == count)
            check(pendingQueries.get() == 0 && acknowledgements.get() == 0)
            for (row in queued) {
                check(row.completed == (row.chapterId % 2L == 0L))
                check(row.explicitUnread == !row.completed && row.pending)
                check(row.pageIndex == if (row.completed) 9 else 0)
            }
            gate.release()
            withTimeout(180_000) {
                while (acknowledgements.get() != count ||
                    scope.coroutineContext[Job]!!.children.any { it.isActive }
                ) {
                    delay(20)
                }
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            check(actual.pendingReadStates(id, "a").isEmpty())
            check(actual.readStates(id, "a").filter { it.chapterId >= 10_000 } == before)
            check(
                actual.readStates(id, "a").filter {
                    it.chapterId < 10_000
                } == queued.map { it.copy(pending = false) },
            )
            check(actual.readStates(id, "b") == listOf(foreign) && actual.readStates(neighbor, "a") == listOf(foreign))
            check(points.get() == 3 * count && puts.get() == count && applied.get() == 2 * count)
            check(pendingQueries.get() == if (failure) 2 else 1)
            check(historyQueries.get() == pendingQueries.get())
            check(returned.get() == if (failure) count + 1 else count)
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "mark_scale",
                        "role=$role;pending=$count;history=1000;queries=${pendingQueries.get()};rows=${returned.get()};points=${points.get()};puts=${puts.get()};ack=${acknowledgements.get()};applied=${applied.get()};elapsedMs=$elapsed",
                    )
                },
            )
        } finally {
            scope.cancel()
            gate.release()
            scope.coroutineContext[Job]!!.join()
            api.close()
            actual.removeConnection(id)
            actual.removeConnection(neighbor)
            check(actual.readStates(id, "a").isEmpty() && actual.readStates(id, "b").isEmpty())
            check(actual.readStates(neighbor, "a").isEmpty())
        }
    }

    private class PreparationDispatcher : CoroutineDispatcher() {
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
            val ready = synchronized(queued) {
                released = true
                queued.toList().also { queued.clear() }
            }
            ready.forEach { (context, block) -> Dispatchers.IO.dispatch(context, block) }
        }
    }
}
