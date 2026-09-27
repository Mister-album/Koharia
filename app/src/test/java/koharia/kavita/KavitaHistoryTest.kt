package koharia.kavita

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class KavitaHistoryTest {
    private var version = "0.9.1.4"
    private var status = 200
    private var failSecond = false
    private var malformedPage = false
    private var empty = false
    private var legacyReads = 0
    private val pages = mutableListOf<Int>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            exchange.requestBody.close()
            var code = 200
            val body = when {
                path.endsWith("authenticate") || path.endsWith("refresh-account") ->
                    """{"id":7,"username":"reader","token":"fixture","roles":["Login"],"kavitaVersion":"$version"}"""
                path.endsWith("Stats/user/reading-history") -> {
                    legacyReads++
                    assertTrue(exchange.requestURI.query.contains("userId=7"))
                    """[{"userId":7,"libraryId":1,"seriesId":2,"chapterId":3,"readDate":"2026-01-01T00:00:00Z"},
                        {"userId":8,"libraryId":1,"seriesId":4,"chapterId":5,"readDate":"2026-01-01T00:00:00Z"}]"""
                }
                else -> {
                    val page = Regex("PageNumber=(\\d+)").find(exchange.requestURI.query)!!.groupValues[1].toInt()
                    pages += page
                    code = if (page == 2 && failSecond) 503 else status
                    exchange.responseHeaders.add(
                        "Pagination",
                        """{"currentPage":${if (malformedPage) 9 else page},"totalPages":${if (empty) 0 else 2}}""",
                    )
                    if (empty) {
                        "[]"
                    } else {
                        """[{"libraryId":1,"seriesId":2,"chapters":[
                            {"chapterId":3,"endTimeUtc":"2026-01-0${page}T00:00:00Z"},
                            {"chapterId":4,"endTimeUtc":"invalid"}]}]"""
                    }
                }
            }.toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val api =
        KavitaApiClient(okhttp3.OkHttpClient(), "http://127.0.0.1:${server.address.port}", "fixture", "test")
    private val repository = MemoryKavitaRepository()
    private fun catalog(account: String = "one") = KavitaCatalog(1, account, repository, api) {}

    @AfterEach fun close() {
        api.close()
        server.stop(0)
    }

    @Test fun legacyVersionUsesKnownRouteWithoutProbingTheHtmlFallback() = runBlocking {
        version = "0.8.8"
        assertEquals(listOf(3L), api.readingHistory().map { it.chapterId })
        assertEquals(1, legacyReads)
        assertTrue(pages.isEmpty())
    }

    @Test fun allPagesAreMergedByChapterUsingTheLatestActualReadTime() = runBlocking {
        val result = catalog().history()
        assertEquals(listOf(1, 2), pages)
        assertEquals(listOf(KavitaHistoryEntry(1, 2, 3, kavitaTimestamp("2026-01-02T00:00:00Z"))), result)
        assertEquals(result, catalog().history())
        assertEquals(2, pages.size)
    }

    @Test fun partialRefreshFailureRetainsPriorSnapshotAndAnotherAccountCannotReuseIt() = runBlocking {
        val previous = catalog().history()
        failSecond = true
        assertTrue(runCatching { catalog().history(true) }.isFailure)
        assertEquals(previous, catalog().history())
        assertTrue(runCatching { catalog("other").history() }.isFailure)
    }

    @Test fun onlyMissingModernEndpointFallsBackToTheAuthenticatedUsersLegacyHistory() = runBlocking {
        status = 403
        assertTrue(runCatching { api.readingHistory() }.isFailure)
        assertEquals(0, legacyReads)
        status = 404
        assertEquals(listOf(3L), api.readingHistory().map { it.chapterId })
        assertEquals(1, legacyReads)
    }

    @Test fun emptyHistoryIsCachedAndInvalidServerPaginationIsRejected() = runBlocking {
        empty = true
        assertTrue(catalog().history().isEmpty())
        assertTrue(catalog().history().isEmpty())
        assertEquals(listOf(1), pages)
        malformedPage = true
        assertTrue(runCatching { catalog().history(true) }.isFailure)
        assertTrue(catalog().history().isEmpty())
    }
}
