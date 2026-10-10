package koharia.source.komga

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KomgaMetadataCacheStoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `search list posts are cached by url and request body`() {
        val store = KomgaMetadataCacheStore(context())
        val first = searchRequest("{\"fullTextSearch\":\"first\"}")
        val second = searchRequest("{\"fullTextSearch\":\"second\"}")

        store.save(first, response(first, "first result")).close()

        assertEquals("first result", store.load(first)?.body?.string())
        assertNull(store.load(second))
    }

    @Test
    fun `only supported json search posts are eligible`() {
        val store = KomgaMetadataCacheStore(context())
        val supported = searchRequest("{}")
        val unrelated = supported.newBuilder().url("https://komga.test/api/v1/books/an-id").build()
        val nonJson = supported.newBuilder().post("query".toRequestBody("text/plain".toMediaType())).build()

        assertTrue(store.isEligible(supported))
        assertFalse(store.isEligible(unrelated))
        assertFalse(store.isEligible(nonJson))
    }

    @Test
    fun `read list library membership comes from its cached books`() {
        val store = KomgaMetadataCacheStore(context())
        val readListUrl = "https://komga.test/api/v1/readlists/read-list-id"
        val booksRequest = Request.Builder()
            .url("$readListUrl/books?unpaged=true&media_status=READY&deleted=false")
            .build()
        val body = """
            {
              "content": [
                { "id": "book-1", "libraryId": "library-a" },
                { "id": "book-2", "libraryId": "library-b" },
                { "id": "book-3", "libraryId": "library-a" }
              ]
            }
        """.trimIndent()

        store.save(booksRequest, response(booksRequest, body)).close()

        assertEquals(setOf("library-a", "library-b"), store.findLibraryIds(readListUrl))
    }

    @Test
    fun `malformed read list books cache has no library membership`() {
        val store = KomgaMetadataCacheStore(context())
        val readListUrl = "https://komga.test/api/v1/readlists/read-list-id"
        val booksRequest = Request.Builder()
            .url("$readListUrl/books?unpaged=true&media_status=READY&deleted=false")
            .build()

        store.save(booksRequest, response(booksRequest, """{ "content": {} }""")).close()

        assertEquals(emptySet<String>(), store.findLibraryIds(readListUrl))
    }

    @Test
    fun `oversized known and unknown bodies bypass cache without consuming response`() {
        val store = KomgaMetadataCacheStore(context())
        for (unknown in listOf(false, true)) {
            val request = searchRequest("{\"unknown\":$unknown}")
            val text = "x".repeat((KomgaMetadataCacheStore.MAX_CACHE_BYTES + 123).toInt())
            val body = if (unknown) {
                object : okhttp3.ResponseBody() {
                    private val input = okio.Buffer().writeUtf8(text)
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = input
                }
            } else {
                text.toResponseBody("application/json".toMediaType())
            }
            val response = response(request, "").newBuilder().body(body).build()
            store.save(request, response).use { assertEquals(text, it.body.string()) }
            assertNull(store.load(request))
        }
    }

    @Test
    fun `binary response and file query are not cached`() {
        val store = KomgaMetadataCacheStore(context())
        assertFalse(KomgaMetadataCacheStore.isEligibleUrl("https://komga.test/api/v1/books/id/file?download=true"))
        val request = Request.Builder().url("https://komga.test/api/v1/books/id/thumbnail").build()
        val response = response(
            request,
            "",
        ).newBuilder().body("image".toResponseBody("image/png".toMediaType())).build()
        store.save(request, response).use { assertEquals("image", it.body.string()) }
        assertNull(store.load(request))
    }

    @Test
    fun `successful empty organization cache is retained after refresh failure`() {
        val store = KomgaMetadataCacheStore(context())
        val request = Request.Builder().url("https://komga.test/api/v1/readlists?page=0").build()
        val empty = """{"content":[],"totalElements":0}"""
        store.save(request, response(request, empty)).close()
        store.save(request, response(request, "failed").newBuilder().code(503).build()).close()
        assertEquals(empty, store.load(request)?.body?.string())
        assertNull(store.load(request, Long.MAX_VALUE))
        assertEquals(empty, store.load(request)?.body?.string())
    }

    @Test
    fun `late response is stored only in captured account and strict requests bypass cache`() {
        var selectedAccount = "account-a"
        val store = KomgaMetadataCacheStore(context()) { selectedAccount }
        val request = Request.Builder().url("https://komga.test/api/v1/collections")
            .tag(KomgaCacheNamespace::class.java, KomgaCacheNamespace("account-a")).build()
        selectedAccount = "account-b"
        store.save(request, response(request, "account a")).close()
        assertEquals("account a", store.load(request)?.body?.string())
        assertNull(store.load(request.newBuilder().tag(KomgaCacheNamespace::class.java, null).build()))
        val strict = request.newBuilder().komgaRequireNetwork().build()
        assertFalse(store.isEligible(strict))
        assertNull(store.load(strict))
    }

    @Test
    fun `organization reference choices remain cacheable behind a base path`() {
        listOf("authors", "age-ratings", "languages").forEach { path ->
            val version = if (path == "authors") 2 else 1
            assertTrue(KomgaMetadataCacheStore.isEligibleUrl("https://komga.test/komga/api/v$version/$path"))
        }
    }

    private fun context(): Context = mockk {
        every { getExternalFilesDir(any()) } returns File(tempDir, "external")
        every { cacheDir } returns File(tempDir, "legacy")
        every { filesDir } returns File(tempDir, "files")
    }

    private fun searchRequest(body: String): Request = Request.Builder()
        .url("https://komga.test/api/v1/books/list?page=0")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    private fun response(request: Request, body: String): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}
