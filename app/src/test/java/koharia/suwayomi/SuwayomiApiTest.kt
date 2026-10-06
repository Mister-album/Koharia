@file:Suppress("ktlint:standard:max-line-length")

package koharia.suwayomi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionAddressVerification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SuwayomiApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val servers = mutableListOf<HttpServer>()
    private val clients = mutableListOf<SuwayomiApi>()
    private class Fixture(val mode: SuwayomiAuthMode, val meta: MutableMap<String, String> = ConcurrentHashMap()) {
        val requests = CopyOnWriteArrayList<String>()
        val loginCount = AtomicInteger()
        val refreshCount = AtomicInteger()
        var access = "fixture-1"
        var expired = false
        var refreshInvalid = false
        var graphqlStatus = 200
        var deny = false
        var denyLogin = false
        var metaReadDelayMillis = 0L
        var pageFailure = false
        var chapterExists = true
        var sourceExists = true
    }
    private fun server(fixture: Fixture): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange, fixture) }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/proxy/"
    }
    private fun api(
        base: String,
        mode: SuwayomiAuthMode,
        internal: String = "",
        router: ConnectionAddressRouter? = null,
    ) =
        SuwayomiApi(OkHttpClient(), json, base, mode, "fixture-user", "fixture-password", internal, router)
            .also { clients += it }
    private fun send(exchange: HttpExchange, text: String, status: Int = 200) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
    private fun handle(exchange: HttpExchange, fixture: Fixture) {
        fixture.requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
        if (exchange.requestURI.path == "/proxy/login.html") {
            fixture.loginCount.incrementAndGet()
            if (fixture.denyLogin) {
                send(exchange, "{}", 401)
                return
            }
            fixture.expired = false
            exchange.responseHeaders.add("Set-Cookie", "session=fixture; Path=/proxy/; HttpOnly")
            exchange.responseHeaders.add("Location", "/proxy/")
            send(exchange, "{}", 303)
            return
        }
        val body = if (exchange.requestMethod == "POST") {
            runCatching { json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject }
                .getOrDefault(JsonObject(emptyMap()))
        } else {
            JsonObject(emptyMap())
        }
        val query = body["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val variables = body["variables"]?.jsonObject ?: JsonObject(emptyMap())
        if (query.startsWith("mutation") && query.contains("refreshToken(")) {
            fixture.refreshCount.incrementAndGet()
            if (fixture.refreshInvalid) {
                send(exchange, "{\"errors\":[{\"message\":\"invalid refresh\"}]}")
            } else {
                fixture.access = "fixture-${fixture.refreshCount.get() + 1}"
                fixture.expired = false
                send(exchange, "{\"data\":{\"refreshToken\":{\"accessToken\":\"${fixture.access}\"}}}")
            }
            return
        }
        if (query.startsWith("mutation") && query.contains("login(")) {
            fixture.loginCount.incrementAndGet()
            if (fixture.denyLogin) {
                send(exchange, "{\"errors\":[{\"message\":\"Unauthorized\"}]}")
                return
            }
            fixture.access = "fixture-login-${fixture.loginCount.get()}"
            fixture.expired = false
            send(
                exchange,
                "{\"data\":{\"login\":{\"accessToken\":\"${fixture.access}\",\"refreshToken\":\"fixture-refresh\"}}}",
            )
            return
        }
        val authorized = !fixture.deny && when (fixture.mode) {
            SuwayomiAuthMode.NONE -> true
            SuwayomiAuthMode.BASIC_AUTH -> exchange.requestHeaders.getFirst("Authorization")?.startsWith("Basic ") ==
                true
            SuwayomiAuthMode.SIMPLE_LOGIN -> exchange.requestHeaders.getFirst("Cookie") == "session=fixture" &&
                !fixture.expired
            SuwayomiAuthMode.UI_LOGIN -> exchange.requestHeaders.getFirst(
                "Authorization",
            ) == "Bearer ${fixture.access}" &&
                !fixture.expired
        }
        if (!authorized) {
            send(
                exchange,
                "{\"errors\":[{\"message\":\"Unauthorized\"}]}",
                if (fixture.mode == SuwayomiAuthMode.BASIC_AUTH) 401 else 200,
            )
            return
        }
        if (fixture.graphqlStatus != 200 && exchange.requestMethod == "POST") {
            send(exchange, "{}", fixture.graphqlStatus)
            return
        }
        if (fixture.pageFailure && query.contains("fetchChapterPages(")) {
            send(exchange, """{"errors":[{"message":"fixture page failure"}]}""")
            return
        }
        val response = when {
            exchange.requestMethod == "GET" -> "{\"aboutServer\":{\"version\":\"v2.4.2366\"}}"
            query.contains("setGlobalMeta(") -> {
                fixture.meta[variables["key"]!!.jsonPrimitive.content] = variables["value"]!!.jsonPrimitive.content
                "{\"setGlobalMeta\":{\"meta\":{\"key\":\"fixture\",\"value\":\"fixture\"}}}"
            }
            query.contains("deleteGlobalMeta(") -> {
                fixture.meta.remove(variables["key"]!!.jsonPrimitive.content)
                "{\"deleteGlobalMeta\":{\"clientMutationId\":null}}"
            }
            query.contains("metas(") -> {
                if (fixture.metaReadDelayMillis > 0) Thread.sleep(fixture.metaReadDelayMillis)
                val value = fixture.meta[variables["key"]!!.jsonPrimitive.content]
                "{\"metas\":{\"nodes\":${if (value == null) "[]" else "[{\"value\":\"$value\"}]"}}}"
            }
            query.contains("chapter(id:") -> if (fixture.chapterExists) {
                """{"chapter":{"id":11,"mangaId":3}}"""
            } else {
                """{"chapter":null}"""
            }
            query.contains("manga(id:") -> if (fixture.sourceExists) {
                """{"manga":{"source":{"id":"3"}}}"""
            } else {
                """{"manga":{"source":null}}"""
            }
            query.contains(
                "__type",
            ) -> """{"chapter":{"fields":[{"name":"id"},{"name":"mangaId"},{"name":"lastPageRead"},{"name":"lastReadAt"},{"name":"pageCount"}]},"patch":{"inputFields":[{"name":"lastPageRead"},{"name":"isRead"}]},"mutations":{"fields":[{"name":"fetchChapterPages"},{"name":"updateChapter"}]}}"""
            query.contains(
                "categories(",
            ) -> "{\"categories\":{\"nodes\":[{\"id\":0,\"name\":\"Default\",\"order\":0}]}}"
            query.contains(
                "mangas(",
            ) -> "{\"mangas\":{\"nodes\":[],\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}}}"
            query.contains("aboutServer") -> "{\"aboutServer\":{\"version\":\"v2.4.2366\"}}"
            else -> "{}"
        }
        send(exchange, "{\"data\":$response}")
    }

    @AfterEach fun close() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(0) }
    }

    @Test
    fun `all authentication modes query protected shelves`() = runTest {
        for (mode in SuwayomiAuthMode.entries) {
            val fixture = Fixture(mode)
            val api = api(server(fixture), mode)
            api.validate()
            assertEquals(1, api.categories().size)
            if (mode == SuwayomiAuthMode.UI_LOGIN || mode == SuwayomiAuthMode.SIMPLE_LOGIN) {
                assertEquals(1, fixture.loginCount.get())
            }
        }
    }

    @Test
    fun `concurrent graphql unauthorized errors refresh JWT only once`() = runTest {
        val fixture = Fixture(SuwayomiAuthMode.UI_LOGIN)
        val api = api(server(fixture), fixture.mode)
        api.categories()
        fixture.expired = true
        (1..6).map { async(Dispatchers.IO) { api.categories() } }.awaitAll()
        assertEquals(1, fixture.refreshCount.get())
        assertEquals(1, fixture.loginCount.get())
    }

    @Test
    fun `invalid refresh token falls back to credentials and cookie expiry logs in again`() = runTest {
        for (mode in listOf(SuwayomiAuthMode.UI_LOGIN, SuwayomiAuthMode.SIMPLE_LOGIN)) {
            val fixture = Fixture(mode)
            val api = api(server(fixture), mode)
            api.categories()
            fixture.refreshInvalid = true
            fixture.expired = true
            api.categories()
            assertEquals(2, fixture.loginCount.get())
        }
    }

    @Test
    fun `authentication retries are bounded and do not fall back to public`() = runTest {
        val public = Fixture(SuwayomiAuthMode.BASIC_AUTH)
        val internal = Fixture(SuwayomiAuthMode.BASIC_AUTH)
        val publicUrl = server(public)
        val internalUrl = server(internal)
        val api = api(
            publicUrl,
            public.mode,
            internalUrl,
            ConnectionAddressRouter({ publicUrl }, { internalUrl }, { "wifi" }, "probe"),
        )
        internal.deny = true
        var observed: SuwayomiException.Reason? = null
        try {
            api.categories()
        } catch (
            error: SuwayomiException,
        ) {
            observed = error.reason
        }
        assertEquals(SuwayomiException.Reason.AUTH, observed)
        assertTrue(public.requests.isEmpty())
    }

    @Test
    fun `failed session login never switches to another address`() = runTest {
        for (mode in listOf(SuwayomiAuthMode.UI_LOGIN, SuwayomiAuthMode.SIMPLE_LOGIN)) {
            val public = Fixture(mode)
            val internal = Fixture(mode).apply { denyLogin = true }
            val publicUrl = server(public)
            val internalUrl = server(internal)
            val api = api(
                publicUrl,
                mode,
                internalUrl,
                ConnectionAddressRouter({ publicUrl }, { internalUrl }, { "wifi" }, "probe"),
            )
            var observed: SuwayomiException.Reason? = null
            try {
                api.categories()
            } catch (error: IOException) {
                observed = generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<SuwayomiException>()
                    .firstOrNull()?.reason
            }
            assertEquals(SuwayomiException.Reason.AUTH, observed)
            assertTrue(public.requests.isEmpty())
            assertEquals(1, internal.loginCount.get())
        }
    }

    @Test
    fun `query POST can fall back but mutation POST is never replayed`() = runTest {
        val public = Fixture(SuwayomiAuthMode.NONE)
        val internal = Fixture(SuwayomiAuthMode.NONE)
        val publicUrl = server(public)
        val internalUrl = server(internal)
        val router = ConnectionAddressRouter({ publicUrl }, { internalUrl }, { "wifi" }, "probe")
        val api = api(publicUrl, public.mode, internalUrl, router)
        internal.graphqlStatus = 503
        assertEquals(1, api.categories().size)
        assertEquals(1, public.requests.size)
        val freshRouter = ConnectionAddressRouter({ publicUrl }, { internalUrl }, { "wifi" }, "probe")
        val freshApi = api(publicUrl, public.mode, internalUrl, freshRouter)
        try {
            freshApi.updateChapter(11, 2, false)
        } catch (_: SuwayomiException) { }
        assertEquals(1, public.requests.size)
    }

    @Test
    fun `websocket authorization follows the address that authenticated the session`() = runTest {
        val public = Fixture(SuwayomiAuthMode.UI_LOGIN)
        val internal = Fixture(SuwayomiAuthMode.UI_LOGIN)
        val publicUrl = server(public)
        val internalUrl = server(internal)
        // Wi-Fi prefers the LAN address, so the HTTP session belongs to the internal endpoint.
        val router = ConnectionAddressRouter({ publicUrl }, { internalUrl }, { "wifi" }, "probe")
        val api = api(publicUrl, public.mode, internalUrl, router)

        assertEquals(1, api.categories().size)
        assertTrue(internal.requests.any { it.startsWith("POST") })
        assertTrue(public.requests.none { it.startsWith("POST") })

        val authorization = api.websocketAuthorization()
        assertTrue(!authorization.isNullOrBlank())
        // OkHttp reports websocket URLs in their http form, so this pins the routed address.
        assertEquals(internalUrl + "api/graphql", api.authenticatedWebSocketRequest().url.toString())
    }

    @Test
    fun `same instance marker is cleaned on both success and mismatch`() = runTest {
        for (same in listOf(true, false)) {
            val public = Fixture(SuwayomiAuthMode.NONE)
            val internal = Fixture(SuwayomiAuthMode.NONE, if (same) public.meta else ConcurrentHashMap())
            val api = api(server(public), public.mode, server(internal))
            if (same) {
                withContext(Dispatchers.IO) { api.verifyInternal() }
            } else {
                try {
                    withContext(Dispatchers.IO) { api.verifyInternal() }
                    throw AssertionError("Expected instance mismatch")
                } catch (error: ConnectionAddressVerification.Failure) {
                    assertEquals(ConnectionAddressVerification.Reason.MISMATCH, error.reason)
                }
            }
            assertTrue(public.meta.isEmpty())
            assertTrue(internal.meta.isEmpty())
        }
    }

    @Test
    fun `cancelled dual-address verification still removes its marker`() = runTest {
        val public = Fixture(SuwayomiAuthMode.NONE)
        val internal = Fixture(SuwayomiAuthMode.NONE, public.meta).apply { metaReadDelayMillis = 2000 }
        val client = api(server(public), public.mode, server(internal))
        val verification = launch(Dispatchers.IO) { client.verifyInternal() }
        withContext(Dispatchers.IO) {
            withTimeout(10_000) { while (public.meta.isEmpty()) delay(10) }
        }
        verification.cancelAndJoin()
        assertTrue(public.meta.isEmpty())
    }

    @Test
    fun `subpath page addresses are canonical and outside endpoints cannot receive credentials`() = runTest {
        val fixture = Fixture(SuwayomiAuthMode.BASIC_AUTH)
        val base = server(fixture)
        val api = api(base, fixture.mode)
        assertEquals(base + "api/v1/page/0", api.resourceUrl("/api/v1/page/0"))
        assertThrows(SuwayomiException::class.java) { api.resourceUrl("https://outside.invalid/page") }
        assertThrows(SuwayomiException::class.java) { api.resourceUrl(base + "api/page?token=secret") }
        val root = base.removeSuffix("proxy/")
        assertThrows(SuwayomiException::class.java) {
            api.client.newCall(Request.Builder().url(root + "outside").build()).execute().close()
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `obsolete account cover addresses are rejected before authentication or network`() {
        val fixture = Fixture(SuwayomiAuthMode.UI_LOGIN)
        val base = server(fixture)
        val client = SuwayomiApi(
            OkHttpClient(),
            json,
            base,
            fixture.mode,
            "fixture-user",
            "fixture-password",
            imageScope = "4/current-account",
        ).also { clients += it }
        assertThrows(SuwayomiException::class.java) {
            client.client.newCall(
                Request.Builder().url(base + "api/cover?kohariaScope=4/obsolete-account").build(),
            ).execute().close()
        }
        assertTrue(fixture.requests.isEmpty())
        assertEquals(0, fixture.loginCount.get())
    }

    @Test
    fun `page failures distinguish removed chapters missing extensions and source fetch errors`() = runTest {
        val fixture = Fixture(SuwayomiAuthMode.NONE).apply { pageFailure = true }
        val client = api(server(fixture), fixture.mode)
        for (reason in listOf(
            SuwayomiException.Reason.NOT_FOUND,
            SuwayomiException.Reason.SOURCE,
            SuwayomiException.Reason.PAGES,
        )) {
            fixture.chapterExists = reason != SuwayomiException.Reason.NOT_FOUND
            fixture.sourceExists = reason == SuwayomiException.Reason.PAGES
            var observed: SuwayomiException.Reason? = null
            try {
                client.pages(11)
            } catch (error: SuwayomiException) {
                observed = error.reason
            }
            assertEquals(reason, observed)
        }
    }

    @Test
    fun `server download flag and string long scalars remain remote metadata`() {
        val chapter = json.decodeFromString<SuwayomiChapter>(
            """{"id":11,"mangaId":3,"isDownloaded":true,"lastReadAt":"1780000000","lastPageRead":2}""",
        )
        assertTrue(chapter.isDownloaded)
        assertEquals(1780000000L, chapter.lastReadAt)
        assertFalse(chapter.isRead)
    }
}
