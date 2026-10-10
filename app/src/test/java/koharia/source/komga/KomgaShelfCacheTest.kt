package koharia.source.komga

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class KomgaShelfCacheTest {
    @TempDir lateinit var directory: File

    @Test
    fun `warm shelf does not hit network and manual failure retains data`() {
        val context = context()
        var epoch = 0L
        var requests = 0
        var fail = false
        val store = KomgaMetadataCacheStore(context) { "account-a" }
        val client = OkHttpClient.Builder()
            .addInterceptor(KomgaOfflineInterceptor(context, { "account-a" }, { epoch }) { false })
            .addInterceptor { chain ->
                requests++
                if (fail) throw IOException("offline fixture")
                val body = "{\"content\":[]}".toResponseBody("application/json".toMediaType())
                val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(body).build()
                store.save(chain.request(), response)
            }.build()
        val request = Request.Builder().url("https://komga.test/api/v1/series?page=0")
            .tag(KomgaCachePolicy::class.java, KomgaCachePolicy.Default).build()
        client.newCall(request).execute().use { assertEquals("{\"content\":[]}", it.body.string()) }
        fail = true
        repeat(3) {
            client.newCall(request).execute().use { assertEquals("{\"content\":[]}", it.body.string()) }
        }
        assertEquals(1, requests)
        epoch = Long.MAX_VALUE
        client.newCall(request).execute().use { assertEquals("{\"content\":[]}", it.body.string()) }
        assertEquals(2, requests)
        assertEquals("{\"content\":[]}", store.load(request)?.use { it.body.string() })
        assertNull(KomgaMetadataCacheStore(context) { "account-b" }.load(request))
    }

    @Test
    fun `large shelf response is persisted without the small metadata buffer limit`() {
        val store = KomgaMetadataCacheStore(context()) { "large-shelf" }
        val request = Request.Builder().url("https://komga.test/api/v1/series?page=0")
            .tag(KomgaCachePolicy::class.java, KomgaCachePolicy.Default).build()
        val content = "x".repeat((KomgaMetadataCacheStore.MAX_CACHE_BYTES + 1024).toInt())
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(content.toResponseBody("application/json".toMediaType())).build()
        store.save(request, response).use { assertEquals(content, it.body.string()) }
        assertEquals(
            content,
            KomgaMetadataCacheStore(context()) {
                "large-shelf"
            }.load(request)?.use { it.body.string() },
        )
    }

    @Test
    fun `cached filters are read before network execution`() = kotlinx.coroutines.runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw AssertionError("Network must not run") }.build()
        val api = koharia.komga.api.KomgaApiClient(
            "https://komga.test",
            okhttp3.Headers.Builder().build(),
            client,
            kotlinx.serialization.json.Json,
            shelfCache = { request ->
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("[]".toResponseBody("application/json".toMediaType())).build()
            },
        )
        assertEquals(emptyList<koharia.komga.api.dto.LibraryDto>(), api.getLibraries())
    }

    @Test
    fun `progress requests are never stored as shelf metadata`() {
        val store = KomgaMetadataCacheStore(context()) { "progress" }
        val request = Request.Builder()
            .url("https://komga.test/api/v1/books/book/read-progress")
            .komgaProgressSync()
            .build()
        assertEquals(false, store.isEligible(request))
    }

    @Test
    fun `cached-only mode permits only tagged progress traffic while online`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(KomgaOfflineInterceptor(context(), { "progress-only" }) { true })
                .build()
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val progress = Request.Builder()
                .url("$baseUrl/api/v1/books/book/read-progress")
                .patch(ByteArray(0).toRequestBody())
                .komgaProgressSync()
                .build()
            client.newCall(progress).execute().use { assertEquals(204, it.code) }
            assertEquals(1, requests)

            val shelf = Request.Builder().url("$baseUrl/api/v1/series").build()
            assertEquals(true, runCatching { client.newCall(shelf).execute().close() }.isFailure)
            assertEquals(1, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `partially consumed refresh keeps the previous complete shelf`() {
        val store = KomgaMetadataCacheStore(context()) { "partial" }
        val request = Request.Builder().url("https://komga.test/api/v1/series?page=0")
            .tag(KomgaCachePolicy::class.java, KomgaCachePolicy.Default).build()
        fun response(body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        store.save(request, response("previous")).use { it.body.string() }
        store.save(request, response("incomplete new response")).use { it.body.source().read(okio.Buffer(), 2) }
        assertEquals("previous", store.load(request)?.use { it.body.string() })
    }

    @Test
    fun `gzip shelf is cached as decoded JSON`() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val text = "{\"content\":[]}"
        val compressed = java.io.ByteArrayOutputStream().also { bytes ->
            java.util.zip.GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
        }.toByteArray()
        var requests = 0
        server.createContext("/") { exchange ->
            requests++
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
        }
        server.start()
        try {
            val context = context()
            val client = OkHttpClient.Builder()
                .addInterceptor(KomgaOfflineInterceptor(context, { "gzip" }) { false })
                .addInterceptor(KomgaCacheControlInterceptor(context) { "gzip" }).build()
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/api/v1/series")
                .tag(KomgaCachePolicy::class.java, KomgaCachePolicy.Default).build()
            repeat(2) { client.newCall(request).execute().use { assertEquals(text, it.body.string()) } }
            assertEquals(1, requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `organization cache misses offline cannot fall back to another account http cache`() {
        val client =
            OkHttpClient.Builder()
                .addInterceptor(KomgaOfflineInterceptor(context(), { "account-b" }) { true })
                .addInterceptor { throw AssertionError("HTTP cache fallback must not run") }
                .build()
        val request =
            Request.Builder()
                .url("https://komga.test/api/v1/readlists")
                .tag(KomgaCacheNamespace::class.java, KomgaCacheNamespace("account-b"))
                .tag(KomgaCachePolicy::class.java, KomgaCachePolicy.Default)
                .build()
        assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
    }

    @Test
    fun `strict permissions cannot be satisfied by a previously successful metadata response`() {
        val context = context()
        val store = KomgaMetadataCacheStore(context) { "account-a" }
        val request = Request.Builder().url("https://komga.test/api/v1/collections/id").build()
        val response =
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        store.save(request, response).close()
        var networkCalls = 0
        val client =
            OkHttpClient.Builder()
                .addInterceptor(KomgaOfflineInterceptor(context, { "account-a" }) { false })
                .addInterceptor {
                    networkCalls++
                    throw IOException("server unavailable")
                }
                .build()
        assertThrows(IOException::class.java) {
            client.newCall(request.newBuilder().komgaRequireNetwork().build()).execute().close()
        }
        assertEquals(1, networkCalls)
    }

    private fun context(): Context {
        val connectivity = mockk<ConnectivityManager>()
        every { connectivity.activeNetwork } returns mockk()
        every { connectivity.getNetworkCapabilities(any()) } returns mockk<NetworkCapabilities> {
            every { hasTransport(any()) } returns true
        }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
        every { context.getExternalFilesDir(any()) } returns File(directory, "external")
        every { context.cacheDir } returns File(directory, "legacy")
        every { context.filesDir } returns File(directory, "files")
        return context
    }
}
