package koharia.epub

internal const val EPUB_PARAGRAPH_INDENT_STYLE_ID = "koharia-paragraph-indent"
internal const val EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE = "data-koharia-no-paragraph-indent"
internal const val EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE = "data-koharia-orphaned-inline-padding"

// Readium's paragraph preference targets every <p>. A higher-specificity exception keeps
// paragraph-shaped headings, signatures and media containers from inheriting body indentation.
internal const val EPUB_PARAGRAPH_INDENT_CSS =
    "html:root:root:root p:not([$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE]) { " +
        "text-indent: var(--USER__paraIndent, 2rem) !important; " +
        "} html:root:root:root p[$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE] { text-indent: 0 !important; } " +
        "html:root:root:root [$EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE] { " +
        "padding: 0 !important; vertical-align: baseline !important; }"

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
        Array.from(document.querySelectorAll('[$EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE]')).forEach(function(element) {
            element.removeAttribute('$EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE');
        });
        Array.from(document.querySelectorAll('p')).forEach(function(paragraph) {
            paragraph.removeAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE');
            var computed = window.getComputedStyle(paragraph);
            var textAlign = (computed.textAlign || '').toLowerCase();
            var role = (paragraph.getAttribute('role') || '').toLowerCase();
            var epubType = (paragraph.getAttribute('epub:type') || '').toLowerCase();
            var hasOnlyMedia = paragraph.textContent.trim() === '' &&
                paragraph.querySelector('img, svg, picture, video, audio, figure, table');
            var isStructuralParagraph =
                textAlign === 'center' || textAlign === 'right' || textAlign === 'end' ||
                role === 'heading' || epubType.indexOf('title') !== -1 || hasOnlyMedia;
            if (isStructuralParagraph) {
                paragraph.setAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE', '');
                Array.from(paragraph.children).forEach(function(child) {
                    var childStyle = window.getComputedStyle(child);
                    var paragraphStyle = window.getComputedStyle(paragraph);
                    var horizontalPadding = parseFloat(childStyle.paddingLeft) + parseFloat(childStyle.paddingRight);
                    var hasOrphanedPadding = child.tagName.toLowerCase() === 'span' &&
                        childStyle.display === 'inline' && horizontalPadding > 0 &&
                        childStyle.backgroundColor === 'rgba(0, 0, 0, 0)' &&
                        childStyle.color === paragraphStyle.color;
                    if (hasOrphanedPadding) {
                        child.setAttribute('$EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE', '');
                    }
                });
            }
        });
        return true;
    })()"""

internal const val REMOVE_EPUB_PARAGRAPH_INDENT_SCRIPT =
    """(function() {
        var style = document.getElementById('$EPUB_PARAGRAPH_INDENT_STYLE_ID');
        if (style) style.remove();
        Array.from(document.querySelectorAll('p[$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE]')).forEach(function(paragraph) {
            paragraph.removeAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE');
        });
        Array.from(document.querySelectorAll('[$EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE]')).forEach(function(element) {
            element.removeAttribute('$EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE');
        });
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
