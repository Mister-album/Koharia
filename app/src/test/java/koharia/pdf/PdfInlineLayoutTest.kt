package koharia.pdf

import koharia.pdf.reflow.PdfBox
import koharia.pdf.reflow.PdfGlyph
import koharia.pdf.reflow.PdfInlineLayout
import koharia.pdf.reflow.PdfPageFacts
import koharia.pdf.reflow.PdfTextStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfInlineLayoutTest {
    private fun glyph(index: Int, text: String, x: Float, y: Float, small: Boolean = false): PdfGlyph {
        val box = PdfBox(x, y, x + if (small) 4f else 8f, y + if (small) 5f else 10f)
        return PdfGlyph(index, text, box, box, if (small) 1 else 0, 0f)
    }

    private fun page(extra: List<PdfGlyph>): PdfPageFacts = PdfPageFacts(
        0,
        300,
        450,
        0,
        PdfBox(0f, 0f, 300f, 450f),
        listOf(PdfTextStyle(null, 12f, 400, false, null), PdfTextStyle(null, 6f, 400, false, null)),
        (0..39).map { glyph(it, "文", 20f + it % 20 * 10, 100f + it / 20 * 20) } + extra,
        emptyList(),
    )

    @Test
    fun `ruby binds a complete base group without losing either text layer`() {
        val input = page(listOf(glyph(40, "ふ", 20f, 94f, true), glyph(41, "じ", 30f, 94f, true)))
        val layout = PdfInlineLayout.analyze(input)
        assertEquals("ふじ", layout.ruby[0]?.text)
        assertEquals("文文", layout.glyphs.first().text)
        assertFalse(layout.glyphs.any { it.index == 40 || it.index == 41 || it.index == 1 })
        assertEquals(40, layout.ruby[0]?.source?.firstCharacter)
        assertTrue(layout.originalRegions.isEmpty())
    }

    @Test
    fun `raised and lowered numbers remain scripts`() {
        val input = page(listOf(glyph(40, "1", 219f, 94f, true), glyph(41, "2", 219f, 128f, true)))
        val layout = PdfInlineLayout.analyze(input)
        assertEquals("sup", layout.scripts[40])
        assertEquals("sub", layout.scripts[41])
        assertEquals(94f, layout.sources[40]?.box?.top)
        assertTrue(layout.ruby.isEmpty())
    }

    @Test
    fun `matching footnote becomes a link while an unmatched footer remains text`() {
        val footer = "1.说明文字".mapIndexed { n, ch -> glyph(41 + n, ch.toString(), 20f + n * 5, 370f, true) }
        val linked = PdfInlineLayout.analyze(page(listOf(glyph(40, "1", 219f, 94f, true)) + footer))
        assertEquals(1, linked.notes.size)
        assertEquals("说明文字", linked.notes.single().text)
        assertEquals(linked.notes.single().id, linked.noteReferences[40])
        assertFalse(linked.glyphs.any { it.index == 41 })
        val unmatched = PdfInlineLayout.analyze(page(footer))
        assertTrue(unmatched.notes.isEmpty())
        assertTrue(unmatched.glyphs.any { it.index == 41 })
    }
}
