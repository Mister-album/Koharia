package koharia.epub

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubFootnoteCompatibilityTest {

    private val script = buildEpubFootnoteCompatibilityScript(
        applyReaderStyles = true,
        readerFontScale = 1f,
    )

    @Test
    fun `script preserves standard noterefs and recognizes accessibility roles`() {
        assertTrue(script.contains("epubTypes(anchor).includes('noteref')"))
        assertTrue(script.contains("role === 'doc-noteref'"))
        assertTrue(script.contains("role === 'doc-footnote'"))
        assertTrue(script.contains("role === 'doc-endnote'"))
    }

    @Test
    fun `script recognizes duokan and common footnote classes`() {
        assertTrue(script.contains("'duokan-footnote'"))
        assertTrue(script.contains("'duokan-footnote-item'"))
        assertTrue(script.contains("'footnote-ref'"))
        assertTrue(script.contains("'endnote-ref'"))
    }

    @Test
    fun `script only inspects same document fragment targets`() {
        assertTrue(script.contains("url.origin !== documentUrl.origin"))
        assertTrue(script.contains("url.pathname !== documentUrl.pathname"))
        assertTrue(script.contains("url.search !== documentUrl.search"))
        assertTrue(script.contains("document.getElementById(id)"))
    }

    @Test
    fun `script marks xhtml links using the epub namespace`() {
        assertTrue(script.contains("root.localName.toLowerCase() !== 'html'"))
        assertTrue(script.contains("setAttributeNS(epubNamespace, 'epub:type', 'noteref')"))
        assertTrue(script.contains("data-koharia-footnotes-prepared"))
    }

    @Test
    fun `reader styles place text and common graphics as three quarter inline superscripts`() {
        assertTrue(script.contains("font-size: 0.75rem !important"))
        assertTrue(script.contains("vertical-align: super !important"))
        assertTrue(script.contains("inset: -0.125rem !important"))
        assertTrue(script.contains("transform: none !important"))
        assertTrue(script.contains("img, svg, picture, object, input[type=\"image\"], [role=\"img\"]"))
        assertTrue(script.contains("width: 1em !important"))
        assertTrue(script.contains("height: 1em !important"))
        assertTrue(script.contains("] > *"))
        assertTrue(script.contains("padding: 0 !important"))
        assertTrue(script.contains("margin: 0 !important"))
    }

    @Test
    fun `publisher styles remove reader footnote overrides`() {
        val publisherScript = buildEpubFootnoteCompatibilityScript(
            applyReaderStyles = false,
            readerFontScale = 2f,
        )

        assertTrue(publisherScript.contains("const applyReaderStyles = false"))
        assertTrue(publisherScript.contains("if (previousStyle) previousStyle.remove()"))
        assertTrue(publisherScript.contains("anchor.removeAttribute(referenceAttribute)"))
    }
}
