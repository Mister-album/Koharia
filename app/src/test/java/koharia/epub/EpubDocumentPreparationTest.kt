package koharia.epub

import koharia.epub.settings.EpubLayoutPreferences
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubDocumentPreparationTest {

    @Test
    fun `document preparation combines paragraph and image policies`() {
        val script = buildEpubDocumentPreparationScript(
            paragraphIndentOverrideEnabled = true,
            textAlignment = EpubLayoutPreferences.TextAlignment.JUSTIFY,
            tocHrefs = listOf("OEBPS/chapter.xhtml#chapter-2"),
            chapterBreaksEnabled = true,
            preserveImageColors = true,
            parentColorsInverted = true,
            readerFontScale = 1.5f,
        )

        assertTrue(script.contains(EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE))
        assertTrue(script.contains("text-align: justify !important"))
        assertTrue(script.contains("hyphens: auto !important"))
        assertTrue(script.contains("overflow-wrap: anywhere !important"))
        assertTrue(script.contains("word-wrap: break-word !important"))
        assertTrue(script.contains("break-before: column !important"))
        assertTrue(script.contains("'duokan-footnote'"))
        assertTrue(script.contains("setAttributeNS(epubNamespace, 'epub:type', updated)"))
        assertTrue(script.contains("font-size: 1.125rem !important"))
        assertTrue(script.contains("filter: invert(100%) !important"))
        assertTrue(script.contains("return 'prepared'"))
    }

    @Test
    fun `disabled paragraph override removes prior document attributes`() {
        val script = buildEpubDocumentPreparationScript(
            paragraphIndentOverrideEnabled = false,
            textAlignment = null,
            tocHrefs = emptyList(),
            chapterBreaksEnabled = false,
            preserveImageColors = false,
            parentColorsInverted = false,
            readerFontScale = 1f,
        )

        assertTrue(script.contains("paragraph.removeAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE')"))
        assertFalse(script.contains("paragraph.setAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE'"))
        assertTrue(script.contains("element.removeAttribute('$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE')"))
        assertFalse(script.contains("element.setAttribute('$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE'"))
    }

    @Test
    fun `paginated chapter breaks target toc fragments`() {
        val script = buildEpubChapterBreakOverrideScript(
            tocHrefs = listOf("OEBPS/chapter.xhtml#chapter-2"),
            enabled = true,
        )

        assertTrue(script.contains("break-before: column !important"))
        assertTrue(script.contains("OEBPS/chapter.xhtml#chapter-2"))
        assertTrue(script.contains("document.getElementById(fragment)"))
        assertTrue(script.contains("target.nextElementSibling.matches(headingSelector)"))
    }

    @Test
    fun `scroll layout removes paginated chapter breaks`() {
        val script = buildEpubChapterBreakOverrideScript(
            tocHrefs = listOf("chapter.xhtml#chapter-2"),
            enabled = false,
        )

        assertTrue(script.contains("style.remove()"))
        assertTrue(script.contains("element.removeAttribute('$EPUB_CHAPTER_BREAK_TARGET_ATTRIBUTE')"))
        assertFalse(script.contains("break-before: column !important"))
    }

    @Test
    fun `start alignment overrides body text without hyphenation`() {
        val script = buildEpubTextAlignmentOverrideScript(EpubLayoutPreferences.TextAlignment.START)

        assertTrue(script.contains("text-align: start !important"))
        assertTrue(script.contains("hyphens: none !important"))
        assertTrue(script.contains("p, li, dd, blockquote, div, section"))
    }

    @Test
    fun `physical edge alignment overrides use explicit sides`() {
        val left = buildEpubTextAlignmentOverrideScript(EpubLayoutPreferences.TextAlignment.LEFT)
        val right = buildEpubTextAlignmentOverrideScript(EpubLayoutPreferences.TextAlignment.RIGHT)

        assertTrue(left.contains("text-align: left !important"))
        assertTrue(left.contains("hyphens: none !important"))
        assertTrue(right.contains("text-align: right !important"))
        assertTrue(right.contains("hyphens: none !important"))
        assertTrue(right.contains("float: right !important"))
        assertTrue(right.contains("width: var(--USER__paraIndent, 2rem) !important"))
        assertTrue(right.contains("text-indent: 0 !important"))
        assertTrue(left.contains("element.removeAttribute('$EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE')"))
    }

    @Test
    fun `paragraph indentation does not classify global right alignment as structural`() {
        assertFalse(APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT.contains("textAlign === 'right'"))
        assertFalse(APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT.contains("textAlign === 'end'"))
        assertTrue(APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT.contains("structuralClasses.test(className)"))
    }

    @Test
    fun `alignment override preserves structural and media elements`() {
        val script = buildEpubTextAlignmentOverrideScript(EpubLayoutPreferences.TextAlignment.JUSTIFY)

        assertTrue(script.contains("role === 'heading'"))
        assertTrue(script.contains("originalAlignment === 'center'"))
        assertTrue(script.contains("img, svg, picture, video, audio, figure, table, canvas"))
        assertTrue(script.contains("element.closest('h1, h2, h3, h4, h5, h6"))
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
        assertTrue(
            buildEpubTextAlignmentOverrideScript(EpubLayoutPreferences.TextAlignment.JUSTIFY).contains(
                "document.documentElement.localName.toLowerCase() !== 'html'",
            ),
        )
        assertTrue(
            buildEpubLongWordWrapScript(enabled = true).contains(
                "document.documentElement.localName.toLowerCase() !== 'html'",
            ),
        )
    }

    @Test
    fun `fixed layout removes long word wrapping policy`() {
        val script = buildEpubDocumentPreparationScript(
            paragraphIndentOverrideEnabled = false,
            textAlignment = null,
            tocHrefs = emptyList(),
            chapterBreaksEnabled = false,
            preserveImageColors = true,
            parentColorsInverted = false,
            readerFontScale = 1f,
            longWordWrappingEnabled = false,
        )

        assertTrue(script.contains("if (style) style.remove();"))
        assertFalse(script.contains("overflow-wrap: anywhere !important"))
    }
}
