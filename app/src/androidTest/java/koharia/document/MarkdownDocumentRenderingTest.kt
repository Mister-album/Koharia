package koharia.document

import android.text.style.TypefaceSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownDocumentRenderingTest {
    @Test
    fun codeBlocksRetainNewlinesIndentationAndLiteralHtml() {
        val code = "def hello():\n    print(\"<h1>not a heading</h1>\")\n\n    return 1\n"
        val rendered = MarkdownDocumentRenderer.render(MarkdownHtmlRenderer.render("```python\n$code```"))
        assertEquals(code.trimEnd('\n'), rendered.text.toString().trimEnd('\n'))
        assertTrue(rendered.headings.isEmpty())
        assertTrue(rendered.text.getSpans(0, rendered.text.length, TypefaceSpan::class.java).isNotEmpty())
    }

    @Test
    fun actualHeadingOffsetsIgnoreContentsAndSurvivePageBreaks() {
        val rendered = MarkdownDocumentRenderer.render(
            MarkdownHtmlRenderer.render("[Notes](#notes)\n\n# Notes\n\nBody\n\n## Notes"),
        )
        val text = rendered.text.toString()
        assertEquals(2, rendered.headings.size)
        val first = rendered.headings[0]
        assertTrue(first.offset > text.indexOf("Notes"))
        assertEquals("Notes", text.substring(first.offset, first.offset + first.title.length))
        val split = first.offset + 2
        val headings = resolveHeadings(rendered.headings, listOf(text.take(split), text.drop(split)))
        assertEquals(listOf(0, 1), headings.map { it.pageIndex })
    }

    @Test
    fun headingEntitiesAndInlineCodeUseTheExactRenderedText() {
        val rendered = MarkdownDocumentRenderer.render(
            MarkdownHtmlRenderer.render("# A&nbsp;B &copy; `x  y` &amp;#39;"),
        )
        val heading = rendered.headings.single()
        assertEquals("A\u00a0B © x  y &#39;", heading.title)
        assertEquals(
            heading.title,
            rendered.text.subSequence(heading.offset, heading.offset + heading.title.length).toString(),
        )
    }
}
