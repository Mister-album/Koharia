package koharia.pdf

import koharia.pdf.reflow.PdfBox
import koharia.pdf.reflow.PdfGlyph
import koharia.pdf.reflow.PdfGraphic
import koharia.pdf.reflow.PdfPageFacts
import koharia.pdf.reflow.PdfReflowBuilder
import koharia.pdf.reflow.PdfTextStyle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfReflowPolicyTest {
    private fun textPage(): PdfPageFacts {
        val glyphs = (0 until 80).map { i ->
            val box = PdfBox(20f + i % 20 * 10, 60f + i / 20 * 20, 28f + i % 20 * 10, 72f + i / 20 * 20)
            PdfGlyph(i, "A", box, box, 0, 0f)
        }
        return PdfPageFacts(
            0,
            300,
            450,
            0,
            PdfBox(0f, 0f, 300f, 450f),
            listOf(PdfTextStyle(null, 12f, 400, false, null)),
            glyphs,
            emptyList(),
        )
    }

    @Test
    fun `ordinary text remains reflowable`() {
        assertFalse(PdfReflowBuilder.needsOriginalPage(textPage()))
    }

    @Test
    fun `low punctuation on ordinary lines does not become false columns`() {
        val page = textPage()
        val glyphs = page.glyphs.map { glyph ->
            if (glyph.index % 20 in listOf(3, 17)) {
                glyph.copy(text = "，", box = glyph.box.copy(top = glyph.box.bottom - 2))
            } else {
                glyph
            }
        }
        assertFalse(PdfReflowBuilder.needsOriginalPage(page.copy(glyphs = glyphs)))
    }

    @Test
    fun `ruby above a base glyph remains reflowable`() {
        val page = textPage()
        val box = PdfBox(20f, 55f, 25f, 59f)
        val annotation = PdfGlyph(80, "ふ", box, box, 1, 0f)
        assertFalse(
            PdfReflowBuilder.needsOriginalPage(
                page.copy(
                    styles = page.styles + PdfTextStyle(null, 6f, 400, false, null),
                    glyphs = page.glyphs + annotation,
                ),
            ),
        )
    }

    @Test
    fun `rotation empty text and overlapping artwork preserve the original page`() {
        val page = textPage()
        assertTrue(PdfReflowBuilder.needsOriginalPage(page.copy(rotation = 1)))
        assertTrue(PdfReflowBuilder.needsOriginalPage(page.copy(glyphs = emptyList())))
        assertTrue(
            PdfReflowBuilder.needsOriginalPage(page.copy(graphics = listOf(PdfGraphic(3, PdfBox(0f, 0f, 300f, 450f))))),
        )
    }
}
