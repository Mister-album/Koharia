package koharia.epub.replacement

import koharia.domain.textreplacement.EffectiveReplacementRule
import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.ReplacementRule
import koharia.domain.textreplacement.ReplacementScope
import koharia.domain.textreplacement.TextReplacementEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplacementDocumentCacheTest {
    private val engine = TextReplacementEngine(
        EffectiveRuleSet(
            listOf(
                EffectiveReplacementRule(
                    ReplacementRule(pattern = "old", replacement = "expanded"),
                    ReplacementScope.GLOBAL,
                ),
            ),
        ),
    )

    private fun document(text: String = "old") = replaceDocument(
        "<html><body><p>$text</p></body></html>",
        engine,
        false,
    )

    @Test
    fun `whole book scan keeps current mapping and only a bounded recent window`() {
        val cache = ReplacementDocumentCache(maxEntries = 4)
        val visible = document()
        cache.activate("visible.xhtml")
        cache.put("visible.xhtml", visible)
        repeat(200) { cache.put("chapter-$it.xhtml", document()) }
        assertEquals(4, cache.size())
        assertSame(visible, cache.get("visible.xhtml"))
        assertFalse(cache.contains("chapter-0.xhtml"))
        assertTrue(cache.contains("chapter-199.xhtml"))
        cache.clear()
        assertEquals(0, cache.size())
    }

    @Test
    fun `weight budget discards large speculative documents but retains the visible resource`() {
        val cache = ReplacementDocumentCache(maxWeight = 1)
        val large = document("old".repeat(1000))
        cache.put("background.xhtml", large)
        assertEquals(0, cache.size())
        cache.activate("visible.xhtml")
        cache.put("visible.xhtml", large)
        repeat(20) { cache.put("other-$it.xhtml", large) }
        assertEquals(1, cache.size())
        assertSame(large, cache.get("visible.xhtml"))
        cache.activate("other.xhtml")
        assertEquals(0, cache.size())
    }

    @Test
    fun `unmatched resources retain only bounded lightweight markers`() {
        val cache = ReplacementDocumentCache(maxEntries = 2)
        cache.put("unchanged.xhtml", document("not matched"))
        assertTrue(cache.contains("unchanged.xhtml"))
        assertNull(cache.get("unchanged.xhtml"))
        cache.activate("unchanged.xhtml")
        repeat(10) { cache.put("other-$it.xhtml", document("not matched")) }
        assertEquals(2, cache.size())
        assertTrue(cache.contains("unchanged.xhtml"))
    }
}
