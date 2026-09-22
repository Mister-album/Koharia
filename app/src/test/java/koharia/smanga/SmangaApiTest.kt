package koharia.smanga

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SmangaApiTest {
    private data class Seen(
        val method: String,
        val uri: URI,
        val token: String?,
        val auth: String?,
        val body: String,
        val range: String?,
    )

    private val requests = CopyOnWriteArrayList<Seen>()
    private val logins = AtomicInteger()
    private var handler: (Seen) -> Pair<Int, String> = { 200 to """{"code":200,"list":[],"count":0}""" }
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val request = Seen(
                exchange.requestMethod,
                exchange.requestURI,
                exchange.requestHeaders.getFirst("token"),
                exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestBody.reader().readText(),
                exchange.requestHeaders.getFirst("Range"),
            )
            requests += request
            val (status, text) = if (request.uri.path.endsWith("/login")) {
                val login = logins.incrementAndGet()
                val token = if (login == 1) "fixture-token" else "fixture-token-$login"
                200 to """{"code":200,"data":{"userId":"3","userName":"reader","token":"$token"}}"""
            } else {
                handler(request)
            }
            if (status == 0) {
                exchange.close()
                return@createContext
            }
            if (status == 302) exchange.responseHeaders.add("Location", root + "image")
            if (status == 503) exchange.responseHeaders.add("Retry-After", "0")
            val bytes = text.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }
    private val root = "http://127.0.0.1:${server.address.port}/reader/"
    private val network = OkHttpClient()
    private val api = SmangaApi(network, Json, root, "reader", "fixture-password", "account-a")

    @AfterEach
    fun close() {
        api.close()
        server.stop(0)
    }

    @Test
    fun `normalization preserves reverse proxy paths without duplicate api`() {
        assertEquals(root + "api/", SmangaApi.normalizeBase(root).toString())
        assertEquals(root + "api/", SmangaApi.normalizeBase(root + "api/").toString())
        assertEquals(root + "api/", SmangaApi.normalizeBase(root + "api").toString())
        assertThrows(SmangaException::class.java) { SmangaApi.normalizeBase(root + "?token=secret") }
        assertThrows(SmangaException::class.java) { SmangaApi.normalizeBase("https://user:secret@example.com") }
    }

    @Test
    fun `JSON authenticates only with token and login bodies bypass inherited logging`() = runTest {
        val logs = AtomicInteger()
        val inherited = network.newBuilder().addInterceptor {
            logs.incrementAndGet()
            it.proceed(it.request())
        }.build()
        val isolated = SmangaApi(inherited, Json, root, "reader", "fixture-password", "a")
        try {
            isolated.media()
            assertEquals(0, logs.get())
            assertEquals(2, requests.size)
            assertEquals("POST", requests[0].method)
            assertEquals(
                "fixture-password",
                Json.parseToJsonElement(requests[0].body).jsonObject["passWord"]?.jsonPrimitive?.content,
            )
            assertEquals(null, requests[0].token)
            assertEquals("fixture-token", requests[1].token)
            assertTrue(requests.all { it.auth == null && !it.uri.toString().contains("fixture") })
        } finally {
            isolated.close()
        }
    }

    @Test
    fun `concurrent calls share the initial login`() = runTest {
        (1..8).map { async { api.media() } }.awaitAll()
        assertEquals(1, requests.count { it.uri.path.endsWith("/login") })
    }

    @Test
    fun `concurrent expired token responses share one refreshed login`() = runTest {
        api.login()
        handler = {
            if (it.token == "fixture-token") {
                401 to """{"code":1,"status":"token error"}"""
            } else {
                200 to """{"code":200,"list":[],"count":0}"""
            }
        }
        (1..8).map { async { api.media() } }.awaitAll()
        assertEquals(2, logins.get())
    }

    @Test
    fun `token rejection reauthenticates at most once and permission error never does`() {
        handler = { 401 to """{"code":1,"status":"token error"}""" }
        assertThrows(SmangaException::class.java) { runTest { api.media() } }
        assertEquals(2, requests.count { it.uri.path.endsWith("/login") })
        requests.clear()
        handler = { 401 to """{"code":1,"status":"permisson error"}""" }
        val error = assertThrows(SmangaException::class.java) { runTest { api.media() } }
        assertEquals(SmangaException.Reason.PERMISSION, error.reason)
        assertEquals(0, requests.count { it.uri.path.endsWith("/login") })
    }

    @Test
    fun `HTTP200 semantic error is sanitized and cannot become a shelf`() {
        handler = { 200 to """{"code":500,"message":"private server path and fixture-password","list":[],"count":0}""" }
        val error = assertThrows(SmangaException::class.java) { runTest { api.media() } }
        assertEquals(SmangaException.Reason.SERVER, error.reason)
        assertFalse(error.toString().contains("private"))
        assertFalse(error.toString().contains("fixture-password"))
    }

    @Test
    fun `manga description reads backend describe and preserves legacy fallbacks`() = runTest {
        handler = { request ->
            assertEquals("/reader/api/manga/8", request.uri.path)
            200 to
                """{"code":200,"data":{"mangaId":8,"mediaId":2,"mangaName":"Fixture","describe":"Backend description","intro":"Legacy intro","description":"Legacy description","summary":"Legacy summary"}}"""
        }
        assertEquals("Backend description", api.manga(8).description)

        for ((field, expected) in listOf(
            "intro" to "Legacy intro",
            "description" to "Legacy description",
            "summary" to "Legacy summary",
        )) {
            handler = {
                200 to
                    """{"code":200,"data":{"mangaId":8,"mediaId":2,"mangaName":"Fixture","$field":"$expected"}}"""
            }
            assertEquals(expected, api.manga(8).description)
        }
    }

    @Test
    fun `OPDS basic auth stays within exact server base and never follows redirects`() {
        handler = { 200 to "image" }
        api.opdsClient.newCall(Request.Builder().url(api.coverUrl(4)).header("token", "wrong").build()).execute().use {
            assertTrue(it.isSuccessful)
        }
        assertEquals(Credentials.basic("reader", "fixture-password", Charsets.UTF_8), requests.single().auth)
        assertEquals(null, requests.single().token)
        assertEquals("koharia=account-a", requests.single().uri.query)
        val forbidden = listOf(
            root + "api/image",
            root + "api/opds-other",
            root.replace("/reader/", "/other/") + "api/opds",
        )
        forbidden.forEach { url ->
            assertThrows(SmangaException::class.java) {
                api.opdsClient.newCall(Request.Builder().url(url).build()).execute()
            }
        }
        assertEquals(1, requests.size)
        handler = { 302 to "redirect" }
        api.opdsClient.newCall(Request.Builder().url(api.coverUrl(4)).build()).execute().use {
            assertEquals(302, it.code)
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun `namespace segregates image caches and raw PDF does not send Range`() {
        val other = SmangaApi(network, Json, root, "reader", "fixture-password", "account-b")
        try {
            assertNotEquals(api.coverUrl(4), other.coverUrl(4))
            val request = api.rawFileRequest(9).newBuilder().header("Range", "bytes=0-10").build()
            api.opdsClient.newCall(request).execute().close()
            assertEquals("/reader/api/opds/chapter/9/download", requests.single().uri.path)
            assertEquals(null, requests.single().range)
        } finally {
            other.close()
        }
    }

    @Test
    fun `replacement account client cannot authenticate an old account image URL`() {
        val replacement = SmangaApi(network, Json, root, "another-reader", "fixture-password", "account-b")
        try {
            assertThrows(SmangaException::class.java) {
                replacement.opdsClient.newCall(Request.Builder().url(api.coverUrl(4)).build()).execute()
            }
            assertTrue(requests.isEmpty())
        } finally {
            replacement.close()
        }
    }

    @Test
    fun `validation requires authenticated OPDS capability`() {
        handler = {
            when {
                it.uri.path.endsWith("/user/me") -> 200 to """{"code":200,"data":{"userId":3,"userName":"reader"}}"""
                else ->
                    200 to
                        """<?xml version="1.0"?><feed xmlns="http://www.w3.org/2005/Atom"><title>smanga</title></feed>"""
            }
        }
        runTest { assertEquals(3L, api.validate().id) }
        assertTrue(requests.last().auth?.startsWith("Basic ") == true)
        handler = {
            if (it.uri.path.endsWith("/user/me")) {
                200 to """{"code":200,"data":{"userId":3,"userName":"reader"}}"""
            } else {
                404 to "OPDS disabled"
            }
        }
        val error = assertThrows(SmangaException::class.java) { runTest { api.validate() } }
        assertEquals(SmangaException.Reason.OPDS_DISABLED, error.reason)
    }

    @Test
    fun `chapter pagination remains manga scoped and retains latest numeric string fields`() = runTest {
        handler = {
            val page = it.uri.query.substringAfter("page=").substringBefore('&').toInt()
            val entries = if (page == 1) (1..100) else (101..101)
            200 to """{"code":200,"count":"101","list":[${entries.joinToString(",") { id ->
                """{"chapterId":"$id","mangaId":8,"mediaId":2,"chapterName":"chapter$id","chapterNumber":"$id","chapterType":"pdf","latest":{"page":"2","count":"5","finish":"0","updateTime":"2026-09-22T00:00:00Z"}}"""
            }}]}"""
        }
        val chapters = api.chapters(8)
        assertEquals(101, chapters.size)
        assertEquals(1, chapters.first().latest?.pageIndex)
        assertEquals(5, chapters.first().latest?.totalPages)
        assertTrue(chapters.first().latest!!.updatedAt > 0)
        assertTrue(requests.drop(1).all { it.uri.query.contains("mangaId=8") && it.uri.query.contains("pageSize=100") })
        // The pinned backend matches /id/ case-sensitively; "chapterId" silently disables sorting.
        assertTrue(requests.drop(1).all { it.uri.query.contains("order=id asc") })
        assertEquals(3, requests.size)
    }

    @Test
    fun `incomplete pagination is rejected instead of publishing partial cache`() {
        handler = { 200 to """{"code":200,"list":[],"count":5}""" }
        val error = assertThrows(SmangaException::class.java) { runTest { api.chapters(8) } }
        assertEquals(SmangaException.Reason.INCOMPLETE, error.reason)
    }

    @Test
    fun `preparation ignores partial compressing files and caps retry below destructive threshold`() = runTest {
        val polls = AtomicInteger()
        handler = {
            when {
                it.uri.path.endsWith("/client-user-config") ->
                    200 to
                        """{"code":200,"data":{"orderChapterByNumber":true}}"""
                polls.incrementAndGet() <= 12 ->
                    200 to
                        """{"code":200,"status":"compressing","data":["/private/incomplete.jpg"]}"""
                else ->
                    200 to
                        """{"code":200,"status":"compressed","data":["/private/1.jpg","/private/2.jpg","/private/10.jpg"]}"""
            }
        }
        val manifest = api.preparePages(7)
        assertEquals(listOf(1, 3, 2), manifest.pages.map { it.opdsPage })
        val pollsSeen = requests.filter { it.uri.path.contains("chapter-images") }
        assertEquals(13, pollsSeen.size)
        assertTrue(pollsSeen.all { it.uri.query.substringAfter("reTry=").substringBefore('&').toInt() <= 9 })
        assertTrue(pollsSeen.all { it.uri.query.contains("orderChapterByNumber=1") })
    }

    @Test
    fun `preparation is finite and false order omits truthy query strings`() {
        handler = {
            if (it.uri.path.endsWith("/client-user-config")) {
                200 to """{"code":200,"data":{"orderChapterByNumber":false}}"""
            } else {
                200 to """{"code":202,"status":"compressing","data":[]}"""
            }
        }
        val error = assertThrows(SmangaException::class.java) { runTest { api.preparePages(7) } }
        assertEquals(SmangaException.Reason.PREPARING, error.reason)
        val polls = requests.filter { it.uri.path.contains("chapter-images") }
        assertEquals(30, polls.size)
        assertTrue(polls.all { !it.uri.query.contains("orderChapterByNumber") })
    }

    @Test
    fun `cancelling preparation prevents subsequent extraction polls`() = runTest {
        val firstPoll = CompletableDeferred<Unit>()
        handler = {
            if (it.uri.path.endsWith("/client-user-config")) {
                200 to """{"code":200,"data":{}}"""
            } else {
                firstPoll.complete(Unit)
                200 to """{"code":202,"status":"compressing","data":[]}"""
            }
        }
        val preparing = async { api.preparePages(7) }
        firstPoll.await()
        preparing.cancelAndJoin()
        assertEquals(1, requests.count { it.uri.path.contains("chapter-images") })
    }

    @Test
    fun `explicit order invalidation reloads the server user setting`() = runTest {
        var numeric = false
        handler = {
            if (it.uri.path.endsWith("/client-user-config")) {
                200 to """{"code":200,"data":{"orderChapterByNumber":$numeric}}"""
            } else {
                200 to """{"code":200,"status":"compressed","data":["/fixture/1.jpg"]}"""
            }
        }
        api.preparePages(7)
        numeric = true
        api.preparePages(8)
        assertEquals(1, requests.count { it.uri.path.endsWith("/client-user-config") })
        api.invalidatePageOrder()
        api.preparePages(9)
        assertEquals(2, requests.count { it.uri.path.endsWith("/client-user-config") })
        val polls = requests.filter { it.uri.path.contains("chapter-images") }
        assertFalse(polls[0].uri.query.contains("orderChapterByNumber"))
        assertFalse(polls[1].uri.query.contains("orderChapterByNumber"))
        assertTrue(polls[2].uri.query.contains("orderChapterByNumber=1"))
    }

    @Test
    fun `progress uses one based server pages and unread upserts zero retaining visits`() = runTest {
        val updatedAt = "2026-09-22T08:08:09.031Z"
        handler = {
            val body = Json.parseToJsonElement(it.body).jsonObject
            200 to buildJsonObject {
                put("code", 200)
                put(
                    "data",
                    buildJsonObject {
                        put("chapterId", 4)
                        put("mangaId", 8)
                        put("page", body.getValue("page"))
                        put("count", body.getValue("count"))
                        put("finish", body.getValue("finish"))
                        put("updateTime", updatedAt)
                    },
                )
            }.toString()
        }
        val time = Instant.parse(updatedAt).toEpochMilli()
        assertEquals(SmangaProgress(2, 10, false, time), api.pushProgress(8, 4, 2, 10, false))
        assertEquals(SmangaProgress(-1, 10, false, time), api.markUnread(8, 4, 10))
        assertEquals(SmangaProgress(9, 10, true, time), api.pushProgress(8, 4, 9, 10, true))
        val updates = requests.filter { it.uri.path.endsWith("/latest") }
        assertEquals(
            listOf("3", "0", "10"),
            updates.map {
                Json.parseToJsonElement(it.body).jsonObject["page"]?.jsonPrimitive?.content
            },
        )
        assertEquals(
            listOf("0", "0", "1"),
            updates.map {
                Json.parseToJsonElement(it.body).jsonObject["finish"]?.jsonPrimitive?.content
            },
        )
        assertTrue(updates.all { it.method == "POST" })
        assertFalse(requests.any { it.method == "DELETE" })
    }

    @Test
    fun `progress writes reject missing mismatched and malformed acknowledgement fields`() {
        for ((unread, completed) in listOf(false to false, false to true, true to false)) {
            val page = if (unread) 0 else 3
            val count = if (unread) 0 else 10
            val valid = buildJsonObject {
                put("page", page)
                put("count", count)
                put("finish", if (completed) 1 else 0)
                put("updateTime", "2026-09-22T08:08:09.031Z")
            }
            val invalid = listOf(
                "empty data" to JsonObject(emptyMap()),
                "missing page" to JsonObject(valid - "page"),
                "missing count" to JsonObject(valid - "count"),
                "missing finish" to JsonObject(valid - "finish"),
                "missing time" to JsonObject(valid - "updateTime"),
                "different page" to JsonObject(valid + ("page" to JsonPrimitive(page + 1))),
                "different count" to JsonObject(valid + ("count" to JsonPrimitive(count + 1))),
                "different finish" to JsonObject(valid + ("finish" to JsonPrimitive(if (completed) 2 else 1))),
                "invalid page" to JsonObject(valid + ("page" to JsonPrimitive("invalid"))),
                "invalid count" to JsonObject(valid + ("count" to JsonPrimitive("invalid"))),
                "boolean finish" to JsonObject(valid + ("finish" to JsonPrimitive(false))),
                "invalid time" to JsonObject(valid + ("updateTime" to JsonPrimitive("invalid"))),
                "zero time" to JsonObject(valid + ("updateTime" to JsonPrimitive(0))),
                "negative time" to JsonObject(valid + ("updateTime" to JsonPrimitive(-1))),
            )
            for ((label, data) in invalid) {
                handler = {
                    200 to buildJsonObject {
                        put("code", 200)
                        put("data", data)
                    }.toString()
                }
                val error = assertThrows(SmangaException::class.java) {
                    runTest {
                        if (unread) {
                            api.markUnread(8, 4, count)
                        } else {
                            api.pushProgress(8, 4, 2, count, completed)
                        }
                    }
                }
                assertEquals(
                    SmangaException.Reason.PROTOCOL,
                    error.reason,
                    "$label, unread=$unread, completed=$completed",
                )
            }
        }
    }

    @Test
    fun `chapter reads retain tolerant latest parsing independently of strict write acknowledgements`() = runTest {
        handler = {
            200 to
                """{"code":200,"data":{"chapterId":4,"mangaId":8,"mediaId":2,"chapterName":"Chapter","latest":{"page":"2","count":"10","finish":"1","updateTime":"unknown"}}}"""
        }
        assertEquals(SmangaProgress(1, 10, true, 0), api.chapter(4).latest)
        handler = {
            200 to """{"code":200,"data":{"chapterId":4,"mangaId":8,"mediaId":2,"chapterName":"Chapter","latest":{}}}"""
        }
        assertEquals(SmangaProgress(), api.chapter(4).latest)
    }

    @Test
    fun `history POST is never replayed after authentication rejection`() {
        handler = { 401 to """{"code":1,"status":"token error"}""" }
        assertThrows(SmangaException::class.java) { runTest { api.addHistory(2, 8, 4) } }
        assertEquals(1, requests.count { it.uri.path.endsWith("/history") })
        assertEquals(1, logins.get())
    }

    @Test
    fun `history POST is never replayed after server receives body and disconnects`() {
        handler = { 0 to "" }
        assertThrows(java.io.IOException::class.java) { runTest { api.addHistory(2, 8, 4) } }
        assertEquals(1, requests.count { it.uri.path.endsWith("/history") })
        assertEquals(1, logins.get())
    }

    @Test
    fun `history POST ignores immediate service unavailable retry hints`() {
        handler = { 503 to """{"code":503}""" }
        assertThrows(SmangaException::class.java) { runTest { api.addHistory(2, 8, 4) } }
        assertEquals(1, requests.count { it.uri.path.endsWith("/history") })
    }

    @Test
    fun `idempotent latest upsert still refreshes expired authentication once`() = runTest {
        handler = {
            if (it.token == "fixture-token") {
                401 to """{"code":1,"status":"token error"}"""
            } else {
                200 to
                    """{"code":200,"data":{"chapterId":4,"mangaId":8,"page":3,"count":10,"finish":0,"updateTime":"2026-09-22T08:08:09.031Z"}}"""
            }
        }
        assertEquals(
            SmangaProgress(2, 10, false, Instant.parse("2026-09-22T08:08:09.031Z").toEpochMilli()),
            api.pushProgress(8, 4, 2, 10, false),
        )
        assertEquals(2, requests.count { it.uri.path.endsWith("/latest") })
        assertEquals(2, logins.get())
    }
}
