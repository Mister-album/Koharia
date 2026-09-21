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

    /** Named HTML entities used by markdown rendering (case-insensitive; "&AMP;" is also matched). */
    private val namedEntityPattern = Regex("""&(amp|lt|gt|quot|apos|nbsp);""", RegexOption.IGNORE_CASE)

    /** Numeric character references: `&#1234;` (decimal) or `&#xABCD;` (hex). */
    private val numericEntityPattern = Regex("""&#(x[0-9A-Fa-f]+|\d+);""")

    /**
     * Parses the rendered HTML for `<h1>`–`<h6>` tags and returns [RawDocumentHeading]
     * descriptors in document order. Nested HTML inside headings is stripped; named HTML
     * entities and numeric character references are decoded. Used to build a synthetic
     * table-of-contents for the in-reader navigation sheet when a Markdown file is opened
     * as a reflowable document. Headings whose body is empty after stripping (e.g.
     * image-only headings) are dropped.
     */
    fun extractHeadings(html: String): List<RawDocumentHeading> {
        if (html.isBlank()) return emptyList()
        return headingPattern.findAll(html).mapNotNull { match ->
            val level = match.groupValues[1].drop(1).toIntOrNull() ?: return@mapNotNull null
            val text = match.groupValues[2]
                .replace(nestedTagPattern, "")
                .replace(namedEntityPattern) { entity -> decodeNamedEntity(entity.groupValues[1]) }
                .replace(numericEntityPattern) { entity -> decodeNumericEntity(entity.groupValues[1]) }
                .trim()
            if (text.isBlank()) null else RawDocumentHeading(level, text)
        }.toList()
    }

    /** Decodes a small set of named HTML entities used by the markdown renderer. */
    private fun decodeNamedEntity(name: String): String = when (name.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> "&$name;"
    }

    /**
     * Decodes a numeric character reference (decimal `&#1234;` or hex `&#xABCD;`) to its
     * Unicode code point. Falls back to the original text for malformed references.
     */
    private fun decodeNumericEntity(reference: String): String {
        val code = if (reference.startsWith("x") || reference.startsWith("X")) {
            reference.substring(1).toIntOrNull(16)
        } else {
            reference.toIntOrNull()
        } ?: return "&#$reference;"
        if (code < 0 || code > 0x10FFFF) return "&#$reference;"
        return String(Character.toChars(code))
    }
}
