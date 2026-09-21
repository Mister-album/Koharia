package koharia.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownHtmlRendererTest {

    @Test
    fun `empty input renders an empty string`() {
        assertEquals("", MarkdownHtmlRenderer.render(""))
    }

    @Test
    fun `heading bold and italic are rendered together`() {
        val html = MarkdownHtmlRenderer.render("# H\n\n**bold** and *ital*")
        assertTrue("<h1>" in html)
        assertTrue("<strong>" in html)
        assertTrue("<em>" in html)
    }

    @Test
    fun `setext heading renders an h1 element`() {
        val html = MarkdownHtmlRenderer.render("Title\n=====")
        assertTrue("<h1>" in html)
    }

    @Test
    fun `unordered list renders ul and li elements`() {
        val html = MarkdownHtmlRenderer.render("- a\n- b")
        assertTrue("<ul>" in html)
        assertTrue("<li>" in html)
    }

    @Test
    fun `ordered list renders an ol element`() {
        val html = MarkdownHtmlRenderer.render("1. a\n2. b")
        assertTrue("<ol>" in html)
    }

    @Test
    fun `fenced code block renders a pre element`() {
        val html = MarkdownHtmlRenderer.render("```\nval x = 1\n```")
        assertTrue("<pre>" in html)
    }

    @Test
    fun `blockquote renders a blockquote element`() {
        val html = MarkdownHtmlRenderer.render("> q")
        assertTrue("<blockquote>" in html)
    }

    @Test
    fun `inline code renders a code element`() {
        val html = MarkdownHtmlRenderer.render("use `x` here")
        assertTrue("<code>" in html)
    }

    @Test
    fun `links keep their destination`() {
        val html = MarkdownHtmlRenderer.render("[t](https://e.com)")
        assertTrue("https://e.com" in html)
    }

    @Test
    fun `gfm table renders a table element`() {
        val html = MarkdownHtmlRenderer.render("| a | b |\n| --- | --- |\n| c | d |")
        assertTrue("<table>" in html)
    }

    @Test
    fun `extractHeadings returns atx headings in document order`() {
        val html = MarkdownHtmlRenderer.render("# First\n\n## Second\n\n# First dup")
        assertEquals(
            listOf(
                RawDocumentHeading(1, "First"),
                RawDocumentHeading(2, "Second"),
                RawDocumentHeading(1, "First dup"),
            ),
            MarkdownHtmlRenderer.extractHeadings(html),
        )
    }

    @Test
    fun `extractHeadings decodes common HTML entities and strips nested tags`() {
        val html = "<h1>A &amp; <em>B</em> &lt;tag&gt;</h1>"
        assertEquals(listOf(RawDocumentHeading(1, "A & B <tag>")), MarkdownHtmlRenderer.extractHeadings(html))
    }

    @Test
    fun `extractHeadings drops headings with empty body`() {
        val html = "<h1><img src=\"x.png\" alt=\"\"/></h1><h2>Real</h2>"
        assertEquals(listOf(RawDocumentHeading(2, "Real")), MarkdownHtmlRenderer.extractHeadings(html))
    }

    @Test
    fun `extractHeadings returns empty list for blank input`() {
        assertEquals(emptyList<RawDocumentHeading>(), MarkdownHtmlRenderer.extractHeadings(""))
    }

    @Test
    fun `extractHeadings decodes numeric character references`() {
        val html = "<h2>Don&#39;t &amp; stop &#x4e2d;&#x6587;</h2>"
        assertEquals(
            listOf(RawDocumentHeading(2, "Don't & stop 中文")),
            MarkdownHtmlRenderer.extractHeadings(html),
        )
    }

    @Test
    fun `extractHeadings decodes uppercase-hex numeric references`() {
        // HTML5 allows &#X...; and Html.fromHtml decodes it, so the extractor must match.
        val html = "<h3>&#X4E2D;&#X6587;</h3>"
        assertEquals(
            listOf(RawDocumentHeading(3, "中文")),
            MarkdownHtmlRenderer.extractHeadings(html),
        )
    }

    @Test
    fun `extractHeadings decodes nbsp to a non-breaking space`() {
        // Must stay byte-identical to what Html.fromHtml renders, otherwise resolveHeadings'
        // exact `contains` match fails and the heading silently disappears from the ToC.
        val html = "<h1>A&nbsp;B</h1>"
        assertEquals(
            listOf(RawDocumentHeading(1, "A\u00A0B")),
            MarkdownHtmlRenderer.extractHeadings(html),
        )
    }

    @Test
    fun `extractHeadings decodes named entities case-insensitively`() {
        val html = "<h2>Tom &AMP; Jerry</h2>"
        assertEquals(
            listOf(RawDocumentHeading(2, "Tom & Jerry")),
            MarkdownHtmlRenderer.extractHeadings(html),
        )
    }
}
