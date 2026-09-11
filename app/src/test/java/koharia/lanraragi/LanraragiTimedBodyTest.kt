package koharia.lanraragi

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LanraragiTimedBodyTest {
    @Test
    fun `complete stream reports bytes exactly once including close`() {
        val results = mutableListOf<Pair<Long, Boolean>>()
        LanraragiTimedBody("image".toResponseBody()) { bytes, complete -> results += bytes to complete }.use {
            assertEquals("image", it.string())
        }
        assertEquals(listOf(5L to true), results)
    }

    @Test
    fun `early close is not reported as a completed image`() {
        val results = mutableListOf<Pair<Long, Boolean>>()
        LanraragiTimedBody("image".toResponseBody()) { bytes, complete -> results += bytes to complete }.close()
        assertEquals(listOf(0L to false), results)
    }
}
