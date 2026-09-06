package koharia.epub

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubTocNavigationDeviceTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(EInkMotionFixtureActivity::class.java)

    @Test
    fun distinguishesAnchorsInTheSameResourceAfterPaging() {
        lateinit var webView: WebView
        val loaded = CountDownLatch(1)
        activityRule.scenario.onActivity { activity ->
            webView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
            }
            activity.setContentView(webView)
            webView.loadDataWithBaseURL(
                "https://example.invalid/book/",
                """
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <body style="margin:0;width:300vw;height:100vh">
                    <h1 id="first" style="position:absolute;left:10px;top:10px">Chapter</h1>
                    <h1 id="last section" style="position:absolute;left:210vw;top:10px">License</h1>
                    </body>
                """.trimIndent(),
                "text/html",
                "UTF-8",
                null,
            )
        }
        assertTrue(loaded.await(10, TimeUnit.SECONDS))
        val script = currentEpubTocIndexScript(listOf("first", "last%20section", "missing"))
        fun evaluate(code: String): String? {
            val finished = CountDownLatch(1)
            var result: String? = null
            activityRule.scenario.onActivity {
                webView.evaluateJavascript(code) {
                    result = it
                    finished.countDown()
                }
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            return result
        }
        assertEquals("0", evaluate(script))
        // Shift the resource relative to the viewport, as a paginated navigator does.
        evaluate("document.body.style.transform = 'translateX(-200vw)'")
        assertEquals("1", evaluate(script))
        evaluate("document.body.style.transform = 'none'")
        assertEquals("0", evaluate(script))
        activityRule.scenario.onActivity { webView.destroy() }
    }
}
