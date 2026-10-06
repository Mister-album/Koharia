package koharia.epub

/**
 * Marks reliably identifiable non-standard footnote links as EPUB noterefs so Readium can resolve,
 * sanitize and report their contents through [org.readium.r2.navigator.HyperlinkNavigator].
 *
 * The referenced note containers are hidden in the same pass. Publishers such as Duokan and
 * Zhangyue keep the note body in the flowing text and expect the reading system to hide it, but
 * neither Readium CSS nor its scripts do that, so the note body would otherwise stay visible as a
 * box next to the note icon the reader already opens as a popup.
 */
internal fun buildEpubFootnoteCompatibilityScript(
    applyReaderStyles: Boolean,
    readerFontScale: Float,
): String {
    val readerSizeRem = readerFontScale.coerceIn(0.5f, 3f)
    val referenceSizeRem = readerSizeRem * 0.75f
    val referenceTouchExpansionRem = (readerSizeRem - referenceSizeRem) / 2f
    // The JavaScript recipe passes these to the CSS template literal, which needs the literal "${'$'}"
    // that introduces the interpolation.
    val cssInterpolation = "${'$'}"
    val script = """
        (function() {
            const root = document.documentElement;
            if (!root || root.localName.toLowerCase() !== 'html') return 'unsupported';

            const epubNamespace = 'http://www.idpf.org/2007/ops';
            const xhtmlNamespace = 'http://www.w3.org/1999/xhtml';
            const applyReaderStyles = $applyReaderStyles;
            const styleId = 'koharia-footnote-reference-style';
            const referenceAttribute = 'data-koharia-footnote-reference';
            const graphicAttribute = 'data-koharia-footnote-graphic';
            const noteContainerAttribute = '$EPUB_NOTE_CONTAINER_ATTRIBUTE';
            const noteContainerStyleId = '$EPUB_NOTE_CONTAINER_STYLE_ID';
            const fragmentAttribute = '$EPUB_NOTEREF_FRAGMENT_ATTRIBUTE';
            const noteContainerStyleText = `$EPUB_NOTE_CONTAINER_CSS`;
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

            function addEpubType(element, type) {
                const current = element.getAttributeNS(epubNamespace, 'type') ||
                    element.getAttribute('epub:type') || '';
                const values = String(current).trim().split(/\s+/).filter(Boolean);
                if (!values.some(function(value) { return value.toLowerCase() === type; })) {
                    values.push(type);
                }
                const updated = values.join(' ');
                try {
                    element.setAttributeNS(epubNamespace, 'epub:type', updated);
                } catch (_) {
                    element.setAttribute('epub:type', updated);
                }
            }

            function fragmentId(anchor) {
                const rawHref = anchor.getAttribute('href') || anchor.getAttribute(fragmentAttribute);
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
                    return id || null;
                } catch (_) {
                    return null;
                }
            }

            function fragmentTarget(anchor) {
                const id = fragmentId(anchor);
                return id ? document.getElementById(id) : null;
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

            function visibleText(element) {
                return String(element.textContent || '').replace(/\s+/g, '');
            }

            /** Whether the note shares its parent with other rendered content. */
            function hasVisibleSiblings(note) {
                const parent = note.parentElement;
                if (!parent) return false;
                return Array.from(parent.children).some(function(sibling) {
                    if (sibling === note || sibling.localName === 'script' || sibling.localName === 'style') {
                        return false;
                    }
                    const style = window.getComputedStyle(sibling);
                    if (style.display === 'none' || style.visibility === 'hidden') return false;
                    return visibleText(sibling) !== '' || !!sibling.querySelector('img, svg, video, audio');
                });
            }

            /** A wrapper the publisher dedicated to a note, never the running text around it. */
            function isNoteWrapper(element) {
                if (!element || !element.localName) return false;
                if (element.hasAttribute(noteContainerAttribute)) return true;
                const name = element.localName.toLowerCase();
                if (name === 'note' || name === 'aside') return true;
                return name.indexOf('note') === 0 || name.indexOf('footnote') !== -1;
            }

            /**
             * A note target carrying its own text stays hidden; a target that is only a wrapper
             * around the note (Duokan wraps the aside in a custom note element) shares the note
             * icon with the running text, so only the note itself is hidden there.
             */
            const containerHandled = [];

            function markNoteTarget(target) {
                if (!target || !target.setAttribute) return;
                if (target.localName === 'html' || target.localName === 'body') return;
                if (containerHandled.indexOf(target) === -1) {
                    containerHandled.push(target);
                    target.setAttribute(noteContainerAttribute, 'true');
                }
                let wrapper = target.parentElement;
                while (wrapper && wrapper.localName !== 'html' && wrapper.localName !== 'body') {
                    if (visibleText(wrapper) !== '') break;
                    if (!isNoteWrapper(wrapper) || hasVisibleSiblings(target)) break;
                    if (containerHandled.indexOf(wrapper) === -1) {
                        containerHandled.push(wrapper);
                        wrapper.setAttribute(noteContainerAttribute, 'true');
                    }
                    wrapper = wrapper.parentElement;
                }
            }

            function applyNoteContainers() {
                if (containerHandled.length === 0) return;
                let style = document.getElementById(noteContainerStyleId);
                if (style && style.namespaceURI !== (root.namespaceURI || xhtmlNamespace)) {
                    style.remove();
                    style = null;
                }
                if (!style) {
                    style = document.createElementNS(root.namespaceURI || xhtmlNamespace, 'style');
                    style.id = noteContainerStyleId;
                    style.setAttribute('type', 'text/css');
                    (document.head || root).appendChild(style);
                }
                style.textContent = noteContainerStyleText;
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
                const style = document.createElementNS(root.namespaceURI || xhtmlNamespace, 'style');
                style.id = styleId;
                style.setAttribute('type', 'text/css');
                style.textContent = `
                    a[$cssInterpolation{referenceAttribute}="true"] {
                        position: relative !important;
                        box-sizing: content-box !important;
                        display: inline-block !important;
                        font-size: ${referenceSizeRem}rem !important;
                        line-height: 1 !important;
                        vertical-align: super !important;
                        overflow: visible !important;
                        z-index: 1 !important;
                    }
                    a[$cssInterpolation{referenceAttribute}="true"]::after {
                        content: "" !important;
                        position: absolute !important;
                        inset: -${referenceTouchExpansionRem}rem !important;
                    }
                    a[$cssInterpolation{referenceAttribute}="true"]:not(
                        [$cssInterpolation{graphicAttribute}="true"]
                    ) {
                        padding: 0 !important;
                        margin: 0 !important;
                        white-space: nowrap !important;
                        transform: none !important;
                    }
                    a[$cssInterpolation{referenceAttribute}="true"]:not(
                        [$cssInterpolation{graphicAttribute}="true"]
                    ) * {
                        font-size: inherit !important;
                        line-height: inherit !important;
                        vertical-align: baseline !important;
                    }
                    a[$cssInterpolation{graphicAttribute}="true"] {
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
                    a[$cssInterpolation{graphicAttribute}="true"] :is(
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
                    a[$cssInterpolation{graphicAttribute}="true"] > * {
                        width: 100% !important;
                        height: 100% !important;
                        max-width: 100% !important;
                        max-height: 100% !important;
                    }
                `;
                (document.head || root).appendChild(style);
            }

            let marked = 0;

            const isReference = function(anchor) {
                const role = String(anchor.getAttribute('role') || '').toLowerCase();
                return epubTypes(anchor).includes('noteref') || role === 'doc-noteref' ||
                    hasAny(tokens(anchor.getAttribute('class')), referenceClasses) ||
                    isSemanticNoteTarget(fragmentTarget(anchor));
            };
            /**
             * Publishers such as Duokan remove the fragment href while loading, which also stops
             * the reader from resolving the popup. A reference keeps its fragment in the reader's
             * own attribute, which is also how a book that only points at a note target is paired.
             */
            const noteTargetSelector =
                'aside, [role="doc-footnote"], [role="doc-endnote"], [' + noteContainerAttribute + ']';

            const restoreNoteHref = function() {
                const hints = Array.from(document.querySelectorAll('a'))
                    .filter(function(anchor) { return !anchor.getAttribute('href') && isReference(anchor); });
                if (hints.length === 0) return;
                const recorded = hints.filter(function(anchor) {
                    return !!fragmentId(anchor);
                });
                const targets = Array.from(document.querySelectorAll(noteTargetSelector))
                    .filter(function(target) { return !!target.id; });
                const paired = recorded.length !== hints.length ||
                    hints.length === targets.length;
                hints.forEach(function(anchor, index) {
                    const id = fragmentId(anchor) ||
                        (paired && targets[index] ? targets[index].id : null);
                    if (!id || !document.getElementById(id)) return;
                    anchor.setAttribute(fragmentAttribute, '#' + id);
                    anchor.setAttribute('href', '#' + id);
                    updateReferenceStyle(anchor);
                    markNoteTarget(document.getElementById(id));
                });
            };

            const detect = function() {
                marked = 0;
                Array.from(document.querySelectorAll('a')).forEach(function(anchor) {
                    const id = fragmentId(anchor);
                    const target = id ? document.getElementById(id) : null;
                    if (!isReference(anchor)) {
                        anchor.removeAttribute(referenceAttribute);
                        anchor.removeAttribute(graphicAttribute);
                        return;
                    }
                    if (id) anchor.setAttribute(fragmentAttribute, '#' + id);
                    if (!anchor.getAttribute('href')) return;
                    if (!epubTypes(anchor).includes('noteref')) {
                        addEpubType(anchor, 'noteref');
                        marked += 1;
                    }
                    updateReferenceStyle(anchor);
                    markNoteTarget(target);
                });
            };
            detect();

            // The publisher script strips the fragment href only after the document has loaded, so
            // the note wrappers are resolved a second time once the load event has settled.
            const remeasureNoteContainers = function() {
                const marked = containerHandled.length;
                restoreNoteHref();
                detect();
                if (marked === 0 && containerHandled.length === 0) return;
                applyNoteContainers();
            };
            if (document.readyState === 'complete') {
                remeasureNoteContainers();
            } else {
                window.addEventListener('load', remeasureNoteContainers, { once: true });
            }

            applyNoteContainers();
            root.setAttribute('data-koharia-footnotes-prepared', 'true');
            return marked;
        })()
    """.trimIndent()
    return script
}

