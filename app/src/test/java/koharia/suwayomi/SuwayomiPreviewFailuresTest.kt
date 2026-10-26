package koharia.suwayomi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiPreviewFailuresTest {
    @Test
    fun `a transient chapter failure retries on a later visit without recreating the session`() {
        var now = 1_000L
        val failures = SuwayomiPreviewFailures { now }
        failures.failedChapter(11)
        assertTrue(failures.blocksChapter(3, 11))
        assertFalse(failures.blocksChapter(3, 12))
        now += 29_999
        assertTrue(failures.blocksChapter(3, 11))
        now++
        assertFalse(failures.blocksChapter(3, 11))
        failures.succeeded(11)
        assertFalse(failures.blocksChapter(3, 11))
    }

    @Test
    fun `missing source backs off its series but later source repair can be discovered`() {
        var now = 0L
        val failures = SuwayomiPreviewFailures { now }
        assertTrue(failures.missingSource(3))
        assertFalse(failures.missingSource(3))
        assertTrue(failures.blocksChapter(3, 11))
        assertTrue(failures.blocksChapter(3, 12))
        assertFalse(failures.blocksChapter(4, 13))
        now = 300_000
        assertFalse(failures.blocksSource(3))
        assertFalse(failures.blocksChapter(3, 11))
    }

    @Test
    fun `bounded failures evict oldest without clearing all recent failures or crossing sessions`() {
        val failures = SuwayomiPreviewFailures { 0 }
        repeat(513) { failures.failedChapter(it) }
        assertFalse(failures.blocksChapter(1, 0))
        assertTrue(failures.blocksChapter(1, 1))
        assertTrue(failures.blocksChapter(1, 512))
        assertFalse(SuwayomiPreviewFailures { 0 }.blocksChapter(1, 512))
        failures.succeeded(512)
        assertFalse(failures.blocksChapter(1, 512))
    }
}
