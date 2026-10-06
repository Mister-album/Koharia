package koharia.epub

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubNotePrepaintDeviceTest {
    @Test
    fun xhtmlNotesAreHiddenBeforeReaderScriptsAfterChapterChanges() = verifyPrepaint(xhtml = true)

    @Test
    fun htmlRoleAndClassNotesAreHiddenWithoutHidingBodyOrReferences() = verifyPrepaint(xhtml = false)

    @SuppressLint("SetJavaScriptEnabled")
    private fun verifyPrepaint(xhtml: Boolean) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            var loaded = CountDownLatch(1)
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            loaded.countDown()
                        }
                    }
                }
                activity.setContentView(webView)
            }
            fun evaluate(script: String): String? {
                val finished = CountDownLatch(1)
                var result: String? = null
                scenario.onActivity {
                    webView.evaluateJavascript(script) {
                        result = it
                        finished.countDown()
                    }
                }
                assertTrue("JavaScript callback timed out", finished.await(5, TimeUnit.SECONDS))
                return result
            }
            fun assertSnapshot(name: String) {
                val encoded = checkNotNull(evaluate("JSON.stringify(window.$name)"))
                val snapshot = JSONObject(JSONTokener(encoded).nextValue() as String)
                assertTrue("Note bodies were visible: $snapshot", snapshot.getBoolean("notesHidden"))
                assertTrue("Running text was hidden: $snapshot", snapshot.getBoolean("bodyVisible"))
                assertTrue("Reference was hidden: $snapshot", snapshot.getBoolean("referenceVisible"))
                assertTrue("Ordinary aside was hidden: $snapshot", snapshot.getBoolean("ordinaryAsideVisible"))
                assertTrue("Hidden note content was lost: $snapshot", snapshot.getBoolean("noteContentPreserved"))
                assertTrue("No hiding rule parsed: $snapshot", snapshot.getInt("styleRuleCount") > 0)
                assertFalse("Post-load compatibility ran during the prepaint test", snapshot.getBoolean("prepared"))
            }
            try {
                // First open, another chapter, then return to the original chapter.
                for (chapter in listOf(0, 1, 0)) {
                    loaded = CountDownLatch(1)
                    scenario.onActivity {
                        webView.loadDataWithBaseURL(
                            "https://fixture.invalid/book/chapter$chapter.xhtml",
                            chapterFixture(xhtml).injectEpubNotePrepaintStyle(),
                            if (xhtml) "application/xhtml+xml" else "text/html",
                            "UTF-8",
                            null,
                        )
                    }
                    assertTrue("Chapter load timed out", loaded.await(10, TimeUnit.SECONDS))
                    assertEquals(
                        if (xhtml) "\"application/xhtml+xml\"" else "\"text/html\"",
                        evaluate("document.contentType"),
                    )
                    assertSnapshot("fixtureAtParse")
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    while (evaluate("!!window.fixtureFirstFrame") != "true") {
                        assertTrue("First frame timed out", SystemClock.uptimeMillis() < deadline)
                        SystemClock.sleep(50)
                    }
                    assertSnapshot("fixtureFirstFrame")
                }
            } finally {
                scenario.onActivity {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
    }

    private fun chapterFixture(xhtml: Boolean): String {
        val semanticNotes = if (xhtml) {
            """
                <aside epub:type="footnote" id="note" data-fixture-note="true">Popup note</aside>
                <aside epub:type="endnote" data-fixture-note="true">Endnote</aside>
                <aside pub:type="footnote" data-fixture-note="true">Alternate XML prefix</aside>
                <div epub:type="footnote" class="publisher-footnote" data-fixture-note="true">Publisher note</div>
                <div epub:type="endnote" class="publisher-endnote" data-fixture-note="true">Publisher endnote</div>
            """.trimIndent()
        } else {
            """<aside role="doc-footnote" id="note" data-fixture-note="true">Popup note</aside>"""
        }
        return """
            <html xmlns="http://www.w3.org/1999/xhtml"
                xmlns:epub="http://www.idpf.org/2007/ops" xmlns:pub="http://www.idpf.org/2007/ops">
            <head><title>Note fixture</title>
            <style>aside, [data-fixture-note] { display: block !important; }</style>
            </head><body>
            <note id="wrapper"><p id="body">Running text
                <a id="reference" class="duokan-footnote" epub:type="noteref" href="#note">[1]</a>
            </p>$semanticNotes</note>
            <aside role="doc-endnote" data-fixture-note="true">Role endnote</aside>
            <div class="duokan-footnote-item" data-fixture-note="true">Duokan note</div>
            <div class="footnote-item" data-fixture-note="true">Class footnote</div>
            <div class="endnote-item" data-fixture-note="true">Class endnote</div>
            <aside id="ordinary">Ordinary sidebar</aside>
            <script>
                function fixtureSnapshot() {
                    function visible(id) {
                        return document.getElementById(id).getClientRects().length !== 0;
                    }
                    return {
                        notesHidden: Array.from(document.querySelectorAll('[data-fixture-note]')).every(function(note) {
                            return getComputedStyle(note).display === 'none';
                        }),
                        bodyVisible: visible('body'),
                        referenceVisible: visible('reference'),
                        ordinaryAsideVisible: visible('ordinary'),
                        noteContentPreserved: document.getElementById('note').textContent === 'Popup note',
                        styleRuleCount: Array.from(document.getElementById('$EPUB_NOTE_PREPAINT_STYLE_ID').sheet.cssRules)
                            .filter(function(rule) { return rule.type === 1; }).length,
                        prepared: document.documentElement.hasAttribute('data-koharia-footnotes-prepared')
                    };
                }
                window.fixtureAtParse = fixtureSnapshot();
                requestAnimationFrame(function() { window.fixtureFirstFrame = fixtureSnapshot(); });
            </script>
            </body></html>
        """.trimIndent()
    }
}
