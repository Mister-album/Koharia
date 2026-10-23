package koharia.epub.replacement

import koharia.domain.textreplacement.EffectiveReplacementRule
import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.ReplacementRule
import koharia.domain.textreplacement.ReplacementScope
import koharia.domain.textreplacement.TextReplacementEngine
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplacementDocumentTest {
    @Test
    fun `chapter preview preserves actual inline and ruby boundaries`() {
        val html = "<html><body><p>甲<b>乙</b></p><p><ruby>甲乙</ruby></p></body></html>"
        val rules = EffectiveRuleSet(
            listOf(
                EffectiveReplacementRule(ReplacementRule(pattern = "甲乙", replacement = "new"), ReplacementScope.BOOK),
            ),
        )
        val preview = ReplacementPreviewDocument(html, false).preview(rules)
        val display = replaceDocument(html, TextReplacementEngine(rules), false).mapping
        assertEquals(display, preview)
        assertEquals(1, preview.edits.size)
        assertTrue(preview.text.contains("甲乙"))
    }

    private fun engine(
        pattern: String,
        replacement: String,
    ) = TextReplacementEngine(
        EffectiveRuleSet(
            listOf(
                EffectiveReplacementRule(
                    ReplacementRule(pattern = pattern, replacement = replacement),
                    ReplacementScope.GLOBAL,
                ),
            ),
        ),
    )

    @Test
    fun `matches across inline tags preserving markup and anchor attributes`() {
        val result =
            replaceDocument(
                "<html><body><p id='anchor'>前<b>甲</b><i>乙</i>后</p></body></html>",
                engine("甲乙", "<新>&"),
                false,
            )
        val doc = Jsoup.parse(result.html)
        assertEquals("前<新>&后", doc.selectFirst("p")!!.text())
        assertEquals("<新>&", doc.selectFirst("b")!!.text())
        assertEquals("anchor", doc.selectFirst("p")!!.id())
        assertEquals(1, doc.select("i").size)
        assertFalse(result.html.contains("<新>"))
    }

    @Test
    fun `cannot match across blocks line breaks images ruby or note references`() {
        listOf(
            "</p><p>",
            "<br/>",
            "<img src='keep.png'/>",
            "<ruby>注</ruby>",
            "<a epub:type='noteref' href='#note'>1</a>",
        ).forEach { boundary ->
            val html = "<html><body><p>甲${boundary}乙</p></body></html>"
            assertTrue(replaceDocument(html, engine("甲乙", "wrong"), false).mapping.edits.isEmpty())
        }
    }

    @Test
    fun `footnote body is transformed but script style and metadata are not`() {
        val html = "<html><head><title>甲</title><style>.甲{}</style></head>" +
            "<body><p>甲</p><aside id='note'>甲</aside><script>甲</script></body></html>"
        val doc = Jsoup.parse(replaceDocument(html, engine("甲", "乙"), false).html)
        assertEquals("乙", doc.selectFirst("aside")!!.text())
        assertEquals("甲", doc.title())
        assertEquals("甲", doc.selectFirst("script")!!.data())
    }

    @Test
    fun `empty rules are byte identical and XML keeps namespaces`() {
        val html = "<?xml version='1.0'?><html xmlns='http://www.w3.org/1999/xhtml'><body><p>甲</p></body></html>"
        assertEquals(html, replaceDocument(html, engine("missing", ""), true).html)
        val result = replaceDocument(html, engine("甲", "乙"), true)
        assertTrue(result.html.contains("http://www.w3.org/1999/xhtml"))
        assertTrue(result.html.contains("乙"))
    }
}
