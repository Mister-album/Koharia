package koharia.lanraragi

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class LanraragiApiTest {
    private val requests = CopyOnWriteArrayList<Pair<String, URI>>()
    private val authorizations = CopyOnWriteArrayList<String>()
    private var handler: (String, URI) -> Pair<Int, String> = { _, _ -> 200 to "{}" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests += exchange.requestMethod to exchange.requestURI
            authorizations += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val (status, text) = handler(exchange.requestMethod, exchange.requestURI)
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (status == 204) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                val bytes = text.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
            exchange.close()
        }
        start()
    }
    private val root = "http://127.0.0.1:${server.address.port}/lrr/"
    private val api = LanraragiApi(root, "fixture-key", OkHttpClient(), Json { ignoreUnknownKeys = true })

    @AfterEach
    fun close() {
        api.close()
        server.stop(0)
    }

    @Test
    fun `slow image bodies outlive metadata deadline while retaining cancellation and idle timeout`() {
        server.createContext("/lrr/slow") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.write(1)
                exchange.responseBody.flush()
                Thread.sleep(300)
                runCatching { exchange.responseBody.write(2) }
            } finally {
                exchange.close()
            }
        }
        val network = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS)
            .readTimeout(2, TimeUnit.SECONDS).build()
        val slowApi = LanraragiApi(root, "", network, Json)
        try {
            assertSame(slowApi.client.dispatcher, slowApi.imageClient.dispatcher)
            assertEquals(network.readTimeoutMillis, slowApi.imageClient.readTimeoutMillis)
            slowApi.imageClient.newCall(slowApi.request("slow")).execute().use {
                assertEquals(2, it.body.bytes().size)
            }
            assertThrows(InterruptedIOException::class.java) {
                slowApi.client.newCall(slowApi.request("slow")).execute().use { it.body.bytes() }
            }
        } finally {
            slowApi.close()
        }
    }

    @Test
    fun `auth is base64 bearer and subpath is retained`() = runTest {
        handler = { _, _ -> 200 to info("0.9.70") }
        assertEquals("0.9.70", api.serverInfo().version)
        assertEquals("/lrr/api/info", requests.single().second.path)
        assertEquals("Bearer Zml4dHVyZS1rZXk=", authorizations.single())
    }

    @Test
    fun `legacy and modern tank details paginate all members`() = runTest {
        for (version in listOf("0.9.70", "0.9.80")) {
            val client = LanraragiApi(root, "", OkHttpClient(), Json { ignoreUnknownKeys = true })
            try {
                requests.clear()
                handler = { _, url ->
                    200 to if (url.path.endsWith("/info")) {
                        info(version)
                    } else {
                        val page = url.query.substringAfter("page=").substringBefore('&').toInt()
                        """
                        {"result":{"id":"TANK_1234567890","name":"Collection","archives":["archive$page"]},
                        "total":3,"filtered":1}
                        """.trimIndent()
                    }
                }
                assertEquals(listOf("archive0", "archive1", "archive2"), client.tank("TANK_1234567890").members)
                val calls = requests.drop(1).map { it.second }
                assertEquals(3, calls.size)
                assertTrue(calls.all { it.path.endsWith(if (version == "0.9.80") "/full" else "/TANK_1234567890") })
                assertEquals(version == "0.9.70", calls.all { it.query.contains("include_full_data=true") })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `incomplete tank response cannot publish truncated membership`() {
        handler =
            { _, url ->
                200 to
                    if (url.path.endsWith("/info")) info("0.9.70") else """{"result":{"archives":[]},"total":3}"""
            }
        assertThrows(LanraragiException::class.java) { runTest { api.tank("TANK_1234567890") } }
    }

    @Test
    fun `auth failure does not retry old endpoint`() {
        handler = { _, _ -> 401 to "{}" }
        val error = assertThrows(LanraragiException::class.java) { runTest { api.serverInfo() } }
        assertEquals(LanraragiException.Reason.AUTH, error.reason)
        assertEquals(1, requests.size)
    }

    @Test
    fun `business failure is not treated as successful request`() {
        handler = { _, _ -> 200 to """{"success":0,"error":"fixture"}""" }
        assertThrows(LanraragiException::class.java) { runTest { api.clearNew("archive") } }
    }

    @Test
    fun `empty search supports 204`() = runTest {
        handler = { _, url -> if (url.path.endsWith("/info")) 200 to info("0.9.80") else 204 to "" }
        assertTrue(api.search("empty").isEmpty())
    }

    @Test
    fun `search offsets advance by server page size and are independent`() = runTest {
        handler = handler@{ _, url ->
            if (url.path.endsWith("/info")) return@handler 200 to info("0.9.80")
            val offset = url.query.substringAfter("start=").substringBefore('&').toInt()
            200 to """{"data":[{"arcid":"$offset","title":"Book"}],"recordsFiltered":3,"recordsTotal":3}"""
        }
        assertEquals(listOf("0", "1", "2"), api.search("first").map { it.id })
        assertEquals(listOf("0", "1", "2"), api.search("second").map { it.id })
        assertEquals(6, requests.count { it.second.path.endsWith("/search") })
    }

    @Test
    fun `archive decoding accepts legacy new flag forms and streams batches`() = runTest {
        handler = { _, _ ->
            200 to (0..400).joinToString(prefix = "[", postfix = "]") { index ->
                val flag = listOf("true", "\"true\"", "null", "false")[index % 4]
                """{"arcid":"$index","title":"Book $index","isnew":$flag,"pagecount":2}"""
            }
        }
        val batches = mutableListOf<Int>()
        val flags = mutableListOf<Boolean>()
        api.streamArchives { batch ->
            batches += batch.size
            flags += batch.map { it.isNew }
        }
        assertEquals(listOf(200, 200, 1), batches)
        assertEquals(listOf(true, true, false, false), flags.take(4))
    }

    @Test
    fun `image paths preserve encoding without doubling proxy path`() {
        val relative = "./api/archives/a/page?path=chapter%201%2F01.jpg"
        val expected = root + relative.removePrefix("./")
        assertEquals(expected, api.imageUrl(relative))
        assertEquals(expected, api.imageUrl(relative.removePrefix(".")))
        assertEquals(expected, api.imageUrl("/lrr/" + relative.removePrefix("./")))
        assertEquals(expected, api.imageUrl(expected))
        assertThrows(LanraragiException::class.java) { api.imageUrl("https://example.invalid/image") }
    }

    @Test
    fun `disabled server progress does not send writes`() {
        handler = { _, _ -> 200 to """{"version":"0.9.80","server_tracks_progress":false}""" }
        assertThrows(LanraragiException::class.java) { runTest { api.pushProgress("archive", 1) } }
        assertTrue(requests.all { it.first == "GET" })
    }

    @Test
    fun `version comparison handles future major and patch versions`() {
        assertTrue(LanraragiServerInfo("0.9.80", true, false).modern)
        assertTrue(LanraragiServerInfo("1.0.0", true, false).modern)
        assertFalse(LanraragiServerInfo("0.9.71", true, false).modern)
    }

    @Test
    fun `stale IDs in server counts do not cause overlapping result windows`() = runTest {
        handler = { _, url ->
            200 to if (url.path.endsWith("/info")) {
                info("0.9.81", 100)
            } else {
                """{"data":[{"arcid":"a"},{"arcid":"b"}],"recordsFiltered":3,"recordsTotal":15}"""
            }
        }
        assertEquals(listOf("a", "b"), api.search(category = "SET_1589919074").map { it.id })
        assertEquals(1, requests.count { it.second.path.endsWith("/search") })
    }

    @Test
    fun `an empty metadata window does not hide later result windows`() = runTest {
        handler = { _, url ->
            200 to if (url.path.endsWith("/info")) {
                info("0.9.81", 2)
            } else if (url.query.contains("start=0")) {
                """{"data":[],"recordsFiltered":3}"""
            } else {
                """{"data":[{"arcid":"last"}],"recordsFiltered":3}"""
            }
        }
        assertEquals(listOf("last"), api.search().map { it.id })
    }

    private fun info(version: String, pageSize: Int = 1) = """
        {"version":"$version","server_tracks_progress":true,"authenticated_progress":true,"archives_per_page":$pageSize}
    """.trimIndent()
}
