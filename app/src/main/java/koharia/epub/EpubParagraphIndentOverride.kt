package koharia.epub

internal const val EPUB_PARAGRAPH_INDENT_STYLE_ID = "koharia-paragraph-indent"
internal const val EPUB_PARAGRAPH_INDENT_CSS =
    "p { text-indent: var(--USER__paraIndent, 2rem) !important; }"

internal const val APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT =
    """(function() {
        var style = document.getElementById('$EPUB_PARAGRAPH_INDENT_STYLE_ID');
        if (!style) {
            style = document.createElementNS('http://www.w3.org/1999/xhtml', 'style');
            style.id = '$EPUB_PARAGRAPH_INDENT_STYLE_ID';
            style.setAttribute('type', 'text/css');
            (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = '$EPUB_PARAGRAPH_INDENT_CSS';
        return true;
    })()"""

internal const val REMOVE_EPUB_PARAGRAPH_INDENT_SCRIPT =
    """(function() {
        var style = document.getElementById('$EPUB_PARAGRAPH_INDENT_STYLE_ID');
        if (style) style.remove();
        return true;
    })()"""

internal fun String.injectEpubParagraphIndentStyle(): String {
    if (contains("id=\"$EPUB_PARAGRAPH_INDENT_STYLE_ID\"", ignoreCase = true)) return this

    val style =
        "<style id=\"$EPUB_PARAGRAPH_INDENT_STYLE_ID\" type=\"text/css\">" +
            EPUB_PARAGRAPH_INDENT_CSS +
            "</style>"
    val closingHead = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(this)
    if (closingHead != null) {
        return replaceRange(closingHead.range.first, closingHead.range.first, style)
    }

    val openingBody = Regex("<body\\b", RegexOption.IGNORE_CASE).find(this)
    return if (openingBody != null) {
        replaceRange(openingBody.range.first, openingBody.range.first, style)
    } else {
        style + this
    }
}
