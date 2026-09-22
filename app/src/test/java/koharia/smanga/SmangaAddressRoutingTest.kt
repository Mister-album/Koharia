package koharia.smanga

import com.sun.net.httpserver.HttpServer
import koharia.connection.ConnectionAddressRouter
import koharia.connection.ConnectionAddressVerification
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class SmangaAddressRoutingTest {
    private data class Seen(
        val method: String,
        val path: String,
        val token: String?,
        val auth: String?,
        val cookie: String?,
    )

    private val publicRequests = CopyOnWriteArrayList<Seen>()
    private val internalRequests = CopyOnWriteArrayList<Seen>()
    private val logins = AtomicInteger()
    private var token = ""
    private var wifi: Any? = "home"
    private var probeStatus = 200
    private var internalStatus = 200
    private var acceptsToken = true
    private var internalUserId = 3
    private var internalOpdsEnabled = true
    private val publicServer = server(publicRequests, false)
    private val internalServer = server(internalRequests, true)
    private val publicAddress = "http://127.0.0.1:${publicServer.address.port}/public/sub/api/"
    private val internalAddress = "http://127.0.0.1:${internalServer.address.port}/lan/api/"
    private val clients = mutableListOf<SmangaApi>()

    private fun api(routed: Boolean = true): SmangaApi = SmangaApi(
        OkHttpClient(),
        Json,
        publicAddress,
        "reader",
        "fixture-password",
        "account-a",
        addressRouter = if (routed) {
            ConnectionAddressRouter(
                { publicAddress },
                { internalAddress },
                { wifi },
                "deploy/status",
                authenticateProbe = false,
            )
        } else {
            null
        },
    ).also { clients += it }

    private fun server(requests: MutableList<Seen>, internal: Boolean): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val request = Seen(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestHeaders.getFirst("token"),
                    exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Cookie"),
                )
                exchange.requestBody.use { it.readBytes() }
                requests += request
                val (status, body) = when {
                    request.path.endsWith("/deploy/status") -> probeStatus to """{"code":200}"""
                    internal && internalStatus != 200 -> internalStatus to """{"code":503}"""
                    request.path.endsWith("/login") -> {
                        token = "fixture-token-${logins.incrementAndGet()}"
                        200 to """{"code":200,"data":{"userId":3,"userName":"reader","token":"$token"}}"""
                    }
                    request.path.contains("/opds") -> when {
                        internal && !internalOpdsEnabled -> 404 to "disabled"
                        request.auth != Credentials.basic("reader", "fixture-password") -> 401 to "Unauthorized"
                        else -> 200 to """<feed xmlns="http://www.w3.org/2005/Atom"/>"""
                    }
                    request.token != token || (internal && !acceptsToken) ->
                        401 to """{"code":1,"status":"token error"}"""
                    request.path.endsWith("/user/me") -> {
                        val id = if (internal) internalUserId else 3
                        200 to """{"code":200,"data":{"userId":$id,"userName":"reader"}}"""
                    }
                    else -> 200 to """{"code":200,"list":[],"count":0}"""
                }
                if (status == 302) exchange.responseHeaders.set("Location", publicAddress + "outside")
                if (status == 503) exchange.responseHeaders.set("Retry-After", "0")
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }

    @AfterEach
    fun close() {
        clients.forEach(SmangaApi::close)
        publicServer.stop(0)
        internalServer.stop(0)
    }

    @Test
    fun `wifi routes login JSON and OPDS across different proxy paths while URLs stay canonical`() = runTest {
        val api = api()
        api.media()
        val cover = api.coverUrl(7)
        api.opdsClient.newCall(Request.Builder().url(cover).build()).execute().use {
            assertEquals(cover, it.request.url.toString())
            assertEquals(200, it.code)
        }
        assertTrue(publicRequests.isEmpty())
        assertEquals(
            listOf("/lan/api/deploy/status", "/lan/api/login", "/lan/api/media", "/lan/api/opds/manga/7/cover"),
            internalRequests.map { it.path },
        )
        assertEquals(token, internalRequests.single { it.path.endsWith("/media") }.token)
        assertEquals(null, internalRequests.single { it.path.endsWith("/media") }.auth)
        assertEquals(null, internalRequests.last().token)
        assertEquals(Credentials.basic("reader", "fixture-password"), internalRequests.last().auth)

        wifi = null
        api.media()
        assertEquals(listOf("/public/sub/api/media"), publicRequests.map { it.path })
        assertEquals(cover, api.coverUrl(7))
        assertEquals(1, logins.get())
    }

    @Test
    fun `first OPDS request probes without Basic token or cookies`() {
        val api = api()
        api.opdsClient.newCall(
            Request.Builder().url(api.pageUrl(8, 1)).header("token", "untrusted")
                .header("Cookie", "untrusted").build(),
        ).execute().close()
        val probe = internalRequests.first()
        assertTrue(probe.path.endsWith("/deploy/status"))
        assertEquals(null, probe.auth)
        assertEquals(null, probe.token)
        assertEquals(null, probe.cookie)
        assertTrue(publicRequests.isEmpty())
        assertEquals(0, logins.get())
    }

    @Test
    fun `unavailable LAN probe uses public and does not probe on each request`() = runTest {
        probeStatus = 503
        val api = api()
        api.media()
        val probes = internalRequests.size
        assertTrue(probes > 0)
        api.media()
        assertEquals(probes, internalRequests.size)
        assertEquals(listOf("POST", "GET", "GET"), publicRequests.map { it.method })
    }

    @Test
    fun `LAN read failures fall back but history writes are never replayed`() = runTest {
        val api = api()
        api.media()
        internalStatus = 503
        val failure = runCatching { api.addHistory(1, 2, 3) }.exceptionOrNull()
        assertTrue(failure is SmangaException)
        assertEquals(1, internalRequests.count { it.path.endsWith("/history") })
        assertTrue(publicRequests.isEmpty())
        api.media()
        assertEquals(listOf("/public/sub/api/media"), publicRequests.map { it.path })
    }

    @Test
    fun `LAN read failure also falls back for OPDS without changing resource URL`() = runTest {
        val api = api()
        api.media()
        internalStatus = 503
        val pdf = api.rawFileRequest(8).url.toString()
        api.opdsClient.newCall(Request.Builder().url(pdf).build()).execute().use {
            assertEquals(200, it.code)
            assertEquals(pdf, it.request.url.toString())
        }
        assertEquals(1, publicRequests.size)
        assertEquals(Credentials.basic("reader", "fixture-password"), publicRequests.single().auth)
        assertEquals(null, publicRequests.single().token)
    }

    @Test
    fun `routed OPDS still rejects arbitrary origins and direct LAN URLs before sending credentials`() {
        val api = api()
        for (url in listOf(internalAddress + "opds/manga/7/cover", publicAddress + "image")) {
            val error = runCatching { api.opdsClient.newCall(Request.Builder().url(url).build()).execute().close() }
                .exceptionOrNull()
            assertTrue(error is SmangaException)
            assertEquals(SmangaException.Reason.ADDRESS, (error as SmangaException).reason)
        }
        assertTrue(publicRequests.isEmpty())
        assertTrue(internalRequests.isEmpty())
    }

    @Test
    fun `LAN redirects are not followed with credentials`() = runTest {
        val api = api()
        api.media()
        internalStatus = 302
        assertTrue(runCatching { api.media() }.isFailure)
        api.opdsClient.newCall(Request.Builder().url(api.coverUrl(7)).build()).execute().use {
            assertEquals(302, it.code)
        }
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun `saving dual addresses proves shared login token and validates OPDS on both without LAN login`() = runTest {
        val account = api(routed = false).validate(internalAddress.removeSuffix("api/"))
        assertEquals(3L, account.id)
        assertEquals(1, logins.get())
        assertEquals(listOf("/lan/api/user/me", "/lan/api/opds"), internalRequests.map { it.path })
        assertEquals(token, internalRequests.first().token)
        assertFalse(internalRequests.any { it.method != "GET" })
    }

    @Test
    fun `another database cannot pass by logging in again with identical credentials`() = runTest {
        acceptsToken = false
        val error = runCatching { api(routed = false).validate(internalAddress) }.exceptionOrNull()
        assertEquals(
            ConnectionAddressVerification.Reason.MISMATCH,
            (error as ConnectionAddressVerification.Failure).reason,
        )
        assertEquals(1, logins.get())
        assertEquals(listOf("/lan/api/user/me"), internalRequests.map { it.path })
    }

    @Test
    fun `a different account or disabled LAN OPDS cannot pass address validation`() = runTest {
        internalUserId = 4
        val mismatch = runCatching { api(routed = false).validate(internalAddress) }.exceptionOrNull()
        assertEquals(
            ConnectionAddressVerification.Reason.MISMATCH,
            (mismatch as ConnectionAddressVerification.Failure).reason,
        )
        internalUserId = 3
        internalOpdsEnabled = false
        val unavailable = runCatching { api(routed = false).validate(internalAddress) }.exceptionOrNull()
        assertEquals(SmangaException.Reason.OPDS_DISABLED, (unavailable as SmangaException).reason)
    }
}
