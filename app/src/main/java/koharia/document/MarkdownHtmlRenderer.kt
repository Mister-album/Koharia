package koharia.document

import logcat.LogPriority
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import tachiyomi.core.common.util.system.logcat

/** Converts Markdown (GFM) to HTML. Pure JVM apart from the [logcat] fallback diagnostic. */
internal object MarkdownHtmlRenderer {
    private val flavour = GFMFlavourDescriptor()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        return try {
            val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        } catch (error: Exception) {
            // Catch Exception (not Throwable): a parser failure degrades to unrendered Markdown,
            // but Errors such as OutOfMemoryError on a near-limit file must propagate.
            logcat(LogPriority.WARN) { "[MarkdownHtmlRenderer] markdown render failed, using raw text" }
            markdown
        }
    }

    private val headingPattern = Regex(
        """<(h[1-6])(?:\s[^>]*)?>(.*?)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    private val nestedTagPattern = Regex("""<[^>]+>""")

    /** Named HTML entities used by markdown rendering (case-insensitive; "&AMP;" is also matched). */
    private val namedEntityPattern = Regex("""&(amp|lt|gt|quot|apos|nbsp);""", RegexOption.IGNORE_CASE)

    /**
     * Numeric character references: `&#1234;` (decimal) or `&#xABCD;` / `&#XABCD;` (hex).
     * HTML5 accepts an uppercase `X`, and `Html.fromHtml` decodes it, so the extractor must too —
     * otherwise such a heading never text-matches its rendered page and vanishes from the ToC.
     */
    private val numericEntityPattern = Regex("""&#(x[0-9A-Fa-f]+|\d+);""", RegexOption.IGNORE_CASE)

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

    /**
     * Decodes a small set of named HTML entities used by the markdown renderer.
     *
     * `&nbsp;` maps to U+00A0 (not a plain space) so the extracted title is byte-identical to the
     * text `Html.fromHtml` produces for the page; [resolveHeadings] matches with `contains`, which
     * is exact, so a mismatch would silently drop the heading from the ToC.
     */
    private fun decodeNamedEntity(name: String): String = when (name.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> "\u00A0"
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
