package koharia.epub

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.FixtureActivityLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EpubReadingDocumentDeviceTest {
    @Test
    @SuppressLint("SetJavaScriptEnabled")
    fun sameResourceRecoversAfterErrorAndWebViewRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val fallback = context.assets.open("readium/error.xhtml").bufferedReader().use { it.readText() }
        assertTrue("Readium CSS injection requires a head", fallback.contains("<head>"))
        FixtureActivityLauncher.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            var loaded = CountDownLatch(1)
            fun createWebView() {
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
                assertTrue("JavaScript timed out", finished.await(5, TimeUnit.SECONDS))
                return result
            }
            val href = "https://fixture.invalid/book/chapter.xhtml"
            fun load(document: String) {
                loaded = CountDownLatch(1)
                scenario.onActivity {
                    webView.loadDataWithBaseURL(href, document, "application/xhtml+xml", "UTF-8", null)
                }
                assertTrue("Document timed out", loaded.await(10, TimeUnit.SECONDS))
            }
            createWebView()
            try {
                load(fallback)
                assertEquals("\"failed\"", evaluate(epubReadingDocumentStateScript(href)))
                assertEquals("\"pending\"", evaluate(epubReadingDocumentStateScript("$href?other=1")))
                val success = """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Fixture</title></head>
                    <body><p>Recovered chapter</p></body></html>
                """.trimIndent()
                load(success)
                assertEquals("\"ready\"", evaluate(epubReadingDocumentStateScript("$href#paragraph")))
                // Readium can invalidate its pager while retaining the outer reader fragment.
                scenario.onActivity { webView.destroy() }
                createWebView()
                load(success)
                assertEquals("\"ready\"", evaluate(epubReadingDocumentStateScript(href)))
                load(fallback)
                assertEquals("\"failed\"", evaluate(epubReadingDocumentStateScript(href)))
            } finally {
                scenario.onActivity {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
    }
}
