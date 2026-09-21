package koharia.document

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/** Converts Markdown (GFM) to HTML. Pure JVM — no Android dependencies, so it is unit-testable. */
internal object MarkdownHtmlRenderer {
    private val flavour = GFMFlavourDescriptor()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        return runCatching {
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        }.getOrDefault(markdown)
    }

    private val headingPattern = Regex(
        """<(h[1-6])(?:\s[^>]*)?>(.*?)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val nestedTagPattern = Regex("""<[^>]+>""")
    private val namedEntityPattern = Regex("""&(amp|lt|gt|quot|nbsp);""")

    /**
     * Parses the rendered HTML for `<h1>`–`<h6>` tags and returns (level, plain text) pairs in
     * document order. Nested HTML inside headings is stripped; common HTML entities are
     * decoded. Used to build a synthetic table-of-contents for the in-reader navigation sheet
     * when a Markdown file is opened as a reflowable document. Headings whose body is empty
     * after stripping (e.g. image-only headings) are dropped.
     */
    fun extractHeadings(html: String): List<Pair<Int, String>> {
        if (html.isBlank()) return emptyList()
        return headingPattern.findAll(html).mapNotNull { match ->
            val level = match.groupValues[1].drop(1).toIntOrNull() ?: return@mapNotNull null
            val text = match.groupValues[2]
                .replace(nestedTagPattern, "")
                .replace(namedEntityPattern) { entity ->
                    when (entity.groupValues[1].lowercase()) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "nbsp" -> " "
                        else -> entity.value
                    }
                }
                .trim()
            if (text.isBlank()) null else level to text
        }.toList()
    }
}
