package koharia.epub

/**
 * Marks reliably identifiable non-standard footnote links as EPUB noterefs so Readium can resolve,
 * sanitize and report their contents through [org.readium.r2.navigator.HyperlinkNavigator].
 */
internal fun buildEpubFootnoteCompatibilityScript(
    applyReaderStyles: Boolean,
    readerFontScale: Float,
): String {
    val readerSizeRem = readerFontScale.coerceIn(0.5f, 3f)
    val referenceSizeRem = readerSizeRem * 0.75f
    val referenceTouchExpansionRem = (readerSizeRem - referenceSizeRem) / 2f
    val script = """
        (function() {
            const root = document.documentElement;
            if (!root || root.localName.toLowerCase() !== 'html') return 'unsupported';

            const epubNamespace = 'http://www.idpf.org/2007/ops';
            const applyReaderStyles = $applyReaderStyles;
            const styleId = 'koharia-footnote-reference-style';
            const referenceAttribute = 'data-koharia-footnote-reference';
            const graphicAttribute = 'data-koharia-footnote-graphic';
            const referenceClasses = new Set([
                'duokan-footnote',
                'noteref',
                'footnote-ref',
                'endnote-ref',
            ]);
            const noteClasses = new Set([
                'duokan-footnote-item',
                'footnote-item',
                'endnote-item',
            ]);

            function tokens(value) {
                return String(value || '')
                    .toLowerCase()
                    .split(/\s+/)
                    .filter(Boolean);
            }

            function epubTypes(element) {
                return tokens(
                    element.getAttributeNS(epubNamespace, 'type') ||
                    element.getAttribute('epub:type'),
                );
            }

            function hasAny(values, expected) {
                return values.some(function(value) { return expected.has(value); });
            }

            function fragmentTarget(anchor) {
                const rawHref = anchor.getAttribute('href');
                if (!rawHref) return null;
                try {
                    const url = new URL(rawHref, document.baseURI);
                    const documentUrl = new URL(document.baseURI);
                    if (url.origin !== documentUrl.origin || url.pathname !== documentUrl.pathname ||
                        url.search !== documentUrl.search || !url.hash) {
                        return null;
                    }
                    let id = url.hash.substring(1);
                    try { id = decodeURIComponent(id); } catch (_) {}
                    return document.getElementById(id);
                } catch (_) {
                    return null;
                }
            }

            function isSemanticNoteTarget(target) {
                if (!target) return false;
                const role = String(target.getAttribute('role') || '').toLowerCase();
                if (role === 'doc-footnote' || role === 'doc-endnote') return true;
                if (epubTypes(target).some(function(type) { return type === 'footnote' || type === 'endnote'; })) {
                    return true;
                }
                return hasAny(tokens(target.getAttribute('class')), noteClasses);
            }

            function updateReferenceStyle(anchor) {
                anchor.removeAttribute(referenceAttribute);
                anchor.removeAttribute(graphicAttribute);
                if (!applyReaderStyles) return;
                anchor.setAttribute(referenceAttribute, 'true');
                const hasGraphic = !!anchor.querySelector(
                    'img, svg, picture, object, input[type="image"], [role="img"]',
                );
                if (hasGraphic || !String(anchor.textContent || '').trim()) {
                    anchor.setAttribute(graphicAttribute, 'true');
                }
            }

            const previousStyle = document.getElementById(styleId);
            if (previousStyle) previousStyle.remove();
            if (applyReaderStyles) {
                const style = document.createElement('style');
                style.id = styleId;
                style.textContent = `
                    a[${'$'}{referenceAttribute}="true"] {
                        position: relative !important;
                        box-sizing: content-box !important;
                        display: inline-block !important;
                        font-size: ${referenceSizeRem}rem !important;
                        line-height: 1 !important;
                        vertical-align: super !important;
                        overflow: visible !important;
                        z-index: 1 !important;
                    }
                    a[${'$'}{referenceAttribute}="true"]::after {
                        content: "" !important;
                        position: absolute !important;
                        inset: -${referenceTouchExpansionRem}rem !important;
                    }
                    a[${'$'}{referenceAttribute}="true"]:not(
                        [${'$'}{graphicAttribute}="true"]
                    ) {
                        padding: 0 !important;
                        margin: 0 !important;
                        white-space: nowrap !important;
                        transform: none !important;
                    }
                    a[${'$'}{referenceAttribute}="true"]:not(
                        [${'$'}{graphicAttribute}="true"]
                    ) * {
                        font-size: inherit !important;
                        line-height: inherit !important;
                        vertical-align: baseline !important;
                    }
                    a[${'$'}{graphicAttribute}="true"] {
                        padding: 0 !important;
                        margin: 0 !important;
                        width: 1em !important;
                        height: 1em !important;
                        min-width: 0 !important;
                        min-height: 0 !important;
                        max-width: 1em !important;
                        max-height: 1em !important;
                        transform: none !important;
                        background-size: contain !important;
                        background-position: center !important;
                        background-repeat: no-repeat !important;
                    }
                    a[${'$'}{graphicAttribute}="true"] :is(
                        img, svg, picture, object, input[type="image"], [role="img"]
                    ) {
                        position: static !important;
                        display: block !important;
                        box-sizing: border-box !important;
                        padding: 0 !important;
                        margin: 0 !important;
                        width: 100% !important;
                        height: 100% !important;
                        min-width: 0 !important;
                        min-height: 0 !important;
                        max-width: 100% !important;
                        max-height: 100% !important;
                        object-fit: contain !important;
                        transform: none !important;
                    }
                    a[${'$'}{graphicAttribute}="true"] > * {
                        width: 100% !important;
                        height: 100% !important;
                        max-width: 100% !important;
                        max-height: 100% !important;
                    }
                `;
                (document.head || root).appendChild(style);
            }

            let marked = 0;
            Array.from(document.querySelectorAll('a[href]')).forEach(function(anchor) {
                const isStandardReference = epubTypes(anchor).includes('noteref');
                const role = String(anchor.getAttribute('role') || '').toLowerCase();
                const isReference = isStandardReference || role === 'doc-noteref' ||
                    hasAny(tokens(anchor.getAttribute('class')), referenceClasses) ||
                    isSemanticNoteTarget(fragmentTarget(anchor));
                if (!isReference) {
                    anchor.removeAttribute(referenceAttribute);
                    anchor.removeAttribute(graphicAttribute);
                    return;
                }
                if (!isStandardReference) {
                    try {
                        anchor.setAttributeNS(epubNamespace, 'epub:type', 'noteref');
                    } catch (_) {
                        anchor.setAttribute('epub:type', 'noteref');
                    }
                    marked += 1;
                }
                updateReferenceStyle(anchor);
            });
            root.setAttribute('data-koharia-footnotes-prepared', 'true');
            return marked;
        })()
    """.trimIndent()
    return script
}
