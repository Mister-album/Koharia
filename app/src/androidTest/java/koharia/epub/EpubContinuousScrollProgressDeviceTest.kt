package koharia.epub

import android.annotation.SuppressLint
import android.os.SystemClock
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EpubContinuousScrollProgressDeviceTest {
    @Test
    fun portraitReentryPreservesProgressWithDelayedAdjacentChapters() = verifyReentry(800, 1100)

    @Test
    fun landscapeReentryPreservesProgressWithDelayedAdjacentChapters() = verifyReentry(1280, 650)

    @SuppressLint("SetJavaScriptEnabled")
    private fun verifyReentry(width: Int, height: Int) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
            var webView: WebView? = null
            fun evaluate(script: String): String {
                val latch = CountDownLatch(1)
                var result = "null"
                scenario.onActivity {
                    checkNotNull(webView).evaluateJavascript(script) {
                        result = it
                        latch.countDown()
                    }
                }
                assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS))
                return result
            }
            fun awaitCondition(script: String) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (evaluate(script) != "true") {
                    assertTrue("Condition timed out: $script", SystemClock.uptimeMillis() < deadline)
                    SystemClock.sleep(50)
                }
            }
            val resources = (0 until 15).map {
                EpubContinuousScrollResource(it, "$it.xhtml", "https://fixture.invalid/$it.xhtml")
            }
            var savedProgression = 0.4
            try {
                repeat(3) { reopen ->
                    scenario.onActivity { activity ->
                        webView?.destroy()
                        webView = WebView(activity).also {
                            it.settings.javaScriptEnabled = true
                            activity.setContentView(it, FrameLayout.LayoutParams(width, height))
                            it.loadDataWithBaseURL(
                                resources[3].url,
                                """
                                <!doctype html><html><head>
                                <meta name="viewport" content="width=device-width,initial-scale=1"/>
                                <style>body{margin:0}p{height:100px;margin:0}</style>
                                </head><body>${(1..40).joinToString("") { "<p>Owner paragraph $it</p>" }}</body></html>
                                """.trimIndent(),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        }
                    }
                    awaitCondition(
                        "document.readyState === 'complete' && innerHeight > 0 && " +
                            "document.querySelectorAll('p').length === 40",
                    )
                    evaluate(
                        """
                        window.fixtureLocations = [];
                        window.KohariaContinuousScroll = {
                            onLocationChanged: function(resourceIndex, progression) {
                                fixtureLocations.push({resourceIndex: resourceIndex, progression: progression});
                            }
                        };
                        window.fetch = function() {
                            return new Promise(function(resolve) {
                                setTimeout(function() {
                                    resolve({ok: true, text: function() {
                                        return Promise.resolve('<html><body style="margin:0"><div style="height:9000px">Adjacent chapter</div></body></html>');
                                    }});
                                }, 250);
                            });
                        };
                        window.scrollTo(0, $savedProgression * document.scrollingElement.scrollHeight);
                        """.trimIndent(),
                    )
                    assertEquals(
                        "\"installed\"",
                        evaluate(buildEpubContinuousScrollInstallScript(resources, 3, savedProgression, "", "")),
                    )
                    awaitCondition(
                        "Array.from(document.querySelectorAll('iframe')).length === 2 && " +
                            "Array.from(document.querySelectorAll('iframe')).every(f => parseFloat(f.style.height) >= 9000)",
                    )
                    SystemClock.sleep(350)
                    val location = JSONObject(evaluate("window.__kohariaContinuousScroll.currentLocation()"))
                    assertEquals("Reopen $reopen moved to another resource", 3, location.getInt("resourceIndex"))
                    assertEquals(savedProgression, location.getDouble("progression"), 0.001)
                    assertEquals(
                        "Restoration must use Readium's full resource height",
                        savedProgression * 4000,
                        evaluate(
                            "-document.getElementById('koharia-continuous-current-start').getBoundingClientRect().top",
                        ).toDouble(),
                        2.0,
                    )

                    // The last scroll happens within the throttle window, with no subsequent gesture.
                    val snapshot = JSONObject(
                        evaluate(
                            "window.__kohariaContinuousScroll.notifyLocation(true); window.scrollBy(0, 60); " +
                                "window.__kohariaContinuousScroll.currentLocation()",
                        ),
                    )
                    savedProgression += 60.0 / 4000
                    assertEquals(savedProgression, snapshot.getDouble("progression"), 0.001)
                    SystemClock.sleep(300)
                    val notified = JSONObject(evaluate("fixtureLocations[fixtureLocations.length - 1]"))
                    assertEquals(3, notified.getInt("resourceIndex"))
                    assertEquals(
                        "Final scroll notification was lost",
                        savedProgression,
                        notified.getDouble("progression"),
                        0.001,
                    )
                }
            } finally {
                scenario.onActivity { webView?.destroy() }
            }
        }
    }
}
