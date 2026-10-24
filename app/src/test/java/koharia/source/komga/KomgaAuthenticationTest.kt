package koharia.source.komga

import okhttp3.Credentials
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KomgaAuthenticationTest {
    @Test
    fun `basic authentication replaces existing copies and preserves reader headers`() {
        val request = Request.Builder().url("https://komga.test/api/v1/books/book/manifest/epub")
            .addHeader("Authorization", "Basic stale")
            .addHeader("authorization", "Basic stale")
            .header("Range", "bytes=0-18")
            .build().withKomgaAuthentication("Credentials", "reader", "fixture", "")
        assertEquals(listOf(Credentials.basic("reader", "fixture")), request.headers.values("Authorization"))
        assertEquals("bytes=0-18", request.header("Range"))
    }

    @Test
    fun `api key authentication replaces existing copies`() {
        val request = Request.Builder().url("https://komga.test/api/v1/books/book/manifest/epub")
            .addHeader("X-Komga-Api-Key", "stale")
            .addHeader("X-Komga-Api-Key", "stale")
            .build().withKomgaAuthentication("ApiKey", "", "", "fixture")
        assertEquals(listOf("fixture"), request.headers.values("X-Komga-Api-Key"))
    }
}
