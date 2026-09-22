package koharia.smanga

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SmangaImageResponseTest {
    @Test
    fun `error bodies and successful login HTML cannot enter the image cache`() {
        listOf(401, 403, 404, 500, 200).forEach { code ->
            val failure = assertThrows(SmangaException::class.java) {
                checkedSmangaImageResponse(response(code, "text/html"))
            }
            assertEquals(code, failure.status)
        }
    }

    @Test
    fun `valid OPDS image responses remain readable`() {
        checkedSmangaImageResponse(response(200, "image/png")).use { assertEquals("fixture", it.body.string()) }
    }

    private fun response(code: Int, type: String) = Response.Builder()
        .request(Request.Builder().url("https://example.test/api/opds/chapter/1/page/1").build())
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body("fixture".toResponseBody(type.toMediaType())).build()
}
