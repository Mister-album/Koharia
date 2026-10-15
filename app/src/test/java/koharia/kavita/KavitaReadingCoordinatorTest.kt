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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.InetSocketAddress

class KavitaReadingCoordinatorTest {
    @Test fun queuedOrSupersededPositionsCannotConfirmAnEpubUpload() = runBlocking {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        KavitaApiClient(OkHttpClient(), "https://example.invalid/", "fixture", "a").use { api ->
            val ref = KavitaChapterRef(1, 2, 3, 4, 3)
            val reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            val matches: (KavitaReadingState) -> Boolean = {
                it.progress.pageNum == 2 &&
                    it.progress.bookScrollId == "anchor"
            }
            assertFalse(reading.isConfirmed(ref, 100, matches))
            reading.record(ref, 2, 10, 100, anchor = "anchor")
            assertFalse(reading.isConfirmed(ref, 100, matches))
            val pending = repository.operations(1, "a").single()
            repository.acknowledge(1, "a", pending.key, pending.revision)
            assertTrue(reading.isConfirmed(ref, 100, matches))
            assertFalse(reading.isConfirmed(ref, 101, matches))
            assertFalse(reading.isConfirmed(ref.copy(libraryId = 9), 100, matches))
            assertFalse(reading.isConfirmed(ref, 100) { it.progress.pageNum == 3 })
            reading.record(ref, 3, 10, 200, anchor = "later")
            repository.acknowledge(1, "a", pending.key, pending.revision)
            assertFalse(reading.isConfirmed(ref, 100, matches))
        }
    }

    @Test fun reviewFetchesFreshRemoteEvidenceWithoutChangingPendingIntent() = runBlocking {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = when {
                    exchange.requestURI.path.endsWith("authenticate") ||
                        exchange.requestURI.path.endsWith("refresh-account") ->
                        """{"username":"a","token":"fixture","kavitaVersion":"0.9.1.4","roles":["Login"]}"""
                    exchange.requestURI.path.endsWith("get-progress") ->
                        """{"chapterId":4,"pageNum":${reads.incrementAndGet()}}"""
                    else -> {
                        writes.incrementAndGet()
                        "{}"
                    }
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Cache-Control", "max-age=3600")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        try {
            KavitaApiClient(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", "fixture", "a").use { api ->
                val ref = KavitaChapterRef(1, 2, 3, 4, 3)
                val pending = KavitaReadingState(ref, KavitaProgress(chapterId = 4, pageNum = 9), 10, conflict = true)
                repository.putOperation(
                    1,
                    "a",
                    KavitaOperation("progress/4", api.json.encodeToString(pending), 1, true),
                )
                val before = repository.operations(1, "a")
                val reading =
                    KavitaReadingCoordinator(1, "a", repository, api, scope, {}, { error("Review adopted state") })
                assertEquals(1, reading.review(ref, 10).progress.pageNum)
                assertEquals(2, reading.review(ref, 10).progress.pageNum)
                assertEquals(before, repository.operations(1, "a"))
                assertEquals(0, writes.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test fun rejectedExplicitIntentIsNotReportedAsDurablyQueued() = runBlocking {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        KavitaApiClient(OkHttpClient(), "https://example.invalid/", "fixture", "a").use { api ->
            val reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            val ref = KavitaChapterRef(1, 2, 3, 4, 1)
            assertFalse(reading.record(ref, 0, 0, 100, explicit = true))
            koharia.connection.ConnectionRestoreState.duringRestore {
                assertFalse(reading.record(ref, 10, 10, 100, explicit = true))
            }
            assertTrue(repository.operations(1, "a").isEmpty())
            assertTrue(reading.record(ref, 10, 10, 200, explicit = true))
            assertTrue(repository.operations(1, "a").single().pending)
        }
    }

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
            assertEquals(0, repository.fullHistoryReads)
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

    @Test fun acceptingMappedRemotePositionReplacesBaselinePageAndTimestamp() = runBlocking {
        val repository = MemoryKavitaRepository()
        val scope = CoroutineScope(SupervisorJob()).apply { cancel() }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = when {
                    exchange.requestURI.path.endsWith("authenticate") ||
                        exchange.requestURI.path.endsWith("refresh-account") ->
                        """{"username":"a","token":"fixture","kavitaVersion":"0.9.1.4","roles":["Login"]}"""
                    exchange.requestURI.path.endsWith("get-progress") ->
                        """{"libraryId":1,"seriesId":2,"volumeId":3,"chapterId":4,"pageNum":9,"bookScrollId":"remote-anchor","lastModifiedUtc":"2026-01-01T00:00:00Z"}"""
                    else -> "{}"
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val api = KavitaApiClient(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", "fixture", "a")
        try {
            val reading = KavitaReadingCoordinator(1, "a", repository, api, scope, {}, {})
            val ref = KavitaChapterRef(1, 2, 3, 4, 1)
            reading.pull(ref, 8)
            reading.accept(ref, 7, 8, 2_000)

            val accepted = requireNotNull(reading.cached(4))
            assertEquals(7, accepted.progress.pageNum)
            assertEquals("remote-anchor", accepted.progress.bookScrollId)
            assertEquals("1970-01-01T00:00:02Z", accepted.progress.lastModifiedUtc)
            reading.accept(ref, 7, 8, 3_000, anchorOverride = "reviewed-anchor")
            assertEquals("reviewed-anchor", reading.cached(4)?.progress?.bookScrollId)
            reading.accept(ref, 8, 8, 4_000, anchorOverride = "")
            assertEquals("", reading.cached(4)?.progress?.bookScrollId)
            assertEquals(8, reading.cached(4)?.progress?.pageNum)
        } finally {
            api.close()
            server.stop(0)
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
