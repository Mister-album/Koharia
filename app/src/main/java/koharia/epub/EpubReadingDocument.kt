package koharia.epub

import kotlinx.serialization.json.JsonPrimitive

/** Both Readium's fallback and WebView's XML error document complete loading. */
internal fun epubReadingDocumentStateScript(servedHref: String): String = """
    (function() {
        const expected = new URL(${JsonPrimitive(servedHref)}, document.baseURI);
        const actual = new URL(window.location.href);
        expected.hash = '';
        actual.hash = '';
        if (actual.href !== expected.href || document.readyState === 'loading') return 'pending';
        const root = document.documentElement;
        if (!root) return 'pending';
        const parserError = Array.from(document.getElementsByTagNameNS('*', 'parsererror')).some(function(node) {
            return node.namespaceURI === 'http://www.w3.org/1999/xhtml' ||
                node.namespaceURI === 'http://www.mozilla.org/newlayout/xml/parsererror.xml';
        });
        return root.hasAttribute('data-koharia-load-error') || parserError ? 'failed' : 'ready';
    })();
""".trimIndent()
