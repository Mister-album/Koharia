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
