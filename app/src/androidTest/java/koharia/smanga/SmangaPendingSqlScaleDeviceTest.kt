package koharia.smanga

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

class SmangaPendingSqlScaleDeviceTest {
    @Test
    fun pendingHistoryUsesBoundedRealSqlAcrossThreeScales(): Unit = runBlocking(Dispatchers.IO) {
        for (count in listOf(1, 100, 1000)) exercise(count, failure = false)
    }

    @Test
    fun realSqlBatchRetainsMissingAndForeignHistoryThroughReadFailure(): Unit = runBlocking(Dispatchers.IO) {
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
            check(actual.historyEvents(owner, "a").isEmpty() && actual.readStates(owner, "a").isEmpty())
            check(actual.historyEvents(owner, "b").isEmpty() && actual.readStates(owner, "b").isEmpty())
        }
        val queries = AtomicInteger()
        val pendingStates = AtomicInteger()
        val returned = AtomicInteger()
        val visits = AtomicInteger()
        val points = AtomicInteger()
        val writes = AtomicInteger()
        val sent = AtomicInteger()
        val measured = object : SmangaRepository by actual {
            override suspend fun pendingReadStates(connectionId: Long, accountKey: String): List<SmangaReadState> {
                check(connectionId == id && accountKey == "a")
                pendingStates.incrementAndGet()
                return actual.pendingReadStates(connectionId, accountKey).also { check(it.isEmpty()) }
            }
            override suspend fun pendingHistoryEvents(
                connectionId: Long,
                accountKey: String,
            ): List<SmangaHistoryEvent> {
                check(connectionId == id && accountKey == "a")
                queries.incrementAndGet()
                val rows = actual.pendingHistoryEvents(connectionId, accountKey)
                returned.addAndGet(rows.size)
                return object : AbstractList<SmangaHistoryEvent>() {
                    override val size: Int get() = rows.size
                    override fun get(index: Int): SmangaHistoryEvent {
                        visits.incrementAndGet()
                        return rows[index]
                    }
                }
            }
            override suspend fun historyEvent(
                connectionId: Long,
                accountKey: String,
                eventId: String,
            ): SmangaHistoryEvent? {
                check(connectionId == id && accountKey == "a")
                points.incrementAndGet()
                return actual.historyEvent(connectionId, accountKey, eventId)
            }
            override suspend fun updateHistoryStatus(
                connectionId: Long,
                accountKey: String,
                eventId: String,
                status: Int,
            ) {
                check(connectionId == id && accountKey == "a")
                actual.updateHistoryStatus(connectionId, accountKey, eventId, status)
                writes.incrementAndGet()
                if (status == 2) sent.incrementAndGet()
            }
            override suspend fun historyEvents(connectionId: Long, accountKey: String): List<SmangaHistoryEvent> =
                error("Batch must not load confirmed history")
            override suspend fun readStates(connectionId: Long, accountKey: String): List<SmangaReadState> =
                error("Batch must not load confirmed read states")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val role = if (failure) "failure" else "scale-$count"
        val api = SmangaApi(OkHttpClient(), Json, "http://127.0.0.1:18881/", role, "fixture-228", "owned-$id")
        val foreign = SmangaHistoryEvent("pending-1", 1, 1, 30, 91)
        val missing = SmangaHistoryEvent("missing", 3, 999, 30, 200)
        try {
            database.transaction {
                repeat(1000) { n ->
                    val key = n + 10_000L
                    actual.enqueueHistory(id, "a", SmangaHistoryEvent("confirmed-$n", key, key, 30, 1, status = 2))
                }
                for (n in 1..count) {
                    actual.enqueueHistory(
                        id,
                        "a",
                        SmangaHistoryEvent("pending-$n", n.toLong(), n.toLong(), 30, 100),
                    )
                }
                actual.enqueueHistory(id, "b", foreign)
                actual.enqueueHistory(neighbor, "a", foreign)
                if (failure) actual.enqueueHistory(id, "a", missing)
            }
            val before = actual.historyEvents(id, "a").filter { it.status == 2 }
            val coordinator = SmangaReadingCoordinator(
                id,
                "a",
                api,
                measured,
                scope,
                checkSession = { check(!connections.isKnownConnectionId(id)) },
                checkRemoteWrite = { check(!connections.isKnownConnectionId(id)) },
                applyState = { error("History-only batch must not alter progress") },
            )
            val start = System.nanoTime()
            coordinator.retryPending()
            withTimeout(180_000) {
                while (sent.get() != count || scope.coroutineContext[Job]!!.children.any { it.isActive }) delay(20)
            }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val rows = actual.historyEvents(id, "a")
            check(rows.filter { it.id.startsWith("confirmed-") } == before)
            check(rows.filter { it.id.startsWith("pending-") }.size == count)
            check(rows.filter { it.id.startsWith("pending-") }.all { it.status == 2 })
            check(actual.historyEvents(id, "b") == listOf(foreign))
            check(actual.historyEvents(neighbor, "a") == listOf(foreign))
            check(points.get() == count && writes.get() == 2 * count)
            if (failure) {
                check(actual.pendingHistoryEvents(id, "a") == listOf(missing))
                check(queries.get() == 2 && pendingStates.get() == 2 && returned.get() == 6)
            } else {
                check(actual.pendingHistoryEvents(id, "a").isEmpty())
                check(queries.get() == 1 && pendingStates.get() == 1 && returned.get() == count)
            }
            check(visits.get() <= 3 * returned.get())
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "sql_scale",
                        "role=$role;pending=$count;history=1000;queries=${queries.get()};rows=${returned.get()};visits=${visits.get()};points=${points.get()};writes=${writes.get()};elapsedMs=$elapsed",
                    )
                },
            )
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            api.close()
            actual.removeConnection(id)
            actual.removeConnection(neighbor)
            check(actual.historyEvents(id, "a").isEmpty() && actual.historyEvents(id, "b").isEmpty())
            check(actual.historyEvents(neighbor, "a").isEmpty())
        }
    }
}
