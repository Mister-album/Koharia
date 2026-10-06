@file:Suppress("ktlint:standard:max-line-length")

package koharia.storage

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StorageProtocolTest {
    @Test fun `paths never cross roots`() {
        for (path in listOf("../x", "a/../b", "a\\b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) {
                StoragePath.normalize(path)
            }
        }
        assertEquals("中文/a b", StoragePath.normalize("/中文/a b/"))
        assertEquals("a/b", StoragePath.child("a", "b"))
    }

    @Test fun `DAV namespace and UTF8 paths are preserved`() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/%E4%B8%AD%E6%96%87.cbz</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>12</d:getcontentlength><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        val entry = WebDavStorageBackend.parseProperties(
            "https://example.test/dav/".toHttpUrl(),
            xml.toByteArray(),
        ).single()
        assertEquals("中文.cbz", entry.path)
        assertEquals(12, entry.size)
        assertEquals("\"v1\"", entry.version)
        assertFalse(entry.directory)
    }

    @Test fun `DAV refuses entity declarations and responses outside root`() {
        val base = "https://example.test/dav/".toHttpUrl()
        val entity = """<!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///missing">]><root>&xxe;</root>"""
        assertThrows(Exception::class.java) { WebDavStorageBackend.parseProperties(base, entity.toByteArray()) }
        val escaped = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/other/book.cbz</d:href></d:response></d:multistatus>"""
        assertThrows(StorageFailure::class.java) { WebDavStorageBackend.parseProperties(base, escaped.toByteArray()) }
    }

    @Test fun `DAV range request is conditional and rejects incorrect content range`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-4/10").setBody("abc"),
            )
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/dav/").toString(), "", "")
            val entry = StorageEntry("book.cbz", false, 10, version = "\"v1\"")
            assertArrayEquals("abc".toByteArray(), backend.read(entry, 2, 3))
            val request = server.takeRequest()
            assertEquals("bytes=2-4", request.getHeader("Range"))
            assertEquals("\"v1\"", request.getHeader("If-Match"))
            server.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/10").setBody("abc"),
            )
            val error = assertThrows(StorageFailure::class.java) { runBlocking { backend.read(entry, 2, 3) } }
            assertEquals(StorageFailure.Reason.PROTOCOL, error.reason)
        }
    }

    @Test fun `endpoint probe separates rejected credentials from a non-DAV root`() = runBlocking {
        MockWebServer().use { server ->
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/").toString(), "user", "secret")
            server.enqueue(MockResponse().setResponseCode(401))
            val rejected =
                assertThrows(StorageFailure::class.java) { runBlocking { backend.checkEndpointReachable() } }
            assertEquals(StorageFailure.Reason.AUTH, rejected.reason)
            server.enqueue(MockResponse().setResponseCode(404))
            backend.checkEndpointReachable()
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html>index</html>"))
            backend.checkEndpointReachable()
            server.enqueue(MockResponse().setResponseCode(403))
            backend.checkEndpointReachable()
            val request = server.takeRequest()
            assertEquals("0", request.getHeader("Depth"))
            assertTrue(request.getHeader("Authorization").orEmpty().startsWith("Basic "))
        }
    }

    @Test fun `a server ignoring ranges requests explicit complete-file fallback`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("0123456789"))
            val backend = WebDavStorageBackend(OkHttpClient(), server.url("/").toString(), "", "")
            val error =
                assertThrows(StorageFailure::class.java) {
                    runBlocking { backend.read(StorageEntry("b", false, 10), 2, 3) }
                }
            assertEquals(StorageFailure.Reason.UNSUPPORTED, error.reason)
        }
    }
}

private class MockResponse {
    var code = 200
    var body = ""
    val headers = mutableMapOf<String, String>()
    fun setResponseCode(code: Int) = apply { this.code = code }
    fun setHeader(name: String, value: String) = apply { headers[name] = value }
    fun setBody(body: String) = apply { this.body = body }
}

private class MockWebServer : java.io.Closeable {
    private val responses = java.util.concurrent.LinkedBlockingQueue<MockResponse>()
    private val requests = java.util.concurrent.LinkedBlockingQueue<com.sun.net.httpserver.Headers>()
    private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            requests.add(exchange.requestHeaders)
            val response =
                responses.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: MockResponse().setResponseCode(500)
            response.headers.forEach { (key, value) -> exchange.responseHeaders.set(key, value) }
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }
    fun enqueue(response: MockResponse) {
        responses.add(response)
    }
    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    fun takeRequest(): com.sun.net.httpserver.Headers = checkNotNull(
        requests.poll(5, java.util.concurrent.TimeUnit.SECONDS),
    )
    override fun close() {
        server.stop(0)
    }
}

private fun com.sun.net.httpserver.Headers.getHeader(name: String) = getFirst(name)
