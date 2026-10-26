package koharia.epub

import kotlinx.serialization.json.JsonPrimitive

/** Readium's fallback document completes loading too; only its explicit marker means failure. */
internal fun epubReadingDocumentStateScript(servedHref: String): String = """
    (function() {
        const expected = new URL(${JsonPrimitive(servedHref)}, document.baseURI);
        const actual = new URL(window.location.href);
        expected.hash = '';
        actual.hash = '';
        if (actual.href !== expected.href || document.readyState === 'loading') return 'pending';
        return document.documentElement.hasAttribute('data-koharia-load-error') ? 'failed' : 'ready';
    })();
""".trimIndent()
