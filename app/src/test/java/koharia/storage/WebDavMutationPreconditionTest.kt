package koharia.storage

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class WebDavMutationPreconditionTest {
    @Test
    fun `weak and absent validators use an exclusive lease through the mutation`() = runBlocking {
        for (validator in listOf("W/\"v1\"", null)) {
            for (move in listOf(true, false)) {
                Dav(validator).use { dav ->
                    dav.externalChangeAfterStat = true
                    if (move) dav.backend.move(dav.entry(), "target") else dav.backend.delete(dav.entry())
                    assertEquals(1, dav.blockedExternalChanges)
                    assertFalse(dav.exists)
                    assertEquals(move, dav.moved)
                    assertEquals(listOf("LOCK", "PROPFIND", if (move) "MOVE" else "DELETE", "UNLOCK"), dav.methods)
                    assertEquals("*", dav.lockIfMatch)
                    assertEquals("(<urn:uuid:fixture-lock>)", dav.mutationIf)
                    assertEquals("no-cache", dav.cacheControl)
                    assertEquals("no-cache", dav.pragma)
                    assertEquals("0", dav.lockDepth)
                }
            }
        }
    }

    @Test
    fun `a change before the lock is acquired is rejected and the lease is released`() = runBlocking {
        Dav().use { dav ->
            dav.changeBeforeLock = true
            val error = assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.delete(dav.entry()) } }
            assertEquals(StorageFailure.Reason.CONFLICT, error.reason)
            assertTrue(dav.exists)
            assertEquals(listOf("LOCK", "PROPFIND", "UNLOCK"), dav.methods)
            assertFalse(dav.locked)
        }
    }

    @Test
    fun `expired lease fails server preconditions instead of sending an unconditional move`() = runBlocking {
        Dav().use { dav ->
            dav.expireAfterStat = true
            val error =
                assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.move(dav.entry(), "target") } }
            assertEquals(StorageFailure.Reason.CONFLICT, error.reason)
            assertTrue(dav.exists)
            assertFalse(dav.moved)
            assertEquals(1, dav.methods.count { it == "MOVE" })
        }
    }

    @Test
    fun `unsupported locking never authorizes copy and delete fallback`() = runBlocking {
        Dav().use { dav ->
            dav.lockStatus = 405
            val error =
                assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.move(dav.entry(), "target") } }
            assertEquals(StorageFailure.Reason.UNVERIFIED, error.reason)
            assertEquals(listOf("LOCK"), dav.methods)
            assertTrue(dav.exists)
        }
    }

    @Test
    fun `unbounded or shared lease is released without modifying the resource`() = runBlocking {
        for (shared in listOf(false, true)) {
            Dav().use { dav ->
                if (shared) dav.scope = "shared" else dav.timeout = "Infinite"
                val error = assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.delete(dav.entry()) } }
                assertEquals(StorageFailure.Reason.UNVERIFIED, error.reason)
                assertEquals(listOf("LOCK", "UNLOCK"), dav.methods)
                assertTrue(dav.exists)
                assertFalse(dav.locked)
            }
        }
    }

    @Test
    fun `strong file validators still reject replacement atomically without a lock`() = runBlocking {
        Dav("\"v1\"").use { dav ->
            dav.externalChangeAfterStat = true
            val error = assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.delete(dav.entry()) } }
            assertEquals(StorageFailure.Reason.CONFLICT, error.reason)
            assertTrue(dav.exists)
            assertEquals(listOf("PROPFIND", "DELETE"), dav.methods)
            assertEquals("\"v1\"", dav.mutationIfMatch)
        }
    }

    @Test
    fun `collection deletion checks emptiness inside a depth infinity lease even with a strong etag`() = runBlocking {
        Dav("\"v1\"", directory = true).use { dav ->
            dav.hasChild = true
            val error = assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.delete(dav.entry()) } }
            assertEquals(StorageFailure.Reason.CONFLICT, error.reason)
            assertEquals("infinity", dav.lockDepth)
            assertEquals(listOf("LOCK", "PROPFIND", "PROPFIND", "UNLOCK"), dav.methods)
            assertTrue(dav.exists)
        }
    }

    @Test
    fun `validation errors release the lease and ambiguous moves are not replayed`() = runBlocking {
        Dav().use { dav ->
            dav.statStatus = 500
            assertThrows(StorageFailure::class.java) { runBlocking { dav.backend.delete(dav.entry()) } }
            assertFalse(dav.locked)
            assertEquals(listOf("LOCK", "PROPFIND", "UNLOCK"), dav.methods)
        }
        Dav().use { dav ->
            dav.loseMutationResponse = true
            assertThrows(IOException::class.java) { runBlocking { dav.backend.move(dav.entry(), "target") } }
            assertEquals(1, dav.methods.count { it == "MOVE" })
            assertTrue(dav.moved)
            assertFalse(dav.exists)
        }
    }

    @Test
    fun `an etag list cannot be used to broaden a destructive precondition`() = runBlocking {
        Dav("\"old\", \"new\"").use { dav ->
            dav.backend.delete(dav.entry())
            assertEquals("LOCK", dav.methods.first())
            assertEquals("(<urn:uuid:fixture-lock>)", dav.mutationIf)
        }
    }

    @Test
    fun `bare lock URI is normalized and timeout units are case insensitive`() = runBlocking {
        Dav().use { dav ->
            dav.bareToken = true
            dav.timeout = "second-60"
            dav.backend.delete(dav.entry())
            assertEquals("(<urn:uuid:fixture-lock>)", dav.mutationIf)
            assertFalse(dav.exists)
        }
    }

    private class Dav(private val originalTag: String? = "W/\"v1\"", val directory: Boolean = false) : Closeable {
        val methods = CopyOnWriteArrayList<String>()

        @Volatile var tag = originalTag

        @Volatile var locked = false

        @Volatile var exists = true

        @Volatile var moved = false
        var externalChangeAfterStat = false
        var changeBeforeLock = false
        var expireAfterStat = false
        var blockedExternalChanges = 0
        var lockStatus = 200
        var statStatus = 207
        var scope = "exclusive"
        var timeout = "Second-60"
        var hasChild = false
        var loseMutationResponse = false
        var bareToken = false
        var lockIfMatch: String? = null
        var lockDepth: String? = null
        var mutationIf: String? = null
        var mutationIfMatch: String? = null
        var cacheControl: String? = null
        var pragma: String? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> handle(exchange) }
            start()
        }
        val backend = WebDavStorageBackend(OkHttpClient(), "http://127.0.0.1:${server.address.port}/", "", "")

        fun entry() = StorageEntry("source", directory, if (directory) 0 else 7, version = originalTag ?: "7:0")

        private fun handle(exchange: HttpExchange) {
            methods.add(exchange.requestMethod)
            exchange.requestBody.use { it.readBytes() }
            when (exchange.requestMethod) {
                "LOCK" -> {
                    lockIfMatch = exchange.requestHeaders.getFirst("If-Match")
                    lockDepth = exchange.requestHeaders.getFirst("Depth")
                    if (lockStatus != 200) return respond(exchange, lockStatus)
                    if (changeBeforeLock) tag = "W/\"v2\""
                    locked = true
                    exchange.responseHeaders.add(
                        "Lock-Token",
                        if (bareToken) "urn:uuid:fixture-lock" else "<urn:uuid:fixture-lock>",
                    )
                    respond(
                        exchange,
                        200,
                        """<d:prop xmlns:d="DAV:"><d:lockdiscovery><d:activelock>
                            <d:lockscope><d:$scope/></d:lockscope><d:locktype><d:write/></d:locktype>
                            <d:depth>$lockDepth</d:depth><d:timeout>$timeout</d:timeout>
                            <d:locktoken><d:href>urn:uuid:fixture-lock</d:href></d:locktoken>
                            </d:activelock></d:lockdiscovery></d:prop>""",
                    )
                }
                "PROPFIND" -> {
                    val xml = properties()
                    if (externalChangeAfterStat) {
                        if (locked) blockedExternalChanges++ else tag = "\"v2\""
                    }
                    if (expireAfterStat) locked = false
                    respond(exchange, statStatus, xml)
                }
                "MOVE", "DELETE" -> {
                    mutationIf = exchange.requestHeaders.getFirst("If")
                    mutationIfMatch = exchange.requestHeaders.getFirst("If-Match")
                    cacheControl = exchange.requestHeaders.getFirst("Cache-Control")
                    pragma = exchange.requestHeaders.getFirst("Pragma")
                    val accepted = if (mutationIf != null) {
                        locked && mutationIf == "(<urn:uuid:fixture-lock>)"
                    } else {
                        mutationIfMatch == null || mutationIfMatch == tag
                    }
                    if (!accepted) return respond(exchange, 412)
                    exists = false
                    moved = exchange.requestMethod == "MOVE"
                    locked = false
                    if (loseMutationResponse) exchange.close() else respond(exchange, 204)
                }
                "UNLOCK" -> {
                    locked = false
                    respond(exchange, 204)
                }
                else -> respond(exchange, 500)
            }
        }

        private fun properties(): String {
            val etag = tag?.let { "<d:getetag>$it</d:getetag>" }.orEmpty()
            val type = if (directory) "<d:collection/>" else ""
            val child = if (hasChild) {
                """<d:response><d:href>/source/child</d:href><d:propstat><d:prop>
                    <d:resourcetype/><d:getcontentlength>1</d:getcontentlength><d:getetag>"child"</d:getetag>
                    </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
            } else {
                ""
            }
            return """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/source</d:href>
                <d:propstat><d:prop><d:resourcetype>$type</d:resourcetype><d:getcontentlength>7</d:getcontentlength>
                $etag</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>$child</d:multistatus>"""
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String = "") {
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (status != 204) it.write(bytes) }
            exchange.close()
        }

        override fun close() {
            server.stop(0)
            backend.close()
        }
    }
}
