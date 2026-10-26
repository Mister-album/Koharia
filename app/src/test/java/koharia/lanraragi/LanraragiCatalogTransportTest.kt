package koharia.lanraragi

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LanraragiCatalogTransportTest {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@LanraragiCatalogTransportTest.executor
        start()
    }
    private val clients = mutableListOf<LanraragiApi>()
    private val releaseBody = CountDownLatch(1)

    private fun api(network: OkHttpClient = OkHttpClient()) = LanraragiApi(
        "http://127.0.0.1:${server.address.port}/",
        "fixture",
        network,
        Json,
    ).also(clients::add)

    @AfterEach
    fun close() {
        releaseBody.countDown()
        clients.forEach { it.close() }
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun `catalogue waits for slow headers and local batches beyond ordinary request deadline`() = runBlocking<Unit> {
        val payload = (0..400).joinToString(prefix = "[", postfix = "]") {
            """{"arcid":"$it","title":"${"x".repeat(200)}"}"""
        }.toByteArray()
        server.createContext("/api/archives") { exchange ->
            exchange.use {
                Thread.sleep(300)
                it.sendResponseHeaders(200, payload.size.toLong())
                runCatching { it.responseBody.write(payload) }
            }
        }
        val network = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS)
            .readTimeout(150, TimeUnit.MILLISECONDS).build()
        val api = api(network)
        val batches = mutableListOf<Int>()
        api.streamArchives {
            Thread.sleep(200) // Simulate staged DB/progress work while the response is still open.
            batches += it.size
        }
        assertEquals(listOf(200, 200, 1), batches)
        assertSame(api.client.dispatcher, api.catalogClient.dispatcher)
        assertEquals(0, api.catalogClient.callTimeoutMillis)
        assertEquals(180_000, api.catalogClient.readTimeoutMillis)
        assertEquals(150, api.client.callTimeoutMillis)
        assertEquals(150, api.client.readTimeoutMillis)
        assertEquals(150, api.imageClient.readTimeoutMillis)
        assertThrows(IOException::class.java) {
            api.client.newCall(api.request("api/archives")).execute().use { it.body.bytes() }
        }
    }

    private fun stalledBody(): CountDownLatch {
        val started = CountDownLatch(1)
        server.createContext("/api/archives") { exchange ->
            exchange.use {
                it.sendResponseHeaders(200, 0)
                it.responseBody.write("[".toByteArray())
                it.responseBody.flush()
                started.countDown()
                releaseBody.await(5, TimeUnit.SECONDS)
            }
        }
        return started
    }

    private fun bodySignalClient(reading: CountDownLatch) = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) {
                reading.countDown()
            }
        }).build()

    @Test
    fun `all catalogue stages opt into longer waits while ordinary metadata keeps its timeout`() = runBlocking<Unit> {
        server.createContext("/") { exchange ->
            exchange.use {
                Thread.sleep(300)
                val payload = when (it.requestURI.path) {
                    "/api/info" -> """{"version":"0.9.80","archives_per_page":100}"""
                    "/api/archives/untagged" -> """["a"]"""
                    "/api/tankoubons" -> """{"result":[{"id":"tank"}],"total":1}"""
                    "/api/tankoubons/tank/full" -> """{"result":{"name":"Fixture","archives":["a"]},"total":1}"""
                    "/api/categories" -> """[{"id":"category","name":"Fixture","search":"tag"}]"""
                    "/api/search" -> """{"data":[{"arcid":"a"}],"recordsFiltered":1}"""
                    else -> error("Unexpected fixture endpoint")
                }.toByteArray()
                it.sendResponseHeaders(200, payload.size.toLong())
                runCatching { it.responseBody.write(payload) }
            }
        }
        val api = api(OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build())
        assertEquals("0.9.80", api.serverInfo(true, catalogSync = true).version)
        assertEquals(setOf("a"), api.untagged(catalogSync = true))
        assertEquals(listOf("a"), api.tanks(catalogSync = true).single().members)
        assertEquals("category", api.categories(catalogSync = true).single().id)
        assertEquals("a", api.search(category = "category", catalogSync = true).single().id)
        assertThrows(IOException::class.java) { runBlocking { api.categories() } }
    }

    @Test
    fun `cancelling sync interrupts a stalled response body without waiting three minutes`() = runBlocking<Unit> {
        val started = stalledBody()
        val reading = CountDownLatch(1)
        val api = api(bodySignalClient(reading))
        val loading = async(Dispatchers.IO) { api.streamArchives { error("No complete batch expected") } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { loading.cancelAndJoin() }
        assertTrue(loading.isCancelled)
    }

    @Test
    fun `closing connection cancels catalogue body and rejects subsequent requests`() = runBlocking<Unit> {
        val started = stalledBody()
        val reading = CountDownLatch(1)
        val api = api(bodySignalClient(reading))
        val loading = async(Dispatchers.IO) { runCatching { api.streamArchives {} } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertTrue(reading.await(2, TimeUnit.SECONDS))
        api.close()
        assertTrue(withTimeout(2_000) { loading.await() }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { api.streamArchives {} }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `catalogue still propagates idle read timeout`() {
        stalledBody()
        // Shorten only the socket timeout in this fixture so the test does not wait 3 minutes.
        val api = api(
            OkHttpClient.Builder().addInterceptor {
                it.withReadTimeout(150, TimeUnit.MILLISECONDS).proceed(it.request())
            }.build(),
        )
        val failure = assertThrows(IOException::class.java) { runBlocking { api.streamArchives {} } }
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is SocketTimeoutException })
    }

    @Test
    fun `large catalogue is consumed completely in bounded batches`() = runBlocking<Unit> {
        val total = 97_223
        server.createContext("/api/archives") { exchange ->
            exchange.use {
                it.sendResponseHeaders(200, 0)
                it.responseBody.bufferedWriter().use { writer ->
                    writer.write("[")
                    repeat(total) { index ->
                        if (index > 0) writer.write(",")
                        writer.write("""{"arcid":"$index","title":"Fixture $index"}""")
                    }
                    writer.write("]")
                }
            }
        }
        var consumed = 0
        api().streamArchives { batch ->
            assertTrue(batch.size in 1..200)
            batch.forEach { entry -> assertEquals((consumed++).toString(), entry.id) }
        }
        assertEquals(total, consumed)
    }
}
