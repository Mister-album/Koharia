package koharia.kavita

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.domain.kavita.KavitaOperation
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class KavitaPendingSqlScaleDeviceTest {
    @Test
    fun batchReadAndUnreadUseRealSqlAcrossThreeScales(): Unit = runBlocking(Dispatchers.IO) {
        for (count in listOf(1, 100, 1000)) exercise(count, false)
    }

    @Test
    fun readFailureRetainsPendingAndRetriesOnlyUnconfirmedChapters(): Unit = runBlocking(Dispatchers.IO) {
        exercise(3, true)
    }

    private suspend fun exercise(count: Int, failure: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val actual = Injekt.get<KavitaRepository>()
        val id = System.nanoTime()
        val neighbor = id + 1
        val connections = Injekt.get<ConnectionPreferences>()
        for (owner in listOf(id, neighbor)) {
            check(!connections.isKnownConnectionId(owner))
            check(actual.operations(owner, "a").isEmpty() && actual.operations(owner, "b").isEmpty())
        }
        val queries = AtomicInteger()
        val returned = AtomicInteger()
        val points = AtomicInteger()
        val puts = AtomicInteger()
        val acks = AtomicInteger()
        val applied = AtomicInteger()
        val measured = object : KavitaRepository by actual {
            override suspend fun pendingOperations(connectionId: Long, account: String): List<KavitaOperation> {
                check(connectionId == id && account == "a")
                queries.incrementAndGet()
                return actual.pendingOperations(connectionId, account).also { returned.addAndGet(it.size) }
            }
            override suspend fun operation(connectionId: Long, account: String, key: String): KavitaOperation? {
                check(connectionId == id && account == "a")
                points.incrementAndGet()
                return actual.operation(connectionId, account, key)
            }
            override suspend fun putOperation(connectionId: Long, account: String, operation: KavitaOperation) {
                check(connectionId == id && account == "a")
                actual.putOperation(connectionId, account, operation)
                puts.incrementAndGet()
            }
            override suspend fun acknowledge(connectionId: Long, account: String, key: String, revision: Long) {
                check(connectionId == id && account == "a")
                actual.acknowledge(connectionId, account, key, revision)
                acks.incrementAndGet()
            }
            override suspend fun operations(connectionId: Long, account: String): List<KavitaOperation> =
                error("Pending consumer must not enumerate confirmed operations")
        }
        val gate = PreparationDispatcher()
        val scope = CoroutineScope(SupervisorJob() + gate)
        val role = if (failure) "failure" else "scale-$count"
        val client = OkHttpClient()
        val api = KavitaApiClient(client, "http://127.0.0.1:18911/", role, "owned-$id")
        try {
            fun ref(chapter: Long) = KavitaChapterRef(1, chapter, chapter, chapter, 1)
            fun state(chapter: Long) = KavitaReadingState(
                ref(chapter),
                KavitaProgress(1, chapter, chapter, chapter, 5, lastModifiedUtc = "1970-01-01T00:00:00Z"),
                10,
            )
            val foreign = KavitaOperation("progress/1", api.json.encodeToString(state(1)), 9, true)
            Injekt.get<Database>().transaction {
                repeat(1000) {
                    val chapter = it + 10000L
                    actual.putOperation(
                        id,
                        "a",
                        KavitaOperation("progress/$chapter", api.json.encodeToString(state(chapter)), 1, false),
                    )
                }
                actual.putOperation(id, "b", foreign)
                actual.putOperation(neighbor, "a", foreign)
            }
            val confirmed = actual.operations(id, "a")
            val coordinator = KavitaReadingCoordinator(id, "a", measured, api, scope, {
                check(!connections.isKnownConnectionId(id))
            }) { applied.incrementAndGet() }
            for (chapter in 1L..count) {
                check(coordinator.record(ref(chapter), 10, 10, 1000, explicit = true, unread = chapter % 2 == 1L))
            }
            val queued = actual.pendingOperations(id, "a")
            check(queued.size == count && queries.get() == 0 && points.get() == count && puts.get() == count)
            check(queued.all { it.revision == 1L })
            val started = System.nanoTime()
            gate.release()
            withTimeout(180_000) {
                while (acks.get() != count || scope.coroutineContext[Job]!!.children.any()) delay(25)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            check(queries.get() == if (failure) 2 else 1)
            check(returned.get() == if (failure) 5 else count)
            check(points.get() == 2 * count && puts.get() == count && acks.get() == count)
            check(applied.get() == count)
            check(actual.pendingOperations(id, "a").isEmpty())
            check(queued.all { actual.operation(id, "a", it.key) == it.copy(pending = false) })
            val queuedKeys = queued.map { it.key }.toSet()
            check(actual.operations(id, "a").filter { it.key !in queuedKeys } == confirmed)
            check(actual.operations(id, "b") == listOf(foreign))
            check(actual.operations(neighbor, "a") == listOf(foreign))
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "kavita_scale",
                        "pending=$count;failure=$failure;queries=${queries.get()};rows=${returned.get()};" +
                            "points=${points.get()};put=${puts.get()};ack=${acks.get()};apply=${applied.get()};" +
                            "elapsed_ms=$elapsed",
                    )
                },
            )
        } finally {
            scope.cancel()
            gate.release()
            scope.coroutineContext[Job]!!.join()
            api.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            actual.removeConnection(id)
            actual.removeConnection(neighbor)
            check(actual.operations(id, "a").isEmpty() && actual.operations(id, "b").isEmpty())
            check(actual.operations(neighbor, "a").isEmpty())
            instrumentation.sendStatus(0, Bundle().apply { putString("kavita_scale_cleanup", role) })
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
