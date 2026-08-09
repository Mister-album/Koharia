package koharia.epub

import koharia.epub.settings.EpubLayoutPreferences

internal const val EPUB_PARAGRAPH_INDENT_STYLE_ID = "koharia-paragraph-indent"
internal const val EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE = "data-koharia-no-paragraph-indent"
internal const val EPUB_ORPHANED_INLINE_PADDING_ATTRIBUTE = "data-koharia-orphaned-inline-padding"
internal const val EPUB_TEXT_ALIGNMENT_STYLE_ID = "koharia-text-alignment"
internal const val EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE = "data-koharia-text-alignment-target"
internal const val EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE = "data-koharia-right-indent-spacer"
internal const val EPUB_LONG_WORD_WRAP_STYLE_ID = "koharia-long-word-wrap"

internal const val EPUB_LONG_WORD_WRAP_CSS =
    "html:root:root:root body, html:root:root:root body * { " +
        "word-wrap: break-word !important; overflow-wrap: anywhere !important; " +
        "}"

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
        if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
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
            var className = typeof paragraph.className === 'string' ? paragraph.className : '';
            var structuralClasses = /(^|[-_ ])(title|subtitle|heading|headline|caption|credit|signature|author|date|center|right)([-_ ]|${'$'})/i;
            var hasOnlyMedia = paragraph.textContent.trim() === '' &&
                paragraph.querySelector('img, svg, picture, video, audio, figure, table');
            var isStructuralParagraph =
                textAlign === 'center' || structuralClasses.test(className) ||
                role === 'heading' || epubType.indexOf('title') !== -1 || hasOnlyMedia;
            if (isStructuralParagraph) {
                paragraph.setAttribute('$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE', '');
                Array.from(paragraph.children).forEach(function(child) {
                    if (child.tagName.toLowerCase() !== 'span') return;
                    var childStyle = window.getComputedStyle(child);
                    var horizontalPadding = parseFloat(childStyle.paddingLeft) + parseFloat(childStyle.paddingRight);
                    var hasTransparentBackground =
                        childStyle.backgroundColor === 'rgba(0, 0, 0, 0)' ||
                        childStyle.backgroundColor === 'transparent';
                    var hasOrphanedPadding = childStyle.display === 'inline' && horizontalPadding > 0 &&
                        hasTransparentBackground && childStyle.color === computed.color;
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
        if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
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

internal fun buildEpubLongWordWrapScript(enabled: Boolean): String {
    val updateStyle = if (enabled) {
        """
            if (!style) {
                style = document.createElementNS('http://www.w3.org/1999/xhtml', 'style');
                style.id = '$EPUB_LONG_WORD_WRAP_STYLE_ID';
                style.setAttribute('type', 'text/css');
                (document.head || document.documentElement).appendChild(style);
            }
            style.textContent = '$EPUB_LONG_WORD_WRAP_CSS';
        """.trimIndent()
    } else {
        "if (style) style.remove();"
    }
    return """
        (function() {
            if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
            var style = document.getElementById('$EPUB_LONG_WORD_WRAP_STYLE_ID');
            $updateStyle
            return true;
        })()
    """.trimIndent()
}

internal fun buildEpubTextAlignmentOverrideScript(
    textAlignment: EpubLayoutPreferences.TextAlignment?,
): String {
    if (textAlignment == null) {
        return """
            (function() {
                if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
                var style = document.getElementById('$EPUB_TEXT_ALIGNMENT_STYLE_ID');
                if (style) style.remove();
                Array.from(document.querySelectorAll('[$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE]')).forEach(function(element) {
                    element.removeAttribute('$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE');
                    element.removeAttribute('$EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE');
                });
                return true;
            })()
        """.trimIndent()
    }
    val alignmentCss = when (textAlignment) {
        EpubLayoutPreferences.TextAlignment.START ->
            "text-align: start !important; " +
                "-webkit-hyphens: none !important; hyphens: none !important;"
        EpubLayoutPreferences.TextAlignment.LEFT ->
            "text-align: left !important; " +
                "-webkit-hyphens: none !important; hyphens: none !important;"
        EpubLayoutPreferences.TextAlignment.RIGHT ->
            "text-align: right !important; " +
                "-webkit-hyphens: none !important; hyphens: none !important;"
        EpubLayoutPreferences.TextAlignment.JUSTIFY ->
            "text-align: justify !important; text-justify: auto !important; " +
                "-webkit-hyphens: auto !important; hyphens: auto !important;"
    }
    val rightIndentCss = if (textAlignment == EpubLayoutPreferences.TextAlignment.RIGHT) {
        " html:root:root:root p[$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE]" +
            ":not([$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE]) { text-indent: 0 !important; }" +
            " html:root:root:root p[$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE]" +
            "[$EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE]:not([$EPUB_PARAGRAPH_NO_INDENT_ATTRIBUTE])::before { " +
            "content: \"\" !important; float: right !important; " +
            "width: var(--USER__paraIndent, 2rem) !important; height: 1em !important; " +
            "pointer-events: none !important; }"
    } else {
        ""
    }
    return """
        (function() {
            if (!document.documentElement || document.documentElement.localName.toLowerCase() !== 'html') return false;
            var style = document.getElementById('$EPUB_TEXT_ALIGNMENT_STYLE_ID');
            if (style) style.remove();
            Array.from(document.querySelectorAll('[$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE]')).forEach(function(element) {
                element.removeAttribute('$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE');
                element.removeAttribute('$EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE');
            });
            style = document.createElementNS('http://www.w3.org/1999/xhtml', 'style');
            style.id = '$EPUB_TEXT_ALIGNMENT_STYLE_ID';
            style.setAttribute('type', 'text/css');
            style.textContent =
                'html:root:root:root [$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE] { $alignmentCss }' +
                '$rightIndentCss';
            (document.head || document.documentElement).appendChild(style);

            var blockTags = /^(p|li|dd|blockquote|div|section)$/;
            var nestedBlockTags = /^(p|li|dd|blockquote|div|section|article|aside|header|footer|nav|main)$/;
            var structuralClasses = /(^|[-_ ])(title|subtitle|heading|headline|caption|credit|signature|author|date|center|right)([-_ ]|${'$'})/i;
            Array.from(document.querySelectorAll('p, li, dd, blockquote, div, section')).forEach(function(element) {
                var tagName = element.localName.toLowerCase();
                if (!blockTags.test(tagName)) return;
                var role = (element.getAttribute('role') || '').toLowerCase();
                var epubType = (
                    element.getAttribute('epub:type') ||
                    element.getAttributeNS('http://www.idpf.org/2007/ops', 'type') ||
                    ''
                ).toLowerCase();
                var className = typeof element.className === 'string' ? element.className : '';
                var computed = window.getComputedStyle(element);
                var originalAlignment = (computed.textAlign || '').toLowerCase();
                var text = (element.textContent || '').trim();
                var hasOnlyMedia = text === '' && element.querySelector(
                    'img, svg, picture, video, audio, figure, table, canvas',
                );
                var hasNestedBlock = Array.from(element.children).some(function(child) {
                    return nestedBlockTags.test(child.localName.toLowerCase());
                });
                var isStructural =
                    role === 'heading' ||
                    epubType.indexOf('title') !== -1 ||
                    epubType.indexOf('subtitle') !== -1 ||
                    epubType.indexOf('heading') !== -1 ||
                    structuralClasses.test(className) ||
                    originalAlignment === 'center' ||
                    element.closest('h1, h2, h3, h4, h5, h6, figure, figcaption, table, pre, code, nav');
                if (!text || hasOnlyMedia || hasNestedBlock || isStructural) return;
                element.setAttribute('$EPUB_TEXT_ALIGNMENT_TARGET_ATTRIBUTE', '');
                if (${textAlignment == EpubLayoutPreferences.TextAlignment.RIGHT} && tagName === 'p') {
                    var beforeContent = window.getComputedStyle(element, '::before').content;
                    var hasGeneratedBeforeContent =
                        beforeContent && beforeContent !== 'none' && beforeContent !== 'normal' && beforeContent !== '""';
                    if (!hasGeneratedBeforeContent) {
                        element.setAttribute('$EPUB_RIGHT_INDENT_SPACER_ATTRIBUTE', '');
                    }
                }
            });
            return true;
        })()
    """.trimIndent()
}

internal fun buildEpubTypographyPreparationScript(
    paragraphIndentOverrideEnabled: Boolean,
    textAlignment: EpubLayoutPreferences.TextAlignment?,
    longWordWrappingEnabled: Boolean = true,
): String {
    val paragraphIndentScript = if (paragraphIndentOverrideEnabled) {
        APPLY_EPUB_PARAGRAPH_INDENT_SCRIPT
    } else {
        REMOVE_EPUB_PARAGRAPH_INDENT_SCRIPT
    }
    return """
        (function() {
            $paragraphIndentScript;
            ${buildEpubTextAlignmentOverrideScript(textAlignment)};
            ${buildEpubLongWordWrapScript(longWordWrappingEnabled)};
            return true;
        })()
    """.trimIndent()
}

internal fun buildEpubLayoutPreparationScript(
    paragraphIndentOverrideEnabled: Boolean,
    textAlignment: EpubLayoutPreferences.TextAlignment?,
    tocHrefs: List<String>,
    chapterBreaksEnabled: Boolean,
    longWordWrappingEnabled: Boolean = true,
): String =
    """
        (function() {
            ${buildEpubTypographyPreparationScript(
        paragraphIndentOverrideEnabled = paragraphIndentOverrideEnabled,
        textAlignment = textAlignment,
        longWordWrappingEnabled = longWordWrappingEnabled,
    )};
            ${buildEpubChapterBreakOverrideScript(tocHrefs, chapterBreaksEnabled)};
            return true;
        })()
    """.trimIndent()

internal fun buildEpubDocumentPreparationScript(
    paragraphIndentOverrideEnabled: Boolean,
    textAlignment: EpubLayoutPreferences.TextAlignment?,
    tocHrefs: List<String>,
    chapterBreaksEnabled: Boolean,
    preserveImageColors: Boolean,
    parentColorsInverted: Boolean,
    readerFontScale: Float,
    longWordWrappingEnabled: Boolean = true,
): String {
    val layoutPreparationScript = buildEpubLayoutPreparationScript(
        paragraphIndentOverrideEnabled = paragraphIndentOverrideEnabled,
        textAlignment = textAlignment,
        tocHrefs = tocHrefs,
        chapterBreaksEnabled = chapterBreaksEnabled,
        longWordWrappingEnabled = longWordWrappingEnabled,
    )
    return """
        (function() {
            $layoutPreparationScript;
            ${buildEpubFootnoteCompatibilityScript(
        applyReaderStyles = paragraphIndentOverrideEnabled,
        readerFontScale = readerFontScale,
    )};
            ${buildEpubImageColorPolicyScript(preserveImageColors, parentColorsInverted)};
            return 'prepared';
        })()
    """.trimIndent()
}

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
