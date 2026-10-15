package koharia.kavita

import com.sun.net.httpserver.HttpServer
import koharia.domain.kavita.KavitaOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KavitaRemoteAcceptanceTest {
    private val ref = KavitaChapterRef(1, 2, 3, 4, 3)

    @Test fun explicitAdoptionDoesNotReplayPendingProgressAfterRestart() = runBlocking {
        fixture {
            reading.record(ref, 8, 10, 800)
            reading.review(ref, 10)
            reading.accept(ref, 1, 10, 100, anchorOverride = "anchor")
            assertFalse(repository.operation(1, "a", "progress/4")!!.pending)
            assertEquals("anchor", reading.cached(4)!!.progress.bookScrollId)
            coordinator().flush()
            assertEquals(0, writes)
        }
    }

    @Test fun remoteChangeOrLocalChangeDuringReviewPreservesPendingIntent() = runBlocking {
        for (change in listOf("remote", "local", "removed", "paused")) {
            fixture {
                reading.record(ref, 8, 10, 800)
                reading.review(ref, 10)
                val before = repository.operation(1, "a", "progress/4")!!
                var paused = false
                onRead = {
                    when (change) {
                        "remote" -> remote = remote.copy(pageNum = 2)
                        "local" -> reading.record(ref, 9, 10, 900)
                        "removed" -> repository.removeConnection(1)
                        "paused" -> paused = true
                    }
                }
                assertTrue(
                    runCatching {
                        reading.accept(ref, 1, 10, 100, checkAcceptance = { check(!paused) })
                    }.isFailure,
                    change,
                )
                val after = repository.operation(1, "a", "progress/4")
                when (change) {
                    "local" -> {
                        assertTrue(after!!.pending)
                        assertEquals(9, reading.cached(4)!!.progress.pageNum)
                    }
                    "removed" -> assertEquals(null, after)
                    else -> assertEquals(before, after)
                }
                assertEquals(0, writes)
            }
        }
    }

    @Test fun adoptionWaitsForInFlightUploadThenRejectsObsoleteRemoteChoice() = runBlocking {
        fixture {
            reading.pull(ref, 10)
            reading.record(ref, 8, 10, 800)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            onWrite = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            coroutineScope {
                val upload = async(Dispatchers.IO) { reading.flush() }
                check(entered.await(10, TimeUnit.SECONDS))
                try {
                    val choice = async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { reading.accept(ref, 1, 10, 100) }
                    }
                    assertFalse(choice.isCompleted, "Adoption must wait for the existing upload")
                    release.countDown()
                    upload.await()
                    assertTrue(choice.await().isFailure)
                    assertEquals(8, reading.cached(4)!!.progress.pageNum)
                    assertEquals(1, writes)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    @Test fun unreviewedChoiceCannotClearPendingState() = runBlocking {
        fixture {
            reading.record(ref, 8, 10, 800)
            val before = repository.operation(1, "a", "progress/4")
            assertTrue(runCatching { reading.accept(ref, 1, 10, 100) }.isFailure)
            assertEquals(before, repository.operation(1, "a", "progress/4"))
            assertEquals(0, writes)
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        Fixture().use { it.block() }
    }

    private inner class Fixture : AutoCloseable {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        var remote = KavitaProgress(1, 2, 3, 4, 1, "anchor", "1970-01-01T00:00:00.100Z")
        var writes = 0
        var onRead: suspend () -> Unit = {}
        var onWrite: () -> Unit = {}
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = when {
                    exchange.requestURI.path.endsWith("authenticate") ||
                        exchange.requestURI.path.endsWith("refresh-account") ->
                        """{"username":"a","token":"fixture","kavitaVersion":"0.9.1.4","roles":["Login"]}"""
                    exchange.requestURI.path.endsWith("get-progress") -> {
                        runBlocking { onRead() }
                        kotlinx.serialization.json.Json.encodeToString(remote)
                    }
                    else -> {
                        onWrite()
                        remote = kotlinx.serialization.json.Json.decodeFromString(
                            exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) },
                        )
                        writes++
                        "{}"
                    }
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val api = KavitaApiClient(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", "fixture", "a")
        fun coordinator() = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
        val reading = coordinator()
        override fun close() {
            api.close()
            server.stop(0)
        }
    }
}
