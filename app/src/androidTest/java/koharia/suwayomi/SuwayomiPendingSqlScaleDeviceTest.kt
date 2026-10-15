package koharia.suwayomi

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionReadStatus
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class SuwayomiPendingSqlScaleDeviceTest {
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
        val actual = Injekt.get<SuwayomiRepository>()
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
        val measured = object : SuwayomiRepository by actual {
            override suspend fun pendingOperations(connectionId: Long, account: String): List<SuwayomiOperation> {
                check(connectionId == id && account == "a")
                queries.incrementAndGet()
                return actual.pendingOperations(connectionId, account).also { returned.addAndGet(it.size) }
            }
            override suspend fun operation(connectionId: Long, account: String, key: String): SuwayomiOperation? {
                check(connectionId == id && account == "a")
                points.incrementAndGet()
                return actual.operation(connectionId, account, key)
            }
            override suspend fun putOperation(connectionId: Long, account: String, operation: SuwayomiOperation) {
                check(connectionId == id && account == "a")
                actual.putOperation(connectionId, account, operation)
                puts.incrementAndGet()
            }
            override suspend fun acknowledge(connectionId: Long, account: String, operation: SuwayomiOperation) {
                check(connectionId == id && account == "a")
                actual.acknowledge(connectionId, account, operation)
                acks.incrementAndGet()
            }
            override suspend fun operations(connectionId: Long, account: String): List<SuwayomiOperation> =
                error("Pending consumer must not enumerate confirmed operations")
        }
        val gate = PreparationDispatcher()
        val scope = CoroutineScope(SupervisorJob() + gate)
        val role = if (failure) "failure" else "scale-$count"
        val client = OkHttpClient()
        val json = Json { encodeDefaults = true }
        val api = SuwayomiApi(client, json, "http://127.0.0.1:18921/$role/", SuwayomiAuthMode.NONE, "", "")
        try {
            fun state(chapter: Int) = SuwayomiReadState(chapter, chapter, 5, 10, false, 1000)
            val foreign = SuwayomiOperation("1", json.encodeToString(state(1)), 9, true)
            Injekt.get<Database>().transaction {
                repeat(1000) {
                    val chapter = it + 10000
                    actual.putOperation(
                        id,
                        "a",
                        SuwayomiOperation(chapter.toString(), json.encodeToString(state(chapter)), 1, false),
                    )
                }
                actual.putOperation(id, "b", foreign)
                actual.putOperation(neighbor, "a", foreign)
            }
            val confirmed = actual.operations(id, "a")
            for (chapter in 1..count) {
                actual.putOperation(
                    id,
                    "a",
                    SuwayomiOperation(chapter.toString(), json.encodeToString(state(chapter)), 1, false),
                )
            }
            val coordinator = SuwayomiReadingCoordinator(SuwayomiIdentity(id, "a"), measured, api, json, scope, {
                check(!connections.isKnownConnectionId(id))
            }) { applied.incrementAndGet() }
            for (chapter in 1..count) {
                check(coordinator.markRead(chapter, chapter, chapter % 2 == 0) == ConnectionReadStatus.PENDING)
            }
            val queued = actual.pendingOperations(id, "a")
            check(queued.size == count && queries.get() == 0 && points.get() == count && puts.get() == count)
            check(queued.all { it.revision > 1L })
            val started = System.nanoTime()
            gate.release()
            withTimeout(180_000) {
                while (acks.get() != count || scope.coroutineContext[Job]!!.children.any()) delay(25)
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            check(
                queries.get() == if (failure) {
                    5
                } else if (count == 1) {
                    2
                } else {
                    4
                },
            )
            check(returned.get() == if (failure) 4 else count)
            check(points.get() == 3 * count && puts.get() == count && acks.get() == count)
            check(applied.get() == 2 * count)
            check(actual.pendingOperations(id, "a").isEmpty())
            for (operation in queued) {
                val before = json.decodeFromString<SuwayomiReadState>(operation.payload)
                val remote = SuwayomiRemoteState(before.page, 10, before.read, 2)
                val after = before.copy(baseline = remote, explicitRead = null, initial = false)
                check(
                    actual.operation(id, "a", operation.key) ==
                        operation.copy(payload = json.encodeToString(after), pending = false),
                )
            }
            val queuedKeys = queued.map { it.key }.toSet()
            check(actual.operations(id, "a").filter { it.key !in queuedKeys } == confirmed)
            check(actual.operations(id, "b") == listOf(foreign))
            check(actual.operations(neighbor, "a") == listOf(foreign))
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "suwayomi_scale",
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
            instrumentation.sendStatus(0, Bundle().apply { putString("suwayomi_scale_cleanup", role) })
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