/** Attributes the note bodies hidden from the flowing text so the popup is their only surface. */
internal const val EPUB_NOTE_CONTAINER_ATTRIBUTE = "data-koharia-note-container"
internal const val EPUB_NOTE_CONTAINER_STYLE_ID = "koharia-footnote-note-style"
internal const val EPUB_NOTE_CONTAINER_CSS =
    "[$EPUB_NOTE_CONTAINER_ATTRIBUTE] { display: none !important; }"

/** Keeps the note fragment of a reference whose `href` the publisher removes while loading. */
internal const val EPUB_NOTEREF_FRAGMENT_ATTRIBUTE = "data-koharia-noteref-fragment"

internal const val EPUB_NOTE_PREPAINT_STYLE_ID = "koharia-footnote-prepaint"

/**
 * Attributes that mark an element as a note body in the document itself. Duokan and Zhangyue books
 * decorate them with a border, so they must be hidden before the first paint.
 */
private val EPUB_NOTE_TARGET_SELECTOR = listOf(
    "aside[epub|type~=\"footnote\"]",
    "aside[epub|type~=\"endnote\"]",
    "aside[role=\"doc-footnote\"]",
    "aside[role=\"doc-endnote\"]",
    "[epub|type~=\"footnote\"][class*=\"footnote\"]",
    "[epub|type~=\"endnote\"][class*=\"endnote\"]",
    "[class~=\"duokan-footnote-item\"]",
    "[class~=\"footnote-item\"]",
    "[class~=\"endnote-item\"]",
).joinToString(",\n")

/**
 * Hides the note bodies of the resource in the document itself, before the reader scripts run.
 *
 * The draw-pass preparation only lands after the resource is painted, so a note body would flash in
 * the flowing text first. Readium adds this stylesheet to the resource before it is displayed, so
 * the note body never renders; the reader scripts still resolve it for the popup.
 */
internal fun String.injectEpubNotePrepaintStyle(): String {
    if ('<' !in this || contains("id=\"$EPUB_NOTE_PREPAINT_STYLE_ID\"", ignoreCase = true)) return this

    val style = """
        <style id="$EPUB_NOTE_PREPAINT_STYLE_ID" type="text/css">
        @namespace epub url("http://www.idpf.org/2007/ops");
        $EPUB_NOTE_TARGET_SELECTOR {
            display: none !important;
        }
        </style>
    """.trimIndent()
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
