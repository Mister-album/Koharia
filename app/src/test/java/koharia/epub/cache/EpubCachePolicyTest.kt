package koharia.epub.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubCachePolicyTest {

    @Test
    fun `manual download wins over complete cache and remote`() {
        assertEquals(
            EpubCachePolicy.OpenSource.MANUAL_DOWNLOAD,
            EpubCachePolicy.selectOpenSource("manual.epub", "cached.epub", "https://server/book"),
        )
    }

    @Test
    fun `complete cache wins over remote`() {
        assertEquals(
            EpubCachePolicy.OpenSource.COMPLETE_CACHE,
            EpubCachePolicy.selectOpenSource(null, "cached.epub", "https://server/book"),
        )
    }

    @Test
    fun `publication key prefers hash then version and size`() {
        assertEquals(
            "komga:hash",
            EpubCachePolicy.publicationKey("hash", "2026-07-16", 10L, "fallback"),
        )
        assertEquals(
            "komga:2026-07-16:10",
            EpubCachePolicy.publicationKey(null, "2026-07-16", 10L, "fallback"),
        )
        assertEquals(
            "fallback",
            EpubCachePolicy.publicationKey(null, null, 10L, "fallback"),
        )
    }

    @Test
    fun `resume only appends a matching partial response`() {
        assertTrue(EpubCachePolicy.shouldAppendPartial(128L, 206, "bytes 128-255/256"))
        assertFalse(EpubCachePolicy.shouldAppendPartial(128L, 200, null))
        assertFalse(EpubCachePolicy.shouldAppendPartial(128L, 206, "bytes 0-255/256"))
    }
}
