package koharia.kavita

import com.sun.net.httpserver.HttpServer
import koharia.domain.kavita.KavitaOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.InetSocketAddress

class KavitaReadingCoordinatorTest {
    @ParameterizedTest
    @CsvSource("2, false", "0, false", "2, true")
    fun lateUploadPreservesNextRevisionAndRealConflicts(nextPage: Int, externalChange: Boolean) = runBlocking {
        val repository = MemoryKavitaRepository()
        val ref = KavitaChapterRef(1, 2, 3, 4, 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).apply { cancel() }
        lateinit var reading: KavitaReadingCoordinator
        val remotePage = java.util.concurrent.atomic.AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                val body = when {
                    exchange.requestURI.path.endsWith("authenticate") ||
                        exchange.requestURI.path.endsWith("refresh-account") ->
                        """{"username":"a","token":"fixture","kavitaVersion":"0.9.1.4","roles":["Login"]}"""
                    exchange.requestURI.path.endsWith(
                        "get-progress",
                    ) -> """{"chapterId":4,"pageNum":${remotePage.get()}}"""
                    else -> {
                        // The next page is recorded while the previous upload is still in flight.
                        if (remotePage.get() == 0) {
                            runBlocking {
                                reading.record(ref, nextPage, 10, 200, explicit = nextPage == 0, unread = nextPage == 0)
                            }
                        }
                        remotePage.set(
                            kotlinx.serialization.json.Json.decodeFromString<KavitaProgress>(request).pageNum,
                        )
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
        try {
            reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            reading.record(ref, 1, 10, 100)
            reading.flush()
            val operation = repository.operations(1, "a").single()
            assertTrue(operation.pending)
            assertEquals(2L, operation.revision)
            assertEquals(nextPage, reading.cached(4)?.progress?.pageNum)
            assertEquals(1, reading.cached(4)?.baseline?.pageNum)
            // Restarting must retain the advanced baseline, not just the in-memory snapshot.
            reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            if (externalChange) remotePage.set(7)
            reading.flush()
            assertEquals(externalChange, repository.operations(1, "a").single().pending)
            assertEquals(externalChange, requireNotNull(reading.cached(4)).conflict)
            assertEquals(if (externalChange) 7 else nextPage, remotePage.get())
        } finally {
            api.close()
            server.stop(0)
        }
    }

    @Test fun openingChapterIsNotAReadingMutationAndExplicitUnreadIsRetained() = runBlocking {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        KavitaApiClient(OkHttpClient(), "https://example.invalid/", "fixture", "a").use { api ->
            val reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            val ref = KavitaChapterRef(1, 2, 3, 4, 1)
            reading.record(ref, 10, 10, 100, initial = true)
            assertTrue(repository.operations(1, "a").isEmpty())
            reading.record(ref, 10, 10, 200)
            reading.record(ref, 0, 10, 300, explicit = true, unread = true)
            val state = requireNotNull(reading.cached(4))
            assertTrue(state.explicit)
            assertEquals(0, state.progress.pageNum)
        }
    }

    @Test fun continuedOfflineReadingPreservesAnUnresolvedRemoteConflict() = runBlocking {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        KavitaApiClient(OkHttpClient(), "https://example.invalid/", "fixture", "a").use { api ->
            val ref = KavitaChapterRef(1, 2, 3, 4, 1)
            val baseline = KavitaProgress(chapterId = 4, pageNum = 1)
            val old = KavitaReadingState(ref, baseline.copy(pageNum = 2), 10, baseline, conflict = true)
            repository.putOperation(1, "a", KavitaOperation("progress/4", api.json.encodeToString(old), 1, true))
            val reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            reading.record(ref, 3, 10, 200)
            val result = requireNotNull(reading.cached(4))
            assertTrue(result.conflict)
            assertEquals(baseline, result.baseline)
            assertEquals(3, result.progress.pageNum)
        }
    }
}
