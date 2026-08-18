package koharia.komga.api

import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KomgaApiReadStatusTest {

    private val api = KomgaApiClient(
        baseUrl = "https://komga.test",
        headers = Headers.Builder().build(),
        client = OkHttpClient(),
        json = Json,
    )

    @Test
    fun `marking a book read patches completed status`() {
        val request = api.bookReadStatusRequest(
            bookUrl = "https://komga.test/api/v1/books/book-1",
            read = true,
        )

        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/books/book-1/read-progress", request.url.encodedPath)
        assertEquals(
            "{\"completed\":true}",
            Buffer().also { request.body!!.writeTo(it) }.readUtf8(),
        )
    }

    @Test
    fun `marking a book unread deletes its read progress`() {
        val request = api.bookReadStatusRequest(
            bookUrl = "https://komga.test/api/v1/books/book-1",
            read = false,
        )

        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/books/book-1/read-progress", request.url.encodedPath)
        assertEquals(0L, request.body?.contentLength() ?: 0L)
    }
}
