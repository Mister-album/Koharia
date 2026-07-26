package koharia.epub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubDocumentPreparationTest {

    @Test
    fun `document preparation combines paragraph and image policies`() {
        val script = buildEpubDocumentPreparationScript(
            paragraphIndentOverrideEnabled = true,
            preserveImageColors = true,
            parentColorsInverted = true,
        )

        assertTrue(script.contains(EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE))
        assertTrue(script.contains("filter: invert(100%) !important"))
        assertTrue(script.contains("return 'prepared'"))
    }

    @Test
    fun `disabled paragraph override removes prior document attributes`() {
        val script = buildEpubDocumentPreparationScript(
            paragraphIndentOverrideEnabled = false,
            preserveImageColors = false,
            parentColorsInverted = false,
        )

        assertTrue(script.contains("paragraph.removeAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE')"))
        assertFalse(script.contains("paragraph.setAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE'"))
    }

    @Test
    fun `paragraph policies ignore standalone svg documents`() {
        assertTrue(
            APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT.contains(
                "document.documentElement.localName.toLowerCase() !== 'html'",
            ),
        )
        assertTrue(
            REMOVE_EPUB_PARAGRAPH_INDENT_SCRIPT.contains(
                "document.documentElement.localName.toLowerCase() !== 'html'",
            ),
        )
    }
}
