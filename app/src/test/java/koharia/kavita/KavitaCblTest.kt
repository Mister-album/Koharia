package koharia.kavita

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class KavitaCblTest {
    private var posts = 0
    private var correctOrder = true
    private var failureStatus = 500
    private var listsRead = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.close()
            var status = 200
            val body = when {
                exchange.requestURI.path.endsWith("authenticate") ||
                    exchange.requestURI.path.endsWith("refresh-account") ->
                    """{"id":1,"username":"fixture","token":"fixture","roles":["Login"],"kavitaVersion":"0.9.1.4"}"""
                exchange.requestURI.path.endsWith("finalize-import") -> {
                    posts++
                    status = failureStatus
                    "{}"
                }
                exchange.requestURI.path.endsWith("ReadingList/lists") -> {
                    listsRead++
                    exchange.responseHeaders.add("Pagination", """{"currentPage":1,"totalPages":1}""")
                    """[{"id":7,"title":"fixture"}]"""
                }
                exchange.requestURI.path.endsWith("ReadingList/items") ->
                    """[{"id":1,"seriesId":2,"chapterId":${if (correctOrder) 3 else 9},"order":0}]"""
                else -> "{}"
            }.toByteArray()
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val api = KavitaApiClient(
        okhttp3.OkHttpClient(),
        "http://127.0.0.1:${server.address.port}",
        "fixture",
        "fixture",
    )
    private val cbl = KavitaCbl(KavitaCatalog(1, "fixture", MemoryKavitaRepository(), api) {}) {}
    private val staged = KavitaCblImport(
        Json.parseToJsonElement("""{"fileName":"fixture.cbl"}""").jsonObject,
        KavitaCblSummary(cblName = "fixture", results = listOf(KavitaCblResult(seriesId = 2, chapterId = 3))),
    )

    @AfterEach fun close() {
        api.close()
        server.stop(0)
    }

    @Test fun responseFailureReconcilesExactMembershipWithoutReplayingImport() = runBlocking {
        val result = cbl.finish(staged, emptyMap())
        assertTrue(result.verifiedAfterError)
        assertEquals(7L, result.readingListId)
        assertEquals(1, posts)
    }

    @Test fun partialImportIsNotReportedAsVerifiedAndPermissionDenialsAreNotReconciled() = runBlocking {
        correctOrder = false
        assertTrue(runCatching { cbl.finish(staged, emptyMap()) }.isFailure)
        val reads = listsRead
        failureStatus = 403
        assertTrue(runCatching { cbl.finish(staged, emptyMap()) }.isFailure)
        assertEquals(reads, listsRead)
    }
}
