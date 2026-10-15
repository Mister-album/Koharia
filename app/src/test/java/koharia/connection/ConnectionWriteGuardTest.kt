package koharia.connection

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConnectionWriteGuardTest {
    private val paused = AtomicBoolean(false)
    private val requests = AtomicInteger()
    private val dropNext = AtomicBoolean(true)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests.incrementAndGet()
            if (exchange.requestURI.path == "/pause") paused.set(true)
            exchange.requestBody.close()
            if (exchange.requestURI.path == "/lost" && dropNext.getAndSet(false)) {
                exchange.close()
                return@createContext
            }
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/"
    private val client = OkHttpClient().withConnectionWriteGuard(paused::get)

    @AfterEach
    fun close() {
        server.stop(0)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `retained and derived clients stop all mutation methods without sending`() {
        client.newCall(Request.Builder().url(url).post(byteArrayOf().toRequestBody()).build()).execute().close()
        paused.set(true)
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "MOVE", "MKCOL")) {
            val request = Request.Builder().url(url).method(method, byteArrayOf().toRequestBody()).build()
            assertThrows(ConnectionWritesPaused::class.java) { client.newBuilder().build().newCall(request).execute() }
        }
        assertEquals(1, requests.get())
    }

    @Test
    fun `paused connection still authenticates and reads via explicitly read only posts`() {
        paused.set(true)
        client.newCall(Request.Builder().url(url).build()).execute().close()
        client.newCall(Request.Builder().url(url).post(byteArrayOf().toRequestBody()).connectionReadOnly().build())
            .execute().close()
        assertEquals(2, requests.get())
    }

    @Test
    fun `response arriving after pause cannot acknowledge mutation`() {
        val request = Request.Builder().url(url + "pause").post(byteArrayOf().toRequestBody()).build()
        assertThrows(ConnectionWritesPaused::class.java) { client.newCall(request).execute() }
        assertEquals(1, requests.get())
    }

    @ParameterizedTest
    @ValueSource(strings = ["POST", "PUT", "PATCH", "DELETE", "MOVE", "MKCOL"])
    fun `committed mutation with lost response is not transparently replayed`(method: String) {
        client.newCall(Request.Builder().url(url).build()).execute().close()
        val request = Request.Builder().url(url + "lost").method(method, ByteArray(0).toRequestBody()).build()
        assertThrows(IOException::class.java) { client.newBuilder().build().newCall(request).execute().close() }
        assertEquals(2, requests.get(), "one warm read and one committed mutation")
        client.newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals(3, requests.get(), "a later explicit read remains available")
    }
}
