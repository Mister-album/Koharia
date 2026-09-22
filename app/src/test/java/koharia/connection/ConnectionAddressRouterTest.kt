package koharia.connection

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionAddressRouterTest {
    private val publicRequests = CopyOnWriteArrayList<String>()
    private val internalRequests = CopyOnWriteArrayList<String>()
    private var internalStatus = 200
    private var probeStatus = 200
    private var wifi: Any? = "home"
    private val publicServer = server(publicRequests) { 200 }
    private val internalServer = server(internalRequests) { if (it.endsWith("/probe")) probeStatus else internalStatus }
    private val public = "http://127.0.0.1:${publicServer.address.port}/public/"
    private var internal = "http://127.0.0.1:${internalServer.address.port}/lan/"
    private val router = ConnectionAddressRouter({ public }, { internal }, { wifi }, "probe")
    private val client = OkHttpClient.Builder().addInterceptor(router)
        .addNetworkInterceptor(ConnectionAddressRouter.redirectGuard).build()

    @AfterEach
    fun close() {
        publicServer.stop(0)
        internalServer.stop(0)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `wifi routes reads and range headers without changing canonical response URL`() {
        val request = Request.Builder().url(public + "book/page?number=2").header("Range", "bytes=3-9").build()
        client.newCall(request).execute().use {
            assertEquals(request.url, it.request.url)
            assertEquals("bytes=3-9", it.header("Received-Range"))
        }
        assertEquals(listOf("GET /lan/probe", "GET /lan/book/page?number=2"), internalRequests)
        assertTrue(publicRequests.isEmpty())
    }

    @Test
    fun `cached only requests never probe or access network`() {
        client.newCall(Request.Builder().url(public + "book").header("Cache-Control", "only-if-cached").build())
            .execute().use { assertEquals(504, it.code) }
        assertTrue(publicRequests.isEmpty())
        assertTrue(internalRequests.isEmpty())
    }

    @Test
    fun `cellular and blank optional address use only public`() {
        wifi = null
        get()
        wifi = "home"
        internal = ""
        get()
        assertEquals(2, publicRequests.size)
        assertTrue(internalRequests.isEmpty())
    }

    @Test
    fun `unreachable internal falls back and avoids repeated probes until network changes`() {
        probeStatus = 503
        get()
        get()
        assertEquals(1, internalRequests.size)
        assertEquals(2, publicRequests.size)
        wifi = "other wifi"
        probeStatus = 200
        get()
        assertEquals(3, internalRequests.size)
        assertEquals(2, publicRequests.size)
    }

    @Test
    fun `lost internal connection falls back to public`() {
        get()
        internalServer.stop(0)
        get()
        assertEquals(1, publicRequests.size)
    }

    @Test
    fun `write is not replayed after server failure`() {
        internalStatus = 503
        client.newCall(Request.Builder().url(public + "progress").put("{}".toRequestBody()).build())
            .execute().use { assertEquals(503, it.code) }
        assertTrue(publicRequests.isEmpty())
        assertEquals(listOf("GET /lan/probe", "PUT /lan/progress"), internalRequests)
    }

    @Test
    fun `failed probe selects public before writing`() {
        probeStatus = 503
        client.newCall(Request.Builder().url(public + "progress").put("{}".toRequestBody()).build())
            .execute().close()
        assertEquals(listOf("GET /lan/probe"), internalRequests)
        assertEquals(listOf("PUT /public/progress"), publicRequests)
    }

    @Test
    fun `read transport failure falls back but authentication response does not`() {
        internalStatus = 401
        get()
        assertTrue(publicRequests.isEmpty())
        internalStatus = 503
        get()
        assertEquals(1, publicRequests.size)
    }

    @Test
    fun `overlapping base paths use the more specific prefix`() {
        val nestedInternal = ConnectionAddressRouter(
            { "https://public.test/lrr/" },
            { "http://lan.test/lrr/internal/" },
            { "wifi" },
            "probe",
        )
        assertEquals(
            "https://public.test/lrr/api/image/1",
            nestedInternal.canonicalResourcePath("/lrr/internal/api/image/1"),
        )
        val nestedPublic = ConnectionAddressRouter(
            { "https://public.test/lrr/public/" },
            { "http://lan.test/lrr/" },
            { "wifi" },
            "probe",
        )
        assertEquals("/lrr/public/api/image/1", nestedPublic.canonicalResourcePath("/lrr/public/api/image/1"))
        assertEquals(
            "https://public.test/lrr/public/api/image/1",
            nestedPublic.canonicalResourcePath("/lrr/api/image/1"),
        )
    }

    @Test
    fun `base path boundaries and embedded internal URLs remain canonical`() {
        val base = checkNotNull(ConnectionAddressRouter.normalize(public))
        assertFalse(
            ConnectionAddressRouter.owns(
                base,
                checkNotNull(ConnectionAddressRouter.normalize(public + "../public-other")),
            ),
        )
        val local = checkNotNull(ConnectionAddressRouter.normalize(internal + "image"))
        assertEquals(public + "image/", router.canonicalUrl(local).toString())
        assertEquals(null, ConnectionAddressRouter.normalize("https://user:password@host"))
    }

    private fun get() = client.newCall(Request.Builder().url(public + "book").build()).execute().close()

    private fun server(requests: MutableList<String>, status: (String) -> Int): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI}"
                exchange.requestHeaders.getFirst("Range")?.let { exchange.responseHeaders.add("Received-Range", it) }
                exchange.requestBody.close()
                val bytes = "{}".toByteArray()
                exchange.sendResponseHeaders(status(exchange.requestURI.path), bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
}
