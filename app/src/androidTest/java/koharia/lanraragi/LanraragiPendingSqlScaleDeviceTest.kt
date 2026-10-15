package koharia.lanraragi

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionPreferences
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import tachiyomi.data.Database
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LanraragiPendingSqlScaleDeviceTest {
    @Test
    fun pendingConsumerUsesRealSqlAcrossThreeScales(): Unit = runBlocking(Dispatchers.IO) {
        for (count in listOf(1, 100, 1000)) exercise(count, false)
    }

    @Test
    fun metadataFailureRetainsUnsentRecordsForNextBatch(): Unit = runBlocking(Dispatchers.IO) {
        exercise(3, true)
    }

    private suspend fun exercise(count: Int, failure: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
        val actual = Injekt.get<LanraragiRepository>()
        val id = System.nanoTime()
        for (owner in listOf(id, id + 1)) {
            check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(owner))
            check(actual.entries(owner).isEmpty() && actual.readStates(owner).isEmpty())
        }
        var queries = 0
        var returned = 0
        var points = 0
        var writes = 0
        var applied = 0
        val measured = object : LanraragiRepository by actual {
            override suspend fun pendingReadStates(connectionId: Long): List<LanraragiReadState> {
                check(connectionId == id)
                queries++
                return actual.pendingReadStates(connectionId).also { returned += it.size }
            }
            override suspend fun readState(connectionId: Long, archiveId: String): LanraragiReadState? {
                check(connectionId == id)
                points++
                return actual.readState(connectionId, archiveId)
            }
            override suspend fun record(connectionId: Long, state: LanraragiReadState) {
                check(connectionId == id)
                actual.record(connectionId, state)
                writes++
            }
            override suspend fun readStates(connectionId: Long): List<LanraragiReadState> =
                error("Pending consumer must not enumerate confirmed history")
        }
        val role = if (failure) "failure" else "scale-$count"
        val client = OkHttpClient()
        val api = LanraragiApi("http://127.0.0.1:18931/$role/", "owned-key", client, Json)
        fun archive(index: Int) = index.toString(16).padStart(40, '0')
        suspend fun flush() = synchronizeLanraragiProgress(
            id,
            measured,
            api::archive,
            api::pushProgress,
            api::clearNew,
            { applied++ },
            isCurrent = { !Injekt.get<ConnectionPreferences>().isKnownConnectionId(id) },
        )
        try {
            Injekt.get<Database>().transaction {
                repeat(1000) {
                    actual.record(id, LanraragiReadState("history-$it", 3, 10, 1, pending = false))
                }
                for (index in 1..count) {
                    actual.record(id, LanraragiReadState(archive(index), if (index % 2 == 0) 9 else 3, 10, 1000))
                }
                actual.record(id, LanraragiReadState("local-unread", 0, 10, 1000, localUnread = true, pending = false))
                actual.record(id, LanraragiReadState("held", 3, 10, 1000, requiresConfirmation = true))
                actual.record(id + 1, LanraragiReadState(archive(1), 7, 10, 1000))
            }
            val queued = actual.pendingReadStates(id)
            check(queued.size == count)
            val keys = queued.map { it.archiveId }.toSet()
            val preserved = actual.readStates(id).filter { it.archiveId !in keys }
            val foreign = actual.readStates(id + 1)
            val started = System.nanoTime()
            if (failure) {
                val error = runCatching { flush() }.exceptionOrNull()
                check(error is LanraragiException && error.reason == LanraragiException.Reason.SERVER)
                check(actual.pendingReadStates(id).map { it.archiveId }.toSet() == setOf(archive(2), archive(3)))
            }
            flush()
            val elapsed = (System.nanoTime() - started) / 1_000_000
            check(queries == if (failure) 2 else 1)
            check(returned == if (failure) 5 else count)
            check(points == 4 * count + if (failure) 1 else 0)
            check(writes == count && applied == count)
            check(actual.pendingReadStates(id).isEmpty())
            for (before in queued) {
                check(
                    actual.readState(id, before.archiveId) ==
                        before.copy(pending = false, revision = before.revision + 1),
                )
            }
            check(actual.readStates(id).filter { it.archiveId !in keys } == preserved)
            check(actual.readStates(id + 1) == foreign)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString(
                        "lanraragi_consumer",
                        "pending=$count;failure=$failure;queries=$queries;rows=$returned;points=$points;" +
                            "writes=$writes;apply=$applied;elapsed_ms=$elapsed",
                    )
                },
            )
        } finally {
            api.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            actual.remove(id)
            actual.remove(id + 1)
            check(actual.readStates(id).isEmpty() && actual.readStates(id + 1).isEmpty())
            instrumentation.sendStatus(0, Bundle().apply { putString("lanraragi_consumer_cleanup", role) })
        }
    }
}
