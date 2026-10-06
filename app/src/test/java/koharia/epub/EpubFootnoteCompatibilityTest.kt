package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertTrue(script.contains("setAttributeNS(epubNamespace, 'epub:type', updated)"))
        assertTrue(script.contains("data-koharia-footnotes-prepared"))
    }

    @Test
    fun `script preserves existing epub type tokens and creates an xhtml style element`() {
        assertTrue(script.contains("const values = String(current).trim().split(/\\s+/).filter(Boolean)"))
        assertTrue(script.contains("values.push(type)"))
        assertTrue(script.contains("values.join(' ')"))
        assertTrue(script.contains("createElementNS(root.namespaceURI || xhtmlNamespace, 'style')"))
    }

    @Test
    fun `script hides the referenced note body from the flowing text`() {
        assertTrue(script.contains("const noteContainerAttribute = '$EPUB_NOTE_CONTAINER_ATTRIBUTE'"))
        assertTrue(script.contains("const noteContainerStyleId = '$EPUB_NOTE_CONTAINER_STYLE_ID'"))
        assertTrue(script.contains("const noteContainerStyleText = `$EPUB_NOTE_CONTAINER_CSS`"))
        assertTrue(script.contains("style.textContent = noteContainerStyleText"))
        assertTrue(script.contains("markNoteTarget(target)"))
        assertTrue(EPUB_NOTE_CONTAINER_CSS.contains("display: none !important"))
    }

    @Test
    fun `script never hides the document root or the running text`() {
        assertTrue(
            script.contains(
                "if (target.localName === 'html' || target.localName === 'body') return;",
            ),
        )
        assertTrue(script.contains("if (visibleText(wrapper) !== '') break;"))
        assertTrue(script.contains("if (!isNoteWrapper(wrapper) || hasVisibleSiblings(target)) break;"))
        assertTrue(script.contains("function isNoteWrapper(element)"))
    }

    @Test
    fun `script re-resolves wrappers after the publisher removed the fragment href`() {
        assertTrue(script.contains("const remeasureNoteContainers = function()"))
        assertTrue(
            Regex("restoreNoteHref\\(\\);\\s+detect\\(\\);").containsMatchIn(script),
            "the second pass must rebuild the fragments before detecting",
        )
        assertTrue(script.contains("window.addEventListener('load', remeasureNoteContainers, { once: true })"))
        assertTrue(script.contains("if (document.readyState === 'complete')"))
    }

    @Test
    fun `script restores the note fragment the publisher removed`() {
        assertTrue(script.contains("const restoreNoteHref = function()"))
        assertTrue(script.contains("Array.from(document.querySelectorAll('a'))"))
        assertTrue(script.contains("!anchor.getAttribute('href') && isReference(anchor)"))
        assertTrue(script.contains("anchor.setAttribute('href', '#' + id)"))
        assertTrue(script.contains("anchor.setAttribute(fragmentAttribute, '#' + id)"))
        assertTrue(script.contains("const fragmentAttribute = '$EPUB_NOTEREF_FRAGMENT_ATTRIBUTE'"))
        assertTrue(script.contains("const paired = recorded.length !== hints.length"))
        assertTrue(script.contains("restoreNoteHref();"))
    }

    @Test
    fun `script keeps note container handling idempotent`() {
        assertTrue(script.contains("if (containerHandled.indexOf(target) === -1)"))
        assertTrue(script.contains("if (containerHandled.indexOf(wrapper) === -1)"))
        assertTrue(script.contains("target.setAttribute(noteContainerAttribute, 'true')"))
        assertTrue(script.contains("wrapper.setAttribute(noteContainerAttribute, 'true')"))
        assertTrue(script.contains("if (containerHandled.length === 0) return;"))
    }

    @Test
    fun `reader styles place text and common graphics as three quarter inline superscripts`() {
        assertTrue(script.contains("font-size: 0.75rem !important"))
        assertTrue(script.contains("vertical-align: super !important"))
        assertTrue(
            script.contains(
                "inset: -0.125rem !important",
            ),
        )
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

    @Test
    fun `publisher styles still hide the referenced note body`() {
        val publisherScript = buildEpubFootnoteCompatibilityScript(
            applyReaderStyles = false,
            readerFontScale = 2f,
        )

        assertFalse(publisherScript.contains("a[data-koharia-footnote-reference=\"true\"]"))
        assertTrue(publisherScript.contains("markNoteTarget(target)"))
        assertTrue(publisherScript.contains("applyNoteContainers()"))
        assertEquals(EPUB_NOTE_CONTAINER_CSS, noteContainerCssIn(publisherScript))
    }

    private fun noteContainerCssIn(value: String): String {
        val marker = "const noteContainerStyleText = `"
        val start = value.indexOf(marker) + marker.length
        val end = value.indexOf('`', start)
        return value.substring(start, end)
    }
}
