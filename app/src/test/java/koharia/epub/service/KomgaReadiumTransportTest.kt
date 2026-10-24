package koharia.epub.service

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionAddressRouter
import koharia.source.komga.withKomgaAuthentication
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import java.net.InetSocketAddress

class KomgaReadiumTransportTest {
    private var status = 200
    private var receivedRange: String? = null
    private var receivedAuth: String? = null
    private var receivedAuthValues: List<String> = emptyList()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            receivedRange = exchange.requestHeaders.getFirst("Range")
            receivedAuth = exchange.requestHeaders.getFirst("Authorization")
            receivedAuthValues = exchange.requestHeaders["Authorization"].orEmpty()
            exchange.responseHeaders.add("Content-Type", "application/xhtml+xml")
            val bytes = "<html>reader</html>".toByteArray()
            exchange.sendResponseHeaders(if (receivedAuthValues.size > 1) 400 else status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val base = "http://127.0.0.1:1/public/"
    private val internal = "http://127.0.0.1:${server.address.port}/internal/"
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().withKomgaAuthentication("Credentials", "reader", "fixture", ""))
        }
        .addInterceptor(ConnectionAddressRouter({ base }, { internal }, { "wifi" }, "probe"))
        .addNetworkInterceptor(ConnectionAddressRouter.redirectGuard).build()
    private val transport = KomgaReadiumTransport {
        KomgaReadiumTransport.Connection(base, client, Headers.headersOf("Authorization", "Basic fixture"))
    }

    @AfterEach
    fun close() {
        server.stop(0)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `readium resources use internal transport and preserve range and canonical URL`() = runTest {
        val url = mockk<AbsoluteUrl>()
        every { url.toString() } returns base + "page.xhtml"
        val request = HttpRequest(url, headers = mapOf("Range" to listOf("bytes=0-18")), extras = mockk())
        val result = transport.stream(request)
        val response = checkNotNull(result.getOrNull()) { result.failureOrNull().toString() }
        assertEquals(url, response.response.url)
        assertEquals("<html>reader</html>", response.body.bufferedReader().use { it.readText() })
        assertEquals("bytes=0-18", receivedRange)
        assertEquals(Credentials.basic("reader", "fixture"), receivedAuth)
        assertEquals(listOf(receivedAuth), receivedAuthValues)
    }

    @Test
    fun `readium preserves server error response`() = runTest {
        val url = mockk<AbsoluteUrl>()
        every { url.toString() } returns base + "page.xhtml"
        val request = HttpRequest(url, extras = mockk())
        transport.stream(request).getOrNull()?.body?.close()
        status = 404
        val result = transport.stream(request)
        assertNotNull(result.failureOrNull() as? HttpError.ErrorResponse)
    }
}
