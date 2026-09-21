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
            logcat(LogPriority.WARN, error) { "[MarkdownHtmlRenderer] markdown render failed, using raw text" }
            markdown
        }
    }

    /** `<br>`, `<br/>`, `<br />` — rendered as a newline by Html.fromHtml. */
    private val breakTagPattern = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

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
        val out = ArrayList<RawDocumentHeading>()
        // Linear scan: locate each `<hN ...>` open tag, then indexOf its matching close tag. A
        // regex with a lazy body plus a backreference would rescan to end-of-input for every
        // unclosed tag (unbalanced inline HTML passes through intellij-markdown verbatim), making
        // crafted input quadratic.
        var cursor = 0
        // Once a level's close tag is absent from a position onward it is absent for the whole
        // remainder, so a single failed lookup per level is enough to keep the scan linear even
        // when the input contains many unclosed headings.
        val closeTagAbsent = BooleanArray(7)
        while (true) {
            val open = html.indexOf("<h", cursor, ignoreCase = true)
            if (open < 0) break
            val levelChar = html.getOrNull(open + 2)
            if (levelChar == null || levelChar !in '1'..'6') {
                cursor = open + 2
                continue
            }
            // The tag name must be exactly h1..h6, so the next char has to end the name.
            val afterName = html.getOrNull(open + 3)
            if (afterName != null && afterName != '>' && !afterName.isWhitespace()) {
                cursor = open + 3
                continue
            }
            val openEnd = html.indexOf('>', open + 3)
            if (openEnd < 0) break
            val level = levelChar - '0'
            val closeTag = "</h$levelChar>"
            if (closeTagAbsent[level]) {
                cursor = openEnd + 1
                continue
            }
            val close = html.indexOf(closeTag, openEnd + 1, ignoreCase = true)
            if (close < 0) {
                // Unclosed heading: remember that this level has no close tag from here on, and
                // skip past this open tag rather than rescanning the remainder.
                closeTagAbsent[level] = true
                cursor = openEnd + 1
                continue
            }
            val text = html.substring(openEnd + 1, close)
                // A GFM hard line break renders as <br>; Html.fromHtml turns it into a newline.
                // Replace it before the generic tag strip, otherwise the words on either side are
                // concatenated and the title no longer text-matches the rendered page.
                .replace(breakTagPattern, "\n")
                .replace(nestedTagPattern, "")
                // Numeric pass runs FIRST: the named pass can synthesize numeric input
                // (`&amp;#39;` -> `&#39;`), which would then be decoded a second time. Html.fromHtml
                // decodes entities exactly once, so `&amp;#39;` must stay `&#39;`.
                .replace(numericEntityPattern) { entity -> decodeNumericEntity(entity.groupValues[1]) }
                .replace(namedEntityPattern) { entity -> decodeNamedEntity(entity.groupValues[1]) }
                .trimPreservingNbsp()
            if (text.isNotEmpty()) {
                out += RawDocumentHeading(level, text)
            }
            cursor = close + closeTag.length
        }
        return out
    }

    /**
     * Like [String.trim] but preserves U+00A0. Kotlin's `Char.isWhitespace()` reports NBSP as
     * whitespace, which would strip the very character [decodeNamedEntity] deliberately produces
     * and break the byte-identical match against the rendered page.
     */
    private fun String.trimPreservingNbsp(): String {
        var start = 0
        var end = length
        while (start < end && this[start].isWhitespace() && this[start] != '\u00A0') start++
        while (end > start && this[end - 1].isWhitespace() && this[end - 1] != '\u00A0') end--
        return substring(start, end)
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
        // Reject values that are not valid code points: 0 (NUL) and the surrogate range would be
        // accepted by Character.toChars and inject ill-formed characters that never text-match the
        // rendered page; the upper bound rejects out-of-range references such as &#1114112;.
        if (code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF) return "&#$reference;"
        return String(Character.toChars(code))
    }
}
