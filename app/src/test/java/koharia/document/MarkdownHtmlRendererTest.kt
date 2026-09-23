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
}
