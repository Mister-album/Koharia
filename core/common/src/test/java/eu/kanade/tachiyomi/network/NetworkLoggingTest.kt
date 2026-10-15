package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.CookieManager
import androidx.core.content.ContextCompat
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class NetworkLoggingTest {
    @Test
    fun verboseDiagnosticsRedactCredentialsWithoutChangingWireValues() = exercise(verbose = true)

    @Test
    fun disabledVerboseLoggingProducesNoHttpDiagnostics() = exercise(verbose = false)

    private fun exercise(verbose: Boolean) {
        val parent = File("build/tmp").canonicalFile.apply { mkdirs() }
        val root = File(parent, "network-logging-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == parent && !root.exists() && root.mkdirs())
        val canaries = mapOf(
            "authorization" to "Bearer dummy-authorization-canary",
            "cookie" to "session=dummy-cookie-canary",
            "x-api-key" to "dummy-api-key-canary",
            "x-komga-api-key" to "dummy-komga-key-canary",
        )
        val body = "dummy-request-body-canary"
        val reply = "dummy-response-body-canary"
        val responseCookie = "session=dummy-response-cookie-canary"
        val received = CopyOnWriteArrayList<Map<String, String>>()
        val requestBodies = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        val logger = Logger.getLogger("okhttp3.OkHttpClient")
        val previousLevel = logger.level
        val previousParents = logger.useParentHandlers
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                messages += record.message
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/fixture") { exchange ->
                try {
                    received += canaries.keys.associateWith { exchange.requestHeaders.getFirst(it).orEmpty() }
                    requestBodies += exchange.requestBody.use { it.readBytes().decodeToString() }
                    exchange.responseHeaders.add("Set-Cookie", responseCookie)
                    exchange.responseHeaders.add("Cache-Control", "no-store")
                    val bytes = reply.encodeToByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        var network: NetworkHelper? = null
        mockkStatic(CookieManager::class, ContextCompat::class)
        try {
            val cookieManager = mockk<CookieManager>(relaxed = true)
            every { CookieManager.getInstance() } returns cookieManager
            every { cookieManager.getCookie(any()) } returns ""
            every { ContextCompat.getMainExecutor(any()) } returns Executor { it.run() }
            val context = mockk<Context>()
            every { context.getExternalFilesDir(any()) } returns File(root, "external")
            every { context.filesDir } returns File(root, "files")
            every { context.cacheDir } returns File(root, "cache")
            logger.level = Level.ALL
            logger.useParentHandlers = false
            logger.addHandler(handler)
            val helper = NetworkHelper(context, NetworkPreferences(InMemoryPreferenceStore(), verbose))
                .also { network = it }
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/fixture")
                .post(body.toRequestBody()).apply { canaries.forEach { (name, value) -> header(name, value) } }.build()
            helper.nonCloudflareClient.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals(reply, response.body.string())
            }
            assertEquals(listOf(canaries), received)
            assertEquals(listOf(body), requestBodies)
            if (verbose) {
                assertTrue(messages.any { it.contains("POST") && it.contains("/fixture") })
                assertTrue(messages.any { it.contains("200") })
                for (secret in canaries.values + listOf(body, reply, responseCookie)) {
                    assertFalse(messages.any { it.contains(secret) }, "Diagnostic output must not contain the canary")
                }
            } else {
                assertTrue(messages.isEmpty())
            }
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousParents
            network?.nonCloudflareClient?.let {
                it.cache?.close()
                it.connectionPool.evictAll()
                it.dispatcher.executorService.shutdown()
            }
            server.stop(0)
            unmockkStatic(CookieManager::class, ContextCompat::class)
            check(root.canonicalFile.parentFile == parent && root.deleteRecursively())
        }
    }
}
