package koharia.connection

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ConnectionAddressVerificationTest {
    private val publicStore = ConcurrentHashMap<String, JsonObject>()
    private var internalStore = publicStore
    private val requests = CopyOnWriteArrayList<String>()
    private var rejectDelete = false
    private var delayWrite = false
    private val writeStarted = CompletableDeferred<Unit>()
    private var rejectInternalAuth = false
    private var rejectPublicAuth = false
    private var malformedAuthResponse = false
    private var redirectInternal = false
    private var blockInternal = false
    private val internalRead = CompletableDeferred<Unit>()
    private val publicServer = server(false)
    private val internalServer = server(true)
    private val public = "http://127.0.0.1:${publicServer.address.port}/"
    private val internal = "http://127.0.0.1:${internalServer.address.port}/"
    private val client = OkHttpClient()
    private val verification = ConnectionAddressVerification(client)

    @AfterEach
    fun close() {
        publicServer.stop(0)
        internalServer.stop(0)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `komga shared server passes and removes only its marker`() = runBlocking {
        publicStore["existing.setting"] = buildJsonObject { put("value", "keep") }
        verify(ConnectionAddressVerification.Provider.KOMGA)
        assertEquals(setOf("existing.setting"), publicStore.keys)
        assertTrue(requests.any { it.startsWith("internal GET") })
    }

    @Test
    fun `different komga server fails and cleans public marker`() = runBlocking {
        internalStore = ConcurrentHashMap()
        assertEquals(
            ConnectionAddressVerification.Reason.MISMATCH,
            failure(ConnectionAddressVerification.Provider.KOMGA),
        )
        assertTrue(publicStore.isEmpty())
        assertTrue(internalStore.isEmpty())
    }

    @Test
    fun `lanraragi accepts credential on both independent servers without writes`() = runBlocking {
        internalStore = ConcurrentHashMap()
        verify(ConnectionAddressVerification.Provider.LANRARAGI)
        assertEquals(
            listOf("public GET /api/plugins/metadata", "internal GET /api/plugins/metadata"),
            requests.toList(),
        )
        assertTrue(publicStore.isEmpty())
        assertTrue(internalStore.isEmpty())
    }

    @Test
    fun `lanraragi rejects credential invalid on internal server`() = runBlocking {
        rejectInternalAuth = true
        assertAuthFailure()
    }

    @Test
    fun `lanraragi rejects credential invalid on public server`() = runBlocking {
        rejectPublicAuth = true
        assertAuthFailure()
    }

    @Test
    fun `lanraragi rejects redirected authentication`() = runBlocking {
        redirectInternal = true
        assertAuthFailure()
    }

    @Test
    fun `lanraragi rejects unexpected successful response`() = runBlocking {
        malformedAuthResponse = true
        assertAuthFailure()
    }

    private suspend fun assertAuthFailure() {
        assertEquals(
            ConnectionAddressVerification.Reason.AUTHENTICATION,
            failure(ConnectionAddressVerification.Provider.LANRARAGI),
        )
        assertTrue(requests.all { " GET /api/plugins/metadata" in it })
        assertTrue(publicStore.isEmpty())
    }

    @Test
    fun `cleanup failure cannot return verification success`() = runBlocking {
        rejectDelete = true
        assertEquals(
            ConnectionAddressVerification.Reason.CLEANUP,
            failure(ConnectionAddressVerification.Provider.KOMGA),
        )
        assertEquals(1, publicStore.size)
    }

    @Test
    fun `redirect cannot silently verify the public address again`() = runBlocking {
        redirectInternal = true
        assertEquals(
            ConnectionAddressVerification.Reason.UNAVAILABLE,
            failure(ConnectionAddressVerification.Provider.KOMGA),
        )
        assertTrue(publicStore.isEmpty())
    }

    @Test
    fun `cancellation after write still cleans marker`() = runBlocking {
        blockInternal = true
        val job = launch(Dispatchers.IO) { verify(ConnectionAddressVerification.Provider.KOMGA) }
        internalRead.await()
        job.cancelAndJoin()
        assertTrue(publicStore.isEmpty())
    }

    @Test
    fun `cancellation during pending write waits then removes committed marker`() = runBlocking {
        delayWrite = true
        val job = launch(Dispatchers.IO) { verify(ConnectionAddressVerification.Provider.KOMGA) }
        writeStarted.await()
        job.cancelAndJoin()
        assertTrue(publicStore.isEmpty())
        assertTrue(requests.any { it.startsWith("public DELETE") })
    }

    private suspend fun verify(provider: ConnectionAddressVerification.Provider) =
        verification.verify(provider, public, internal, Headers.headersOf("Authorization", "fixture"))

    private suspend fun failure(
        provider: ConnectionAddressVerification.Provider,
    ): ConnectionAddressVerification.Reason {
        try {
            verify(provider)
            error("Expected verification failure")
        } catch (error: ConnectionAddressVerification.Failure) {
            return error.reason
        }
    }

    private fun server(internal: Boolean): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/") { exchange ->
                val store = if (internal) internalStore else publicStore
                val path = exchange.requestURI.path
                val method = exchange.requestMethod
                requests += "${if (internal) "internal" else "public"} $method $path"
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                var code = 200
                var response: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap())
                if (internal && blockInternal) {
                    internalRead.complete(Unit)
                    // The exchange remains open until the client cancels; the public server can clean up independently.
                    return@createContext
                } else if (internal && redirectInternal) {
                    exchange.responseHeaders.add("Location", public + path.removePrefix("/"))
                    code = 302
                } else if (method == "DELETE" && rejectDelete) {
                    code = 423
                } else if (path.startsWith("/api/v1/client-settings")) {
                    when (method) {
                        "PATCH" -> {
                            writeStarted.complete(Unit)
                            if (delayWrite) Thread.sleep(300)

                            Json.parseToJsonElement(body).jsonObject.forEach { (key, value) ->
                                store[key] =
                                    value.jsonObject
                            }
                            code = 204
                        }
                        "GET" -> response = JsonObject(store.toMap())
                        "DELETE" -> {
                            (Json.parseToJsonElement(body) as JsonArray).forEach {
                                store.remove(it.jsonPrimitive.content)
                            }
                            code = 204
                        }
                    }
                } else if (path == "/api/plugins/metadata" && method == "GET") {
                    if (exchange.requestHeaders.getFirst("Authorization") != "fixture" ||
                        (internal && rejectInternalAuth) || (!internal && rejectPublicAuth)
                    ) {
                        code = 401
                    } else if (!malformedAuthResponse) {
                        response = JsonArray(emptyList())
                    }
                } else {
                    code = 404
                }
                if (code == 204) {
                    exchange.sendResponseHeaders(code, -1)
                } else {
                    val bytes = response.toString().toByteArray()
                    exchange.sendResponseHeaders(code, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                }
                exchange.close()
            }
            start()
        }
}
