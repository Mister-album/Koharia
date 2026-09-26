package koharia.smanga

import com.sun.net.httpserver.HttpServer
import koharia.domain.smanga.SmangaCacheEntry
import koharia.domain.smanga.SmangaHistoryEvent
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.ByteString.Companion.encodeUtf8
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SmangaCatalogTest {
    private val requests = CopyOnWriteArrayList<URI>()
    private val clients = mutableListOf<SmangaApi>()
    private val repository = MemoryRepository()
    private val json = Json
    private var handler: (URI) -> Pair<Int, String> = { 200 to pageBody(it, "remote") }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            try {
                val uri = exchange.requestURI
                exchange.requestBody.use { it.readBytes() }
                requests += uri
                val (status, body) = if (uri.path.endsWith("/login")) {
                    200 to """{"code":200,"data":{"userId":1,"userName":"fixture","token":"fixture-token"}}"""
                } else {
                    handler(uri)
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } catch (_: java.io.IOException) {
                // Cancellation can close a fixture response before the gate is released.
            } finally {
                exchange.close()
            }
        }
        start()
    }
    private val address = "http://127.0.0.1:${server.address.port}/smanga"

    @AfterEach
    fun close() {
        clients.forEach(SmangaApi::close)
        server.stop(0)
    }

    private fun catalog(
        account: String = "account-a",
        connection: Long = 1,
        checkSession: () -> Unit = {
        },
    ): SmangaCatalog {
        val api = SmangaApi(OkHttpClient(), json, address, "fixture", "fixture", account)
        clients += api
        return SmangaCatalog(connection, account, repository, api, json, checkSession)
    }

    private fun shelfGroup(query: String = "", order: String = ORDER): String =
        "shelf/2/" + json.encodeToString(listOf(query, order)).encodeUtf8().sha256().hex()

    private suspend fun seedPage(page: Int, name: String, account: String = "account-a", connection: Long = 1) {
        val value = SmangaMangaPage(listOf(SmangaManga(page.toLong(), 2, name)), page, 100, 101)
        repository.putCache(
            connection,
            account,
            shelfGroup(),
            SmangaCacheEntry(page.toString(), json.encodeToString(value), 1, 1),
        )
    }

    private suspend fun seedTwoPages() {
        seedPage(1, "old-one")
        seedPage(2, "old-two")
    }

    @Test
    fun `database locale name order supports all media refresh and persisted warm reads`() = runTest {
        handler = { uri ->
            200 to if (uri.path.endsWith("/media")) {
                mediaBody(1, 2)
            } else if (uri.query.contains("mediaId=1")) {
                """{"code":200,"count":4,"list":[
                    {"mangaId":5,"mediaId":1,"mangaName":"JoJo6"},
                    {"mangaId":2,"mediaId":1,"mangaName":"peppercarrot"},
                    {"mangaId":7,"mediaId":1,"mangaName":"Space Adventures"},
                    {"mangaId":1,"mediaId":1,"mangaName":"Super Duck"}]}"""
            } else {
                """{"code":200,"count":1,"list":[{"mangaId":12,"mediaId":2,"mangaName":"[CLAMP]"}]}"""
            }
        }
        val catalog = catalog()
        catalog.refreshShelf(0, "", ORDER)
        val result = catalog.page(listOf(1, 2), "", ORDER, 1)
        assertEquals(setOf(1L, 2L, 5L, 7L, 12L), result.data.map { it.id }.toSet())
        assertEquals(listOf(5L, 2L, 7L, 1L), result.data.filter { it.mediaId == 1L }.map { it.id })
        assertEquals(5, result.total)
        assertFalse(result.hasNext)
        val count = requests.size
        val restarted = catalog()
        assertEquals(listOf(1L, 2L), restarted.media().map { it.id })
        assertEquals(result, restarted.page(listOf(1, 2), "", ORDER, 1))
        assertEquals(count, requests.size)
    }

    @Test
    fun `title tag union is persistent isolated and retains the successful generation on refresh failure`() = runTest {
        var failTags = false
        handler = { uri ->
            when {
                uri.path.endsWith("/tag") ->
                    200 to
                        """{"code":200,"list":[{"tagId":7,"tagName":"SPORT"},{"tagId":8,"tagName":"other"}],"count":2}"""
                uri.path.endsWith("/tags-manga") && failTags -> 500 to "{}"
                uri.path.endsWith("/tags-manga") -> {
                    assertTrue(uri.query.contains("tagIds=7"))
                    val rows = when (uri.page()) {
                        1 -> """{"mangaId":99,"mediaId":3,"mangaName":"A-other-library"}"""
                        2 -> """{"mangaId":1,"mediaId":2,"mangaName":"B-title-and-tag"}"""
                        3 -> """{"mangaId":2,"mediaId":2,"mangaName":"C-tag-only"}"""
                        else -> ""
                    }
                    200 to """{"code":200,"list":[$rows],"count":${if (rows.isEmpty()) 0 else 1}}"""
                }
                else ->
                    200 to
                        """{"code":200,"list":[{"mangaId":1,"mediaId":2,"mangaName":"B-title-and-tag"}],"count":1}"""
            }
        }
        val catalog = catalog()
        val first = catalog.page(2, "sport", ORDER, 1)
        assertEquals(listOf(1L, 2L), first.data.map { it.id })
        assertFalse(first.hasNext)
        assertEquals(4, requests.count { it.path.endsWith("/tags-manga") })
        val calls = requests.size
        assertEquals(first, catalog().page(2, "sport", ORDER, 1))
        assertEquals(calls, requests.size)
        val successful = repository.snapshot()
        failTags = true
        assertTrue(runCatching { catalog.refresh(2, "sport", ORDER) }.isFailure)
        assertEquals(successful, repository.snapshot())
        assertEquals(first, catalog().page(2, "sport", ORDER, 1))
        failTags = false
        val beforeOtherAccount = requests.size
        assertEquals(first, catalog(account = "account-b").page(2, "sport", ORDER, 1))
        assertTrue(requests.size > beforeOtherAccount)
        val beforeOtherConnection = requests.size
        assertEquals(first, catalog(connection = 2).page(2, "sport", ORDER, 1))
        assertTrue(requests.size > beforeOtherConnection)
    }

    @Test
    fun `failed initial tag page retry reuses completed title and tag lookup requests`() = runTest {
        var fail = true
        handler = { uri ->
            when {
                uri.path.endsWith("/tag") ->
                    200 to
                        """{"code":200,"list":[{"tagId":7,"tagName":"sport"}],"count":1}"""
                uri.path.endsWith("/tags-manga") && fail -> 500 to "{}"
                uri.path.endsWith("/tags-manga") -> 200 to """{"code":200,"list":[],"count":0}"""
                else -> 200 to """{"code":200,"list":[],"count":0}"""
            }
        }
        val catalog = catalog()
        assertTrue(runCatching { catalog.page(2, "sport", ORDER, 1) }.isFailure)
        val tags = requests.count { it.path.endsWith("/tag") }
        fail = false
        val result = catalog.page(2, "sport", ORDER, 1)
        assertFalse(result.hasNext)
        assertTrue(result.data.isEmpty())
        assertEquals(tags, requests.count { it.path.endsWith("/tag") })
        val calls = requests.size
        assertEquals(result, catalog().page(2, "sport", ORDER, 1))
        assertEquals(calls, requests.size)
    }

    @Test
    fun `warm persisted shelves media chapters and valid empty caches send zero requests`() = runTest {
        seedPage(1, "cached")
        repository.putCache(1, "account-a", "media", SmangaCacheEntry("data", "[]", 1))
        repository.putCache(1, "account-a", "chapters/5", SmangaCacheEntry("data", "[]", 1))
        val emptyPage = SmangaMangaPage(emptyList(), 1, 100, 0)
        repository.putCache(
            1,
            "account-a",
            "search/title-tags-v1/" + json.encodeToString(listOf("2", "empty", ORDER)).encodeUtf8().sha256().hex(),
            SmangaCacheEntry(
                "page/1",
                json.encodeToString(
                    SmangaSearchPage(
                        emptyPage,
                        SmangaSearchCursor(ended = true),
                        SmangaSearchCursor(ended = true),
                        emptySet(),
                    ),
                ),
                1,
            ),
        )
        // Construct a fresh catalog to model startup/resume rather than an in-memory warm object.
        repeat(2) {
            val catalog = catalog()
            assertEquals("cached", catalog.page(2, "", ORDER, 1).data.single().name)
            assertTrue(catalog.page(2, "empty", ORDER, 1).data.isEmpty())
            assertTrue(catalog.media().isEmpty())
            assertTrue(catalog.chapters(5).isEmpty())
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cold cache loads only requested pages and concurrent requests coalesce`() = runTest {
        val catalog = catalog()
        val first = (1..8).map { async { catalog.page(2, "", ORDER, 1) } }.awaitAll()
        assertEquals(100, first.first().data.size)
        assertTrue(first.first().hasNext)
        assertEquals(1, requests.count { it.path.endsWith("/manga") })
        val second = catalog.page(2, "", ORDER, 2)
        assertEquals(1, second.data.size)
        assertFalse(second.hasNext)
        assertEquals(listOf(1, 2), requests.filter { it.path.endsWith("/manga") }.map { it.page() })
        assertTrue(requests.filter { it.path.endsWith("/manga") }.all { it.query.contains("mediaId=2") })
        val requestCount = requests.size
        catalog().page(2, "", ORDER, 2)
        assertEquals(requestCount, requests.size)
    }

    @Test
    fun `second refresh page failure retains every old page and generation`() {
        runTest { seedTwoPages() }
        val before = repository.snapshot()
        handler = { uri ->
            if (uri.page() == 2) 503 to """{"code":503}""" else 200 to pageBody(uri, "new")
        }
        val catalog = catalog()
        assertThrows(SmangaException::class.java) { runTest { catalog.refresh(2, "", ORDER) } }
        assertEquals(before, repository.snapshot())
        assertEquals(listOf(1, 2), requests.filter { it.path.endsWith("/manga") }.map { it.page() })
        runTest {
            assertEquals("old-one", catalog.page(2, "", ORDER, 1).data.single().name)
            assertEquals("old-two", catalog.page(2, "", ORDER, 2).data.single().name)
        }
        assertEquals(before, repository.snapshot())
    }

    @Test
    fun `cancellation during second refresh page retains all successful cached data`() = runTest {
        seedTwoPages()
        val before = repository.snapshot()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CountDownLatch(1)
        handler = { uri ->
            if (uri.page() == 2) {
                secondStarted.complete(Unit)
                check(releaseSecond.await(10, TimeUnit.SECONDS))
            }
            200 to pageBody(uri, "new")
        }
        val catalog = catalog()
        val refreshing = async { catalog.refresh(2, "", ORDER) }
        try {
            secondStarted.await()
            refreshing.cancelAndJoin()
            assertEquals(before, repository.snapshot())
        } finally {
            releaseSecond.countDown()
        }
    }

    @Test
    fun `account and connection scopes never reuse another shelf cache`() = runTest {
        seedPage(1, "account-a")
        seedPage(1, "account-b", account = "account-b")
        seedPage(1, "other-connection", connection = 2)
        assertEquals("account-a", catalog().page(2, "", ORDER, 1).data.single().name)
        assertEquals("account-b", catalog(account = "account-b").page(2, "", ORDER, 1).data.single().name)
        assertEquals("other-connection", catalog(connection = 2).page(2, "", ORDER, 1).data.single().name)
        assertTrue(requests.isEmpty())
        assertEquals("remote-1", catalog(account = "new-account").page(2, "", ORDER, 1).data.first().name)
        assertEquals(1, requests.count { it.path.endsWith("/manga") })
        assertEquals("account-a", catalog().page(2, "", ORDER, 1).data.single().name)
    }

    @Test
    fun `concurrent page reads wait for refresh and observe one completed generation`() = runTest {
        seedTwoPages()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CountDownLatch(1)
        handler = { uri ->
            if (uri.page() == 1) {
                firstStarted.complete(Unit)
                check(releaseFirst.await(10, TimeUnit.SECONDS))
            }
            200 to pageBody(uri, "new")
        }
        val catalog = catalog()
        val refreshing = async { catalog.refresh(2, "", ORDER) }
        try {
            firstStarted.await()
            val readers = (1..8).map { async { catalog.page(2, "", ORDER, if (it % 2 == 0) 1 else 2) } }
            runCurrent()
            assertTrue(readers.none { it.isCompleted })
            releaseFirst.countDown()
            refreshing.await()
            assertTrue(readers.awaitAll().all { it.data.first().name.startsWith("new-") })
            assertEquals(2, requests.count { it.path.endsWith("/manga") })
            val entries = repository.cacheGroup(1, "account-a", shelfGroup())
            assertEquals(1, entries.map { it.generation }.distinct().size)
            assertTrue(entries.all { it.generation > 1 })
        } finally {
            releaseFirst.countDown()
        }
    }

    @Test
    fun `successful refresh can replace old data with a valid empty shelf`() = runTest {
        seedTwoPages()
        handler = { 200 to """{"code":200,"list":[],"count":0}""" }
        val catalog = catalog()
        catalog.refresh(2, "", ORDER)
        assertTrue(catalog.page(2, "", ORDER, 1).data.isEmpty())
        assertEquals(listOf("1"), repository.cacheGroup(1, "account-a", shelfGroup()).map { it.key })
        assertEquals(1, requests.count { it.path.endsWith("/manga") })
        assertTrue(catalog().page(2, "", ORDER, 1).data.isEmpty())
        assertEquals(1, requests.count { it.path.endsWith("/manga") })
    }

    @Test
    fun `invalidated session cannot return an awaited old account cache hit`() = runTest {
        seedPage(1, "old-account")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var active = true
        repository.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        val catalog = catalog { if (!active) throw CancellationException("Replaced session") }
        val reading = async { catalog.page(2, "", ORDER, 1) }
        entered.await()
        active = false
        release.complete(Unit)
        try {
            reading.await()
            throw AssertionError("Invalidated cache read must be rejected")
        } catch (_: CancellationException) {
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `all media pages remain permission scoped and warm cursors send no requests`() = runTest {
        handler = { uri -> 200 to mergedPageBody(uri) }
        val all = catalog()
        val first = all.page(listOf(2, 3), "", ORDER, 1)
        assertEquals(202, first.total)
        assertEquals(100, first.data.size)
        assertEquals(setOf(2L), first.data.map { it.mediaId }.toSet())
        assertEquals(2, requests.count { it.path.endsWith("/manga") })
        val second = all.page(listOf(2, 3), "", ORDER, 2)
        assertEquals(3, requests.count { it.path.endsWith("/manga") })
        val third = all.page(listOf(2, 3), "", ORDER, 3)
        assertEquals(4, requests.count { it.path.endsWith("/manga") })
        assertEquals(202, (first.data + second.data + third.data).map { it.id }.distinct().size)
        assertFalse(third.hasNext)
        assertTrue(requests.filter { it.path.endsWith("/manga") }.all { it.query.contains("mediaId=") })
        val count = requests.size
        clients.forEach(SmangaApi::close)
        val restarted = catalog()
        assertEquals(first, restarted.page(listOf(3, 2), "", ORDER, 1))
        assertEquals(third, restarted.page(listOf(2, 3), "", ORDER, 3))
        assertEquals(count, requests.size)
    }

    @Test
    fun `all media refresh failure preserves every old merged page and cursor`() = runTest {
        handler = { uri -> 200 to mergedPageBody(uri) }
        val all = catalog()
        val first = all.page(listOf(2, 3), "", ORDER, 1)
        val second = all.page(listOf(2, 3), "", ORDER, 2)
        val before = repository.snapshot()
        handler = { uri ->
            if (uri.page() == 2) 503 to """{"code":503}""" else 200 to mergedPageBody(uri, "new-")
        }
        val error = runCatching { all.refresh(listOf(2, 3), "", ORDER) }.exceptionOrNull()
        assertTrue(error is SmangaException)
        assertEquals(before, repository.snapshot())
        assertEquals(first, all.page(listOf(2, 3), "", ORDER, 1))
        assertEquals(second, all.page(listOf(2, 3), "", ORDER, 2))
        handler = { uri -> 200 to mergedPageBody(uri, "new-") }
        all.refresh(listOf(2, 3), "", ORDER)
        assertTrue(all.page(listOf(2, 3), "", ORDER, 2).data.all { it.name.startsWith("new-") })
    }

    @Test
    fun `all media empty search is cached separately from single media account and authorized scope`() = runTest {
        handler = { uri ->
            if (uri.path.endsWith("/tag") || uri.query.orEmpty().contains("keyWord=empty")) {
                200 to """{"code":200,"list":[],"count":0}"""
            } else {
                200 to mergedPageBody(uri)
            }
        }
        val all = catalog()
        assertTrue(all.page(emptyList(), "", ORDER, 1).data.isEmpty())
        assertTrue(requests.isEmpty())
        all.page(listOf(2, 3), "", ORDER, 1)
        assertTrue(all.page(listOf(2, 3), "empty", ORDER, 1).data.isEmpty())
        val count = requests.size
        assertTrue(catalog().page(listOf(2, 3), "empty", ORDER, 1).data.isEmpty())
        assertEquals(count, requests.size)
        val calls = requests.count { it.path.endsWith("/manga") }
        all.page(listOf(2), "", ORDER, 1)
        assertEquals(calls + 1, requests.count { it.path.endsWith("/manga") })
        catalog(account = "account-b").page(listOf(2, 3), "", ORDER, 1)
        assertEquals(calls + 3, requests.count { it.path.endsWith("/manga") })
        val restricted = all.page(listOf(3, 4), "", ORDER, 1)
        assertTrue(restricted.data.all { it.mediaId in listOf(3L, 4L) })
        assertEquals(calls + 5, requests.count { it.path.endsWith("/manga") })
    }

    @Test
    fun `cancelled all media refresh retains the previous merged generation`() = runTest {
        handler = { uri -> 200 to mergedPageBody(uri) }
        val all = catalog()
        all.page(listOf(2, 3), "", ORDER, 1)
        all.page(listOf(2, 3), "", ORDER, 2)
        val before = repository.snapshot()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CountDownLatch(1)
        handler = { uri ->
            if (uri.page() == 2) {
                secondStarted.complete(Unit)
                check(releaseSecond.await(10, TimeUnit.SECONDS))
            }
            200 to mergedPageBody(uri, "new-")
        }
        val refreshing = async { all.refresh(listOf(2, 3), "", ORDER) }
        try {
            secondStarted.await()
            refreshing.cancelAndJoin()
            assertEquals(before, repository.snapshot())
        } finally {
            releaseSecond.countDown()
        }
    }

    @Test
    fun `malformed all media pages are not cached and a retry can recover`() = runTest {
        handler = { uri ->
            if (uri.query.contains("mediaId=2")) {
                200 to """{"code":200,"count":101,"list":[{"mangaId":2001,"mediaId":2,"mangaName":"2-001"}]}"""
            } else {
                200 to mergedPageBody(uri)
            }
        }
        val all = catalog()
        val error = runCatching { all.page(listOf(2, 3), "", ORDER, 1) }.exceptionOrNull()
        assertTrue(error is SmangaException)
        val requestsBeforeRetry = requests.count { it.query?.contains("mediaId=2") == true }
        handler = { uri -> 200 to mergedPageBody(uri) }
        assertEquals(202, all.page(listOf(2, 3), "", ORDER, 1).total)
        assertEquals(requestsBeforeRetry + 1, requests.count { it.query?.contains("mediaId=2") == true })
    }

    @Test
    fun `added media become visible only after a successful shelf refresh`() = runTest {
        val old = seedAllMedia()
        handler = { uri ->
            if (uri.path.endsWith("/media")) 200 to mediaBody(2, 3, 4) else 503 to """{"code":503}"""
        }
        assertTrue(runCatching { catalog().refreshShelf(0, "", ORDER) }.exceptionOrNull() is SmangaException)
        requests.clear()
        val reopened = catalog()
        assertEquals(listOf(2L, 3L), reopened.media().map { it.id })
        assertEquals(old, reopened.page(reopened.media().map { it.id }, "", ORDER, 1))
        assertTrue(requests.isEmpty())

        handler = { uri ->
            200 to if (uri.path.endsWith("/media")) mediaBody(2, 3, 4) else mergedPageBody(uri, "new-")
        }
        reopened.refreshShelf(0, "", ORDER)
        requests.clear()
        val refreshed = catalog()
        assertEquals(listOf(2L, 3L, 4L), refreshed.media().map { it.id })
        val page = refreshed.page(refreshed.media().map { it.id }, "", ORDER, 1)
        assertEquals(303, page.total)
        assertTrue(page.data.all { it.name.startsWith("new-") })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cancelled refresh with added media leaves the previous warm shelf reachable`() = runTest {
        val old = seedAllMedia()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        handler = { uri ->
            if (uri.path.endsWith("/media")) {
                200 to mediaBody(2, 3, 4)
            } else {
                started.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
                200 to mergedPageBody(uri, "new-")
            }
        }
        val refreshing = async { catalog().refreshShelf(0, "", ORDER) }
        try {
            started.await()
            refreshing.cancelAndJoin()
            val count = requests.size
            val reopened = catalog()
            assertEquals(listOf(2L, 3L), reopened.media().map { it.id })
            assertEquals(old, reopened.page(reopened.media().map { it.id }, "", ORDER, 1))
            assertEquals(count, requests.size)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `failed refresh never restores a revoked media scope`() = runTest {
        seedAllMedia()
        handler = { uri ->
            if (uri.path.endsWith("/media")) 200 to mediaBody(3) else 503 to """{"code":503}"""
        }
        assertTrue(runCatching { catalog().refreshShelf(0, "", ORDER) }.exceptionOrNull() is SmangaException)
        requests.clear()
        assertEquals(listOf(3L), catalog().cachedMedia()?.map { it.id })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `revoked scope remains readable from cache after refresh cancellation`() = runTest {
        seedAllMedia()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        handler = { uri ->
            if (uri.path.endsWith("/media")) {
                200 to mediaBody(3)
            } else {
                started.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
                200 to mergedPageBody(uri)
            }
        }
        val refreshing = async { catalog().refreshShelf(0, "", ORDER) }
        try {
            started.await()
            refreshing.cancelAndJoin()
            val count = requests.size
            assertEquals(listOf(3L), catalog().cachedMedia()?.map { it.id })
            assertEquals(count, requests.size)
        } finally {
            release.countDown()
        }
    }

    private suspend fun seedAllMedia(): SmangaMangaPage {
        handler = { uri ->
            200 to if (uri.path.endsWith("/media")) mediaBody(2, 3) else mergedPageBody(uri)
        }
        val initial = catalog()
        return initial.page(initial.media().map { it.id }, "", ORDER, 1)
    }

    private fun mediaBody(vararg ids: Long): String {
        val values = ids.joinToString(",") { """{"mediaId":$it,"mediaName":"Library $it"}""" }
        return """{"code":200,"count":${ids.size},"list":[$values]}"""
    }

    private fun mergedPageBody(uri: URI, prefix: String = ""): String {
        val media = uri.query.substringAfter("mediaId=").substringBefore('&').toLong()
        val range = if (uri.page() == 1) 1..100 else 101..101
        val values = range.joinToString(",") {
            val id = media * 1000 + it
            val name = "$prefix$media-${it.toString().padStart(3, '0')}"
            """{"mangaId":$id,"mediaId":$media,"mangaName":"$name"}"""
        }
        return """{"code":200,"count":101,"list":[$values]}"""
    }

    private fun pageBody(uri: URI, prefix: String): String {
        val range = if (uri.page() == 1) 1..100 else 101..101
        val values = range.joinToString(",") {
            """{"mangaId":$it,"mediaId":2,"mangaName":"$prefix-$it"}"""
        }
        return """{"code":200,"count":101,"list":[$values]}"""
    }

    private fun URI.page(): Int = query.substringAfter("page=").substringBefore('&').toInt()

    private class MemoryRepository : SmangaRepository {
        private data class Group(val connection: Long, val account: String, val key: String)
        private val monitor = Any()
        private val groups = mutableMapOf<Group, Map<String, SmangaCacheEntry>>()
        var beforeRead: suspend () -> Unit = {}

        fun snapshot(): Map<String, Map<String, SmangaCacheEntry>> = synchronized(monitor) {
            groups.mapKeys { it.key.toString() }.mapValues { it.value.toMap() }
        }

        override suspend fun cache(
            connectionId: Long,
            accountKey: String,
            groupKey: String,
            key: String,
        ): SmangaCacheEntry? {
            beforeRead()
            return synchronized(monitor) { groups[Group(connectionId, accountKey, groupKey)]?.get(key) }
        }

        override suspend fun cacheGroup(
            connectionId: Long,
            accountKey: String,
            groupKey: String,
        ): List<SmangaCacheEntry> =
            synchronized(monitor) { groups[Group(connectionId, accountKey, groupKey)]?.values?.toList().orEmpty() }

        override suspend fun putCache(
            connectionId: Long,
            accountKey: String,
            groupKey: String,
            entry: SmangaCacheEntry,
        ) {
            synchronized(monitor) {
                val group = Group(connectionId, accountKey, groupKey)
                groups[group] = groups[group].orEmpty() + (entry.key to entry)
            }
        }

        override suspend fun replaceCacheGroup(
            connectionId: Long,
            accountKey: String,
            groupKey: String,
            entries: List<SmangaCacheEntry>,
        ) {
            synchronized(monitor) { groups[Group(connectionId, accountKey, groupKey)] = entries.associateBy { it.key } }
        }

        override suspend fun removeConnection(connectionId: Long) {
            synchronized(monitor) { groups.keys.removeAll { it.connection == connectionId } }
        }

        override suspend fun removeAccount(connectionId: Long, accountKey: String) {
            synchronized(monitor) {
                groups.keys.removeAll { it.connection == connectionId && it.account == accountKey }
            }
        }

        override suspend fun readState(
            connectionId: Long,
            accountKey: String,
            chapterId: Long,
        ): SmangaReadState? = unexpected()
        override suspend fun readStates(
            connectionId: Long,
            accountKey: String,
        ): List<SmangaReadState> = unexpected()
        override suspend fun putReadState(
            connectionId: Long,
            accountKey: String,
            state: SmangaReadState,
        ): Unit = unexpected()
        override suspend fun acknowledgeReadState(
            connectionId: Long,
            accountKey: String,
            chapterId: Long,
            revision: Long,
        ): Boolean = unexpected()
        override suspend fun resetReadStates(
            connectionId: Long,
            accountKey: String,
            chapterIds: List<Long>,
        ): Unit = unexpected()
        override suspend fun enqueueHistory(
            connectionId: Long,
            accountKey: String,
            event: SmangaHistoryEvent,
        ): Unit = unexpected()
        override suspend fun historyEvents(
            connectionId: Long,
            accountKey: String,
        ): List<SmangaHistoryEvent> = unexpected()
        override suspend fun updateHistoryStatus(
            connectionId: Long,
            accountKey: String,
            eventId: String,
            status: Int,
        ): Unit = unexpected()

        private fun unexpected(): Nothing = throw AssertionError("Catalog must not mutate reading state or history")
    }

    private companion object {
        const val ORDER = "mangaName asc"
    }
}
