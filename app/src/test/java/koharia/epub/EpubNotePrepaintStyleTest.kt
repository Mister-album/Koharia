package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pre-paint stylesheet that keeps a note body from flashing in the flowing text. */
class EpubNotePrepaintStyleTest {

    private val chapter = """
        <?xml version='1.0' encoding='utf-8'?>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>电子书特典</title><link rel="stylesheet" href="../stylesheet.css"/></head>
        <body>
        <note class="calibre27"><p>…《东京音头》<sup><a class="duokan-footnote" epub:type="noteref"
        href="#note001" id="note_ref001"><img src="../Images/note.webp"/></a></sup>…</p>
        <aside epub:type="footnote" id="note001" class="pcalibre1 calibre29">注：《东京音头》…</aside></note>
        <p>虽然摊位不多…</p>
        </body></html>
    """.trimIndent()

    @Test
    fun `injects the stylesheet before the closing head`() {
        val injected = chapter.injectEpubNotePrepaintStyle()

        assertTrue(injected.contains("id=\"$EPUB_NOTE_PREPAINT_STYLE_ID\""))
        assertTrue(injected.contains("display: none !important;"))
        assertTrue(injected.contains("aside[epub|type~=\"footnote\"]"))
        assertTrue(injected.contains("href=\"#note001\""))
        assertTrue(injected.indexOf(EPUB_NOTE_PREPAINT_STYLE_ID) < injected.indexOf("</head>"))
        assertTrue(injected.contains("<title>电子书特典</title>"))
        assertTrue(injected.endsWith("</html>"))
    }

    @Test
    fun `injection is idempotent`() {
        val once = chapter.injectEpubNotePrepaintStyle()
        val twice = once.injectEpubNotePrepaintStyle()

        assertEquals(once, twice)
        assertEquals(1, Regex(EPUB_NOTE_PREPAINT_STYLE_ID).findAll(twice).count())
    }

    @Test
    fun `leaves resources without markup untouched`() {
        assertEquals("", "".injectEpubNotePrepaintStyle())
        assertEquals("not markup", "not markup".injectEpubNotePrepaintStyle())
    }

    @Test
    fun `falls back to the body start when there is no head`() {
        val injected = "<html><body><p>x</p></body></html>".injectEpubNotePrepaintStyle()

        assertTrue(injected.contains(EPUB_NOTE_PREPAINT_STYLE_ID))
        assertTrue(injected.indexOf(EPUB_NOTE_PREPAINT_STYLE_ID) < injected.indexOf("<body>"))
    }

    @Test
    fun `stylesheet declares its own epub namespace before using it`() {
        val injected = chapter.injectEpubNotePrepaintStyle()
        val css = injected.substringAfter("type=\"text/css\">").substringBefore("</style>")

        assertFalse(css.contains("epub:type"))
        assertTrue(css.contains("epub|type"))
        assertTrue(css.trimStart().startsWith("@namespace epub url(\"http://www.idpf.org/2007/ops\");"))
        assertFalse(css.contains(":has("))
    }
}
