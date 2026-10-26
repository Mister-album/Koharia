package koharia.kavita

import koharia.epub.serverTimeOffsetMinutes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class KavitaClockSampleTest {
    private val receivedAt = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()
    private fun state(modified: String, clock: KavitaClockSample? = null) = KavitaReadingState(
        KavitaChapterRef(1, 2, 3, 4, 3),
        KavitaProgress(pageNum = 2, lastModifiedUtc = modified),
        10,
        clockSample = clock,
    )

    @Test
    fun `HTTP clock is independent of old or sentinel progress timestamps`() {
        val clock = kavitaClockSample("Mon, 26 Oct 2026 12:00:00 GMT", receivedAt)
        for (modified in listOf("2026-10-26T11:40:00Z", "0001-01-01T00:00:00Z", "")) {
            val cache = state(modified, clock).epubProgressCache(4, 2, "book", "{}")
            assertEquals(kavitaTimestamp(modified), cache.modifiedAt!!.time)
            assertEquals(receivedAt, cache.serverDate!!.time)
            assertEquals(receivedAt, cache.checkedAt.time)
            assertEquals(0.2, cache.progression)
            assertNull(cache.serverTimeOffsetMinutes())
        }
    }

    @Test
    fun `missing malformed and sentinel HTTP dates do not fabricate clock evidence`() {
        for (header in listOf(null, "invalid", "Mon, 1 Jan 0001 00:00:00 GMT", "Thu, 1 Jan 1970 00:00:00 GMT")) {
            val clock = kavitaClockSample(header, receivedAt)
            assertEquals(receivedAt, clock.checkedAt)
            assertNull(clock.serverDate)
            assertNull(
                state("2026-10-26T11:40:00Z", clock).epubProgressCache(4, 2, "book", "{}").serverTimeOffsetMinutes(),
            )
        }
    }

    @Test
    fun `real server clock skew still warns`() {
        for (minutes in listOf(-20L, 20L)) {
            val cache = state("", KavitaClockSample(receivedAt, receivedAt + minutes * 60_000))
                .epubProgressCache(4, 2, "book", "{}")
            assertEquals(20L, cache.serverTimeOffsetMinutes())
        }
    }

    @Test
    fun `persisted operations retain progress but never recycle response clock evidence`() {
        val original = state("2026-10-26T11:40:00Z", KavitaClockSample(receivedAt, receivedAt))
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(original)
        assertFalse(encoded.contains("clockSample"))
        val restored = json.decodeFromString<KavitaReadingState>(encoded)
        assertEquals(original.copy(clockSample = null), restored)
        val cache = restored.epubProgressCache(4, 2, "book", "{}")
        assertEquals(0L, cache.checkedAt.time)
        assertNull(cache.serverDate)
        assertNull(cache.serverTimeOffsetMinutes())
    }
}
