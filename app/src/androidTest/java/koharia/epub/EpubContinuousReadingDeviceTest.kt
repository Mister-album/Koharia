package koharia.epub

import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.epub.model.EpubOpenRequest
import koharia.epub.service.LocalEpubPublicationService
import koharia.epub.session.EpubReaderSessionRepository
import koharia.epub.settings.EpubLayoutPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubContinuousReadingDeviceTest {
    @Test
    fun shortOpeningChapterBecomesScrollableAndReinstallsAfterNavigation() = verifyReading(false)

    @Test
    fun embeddedFootnotesResolveInTheirOwnChapter() = verifyReading(true)

    @Test
    fun backgroundProgressCaptureEvaluatesLoadedReadiumWebViewOnMain() = verifyReading(false, captureOnly = true)

    private fun verifyReading(footnotesOnly: Boolean, captureOnly: Boolean = false): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val preferences = Injekt.get<EpubLayoutPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> override(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        override(preferences.readingMode, EpubLayoutPreferences.ReadingMode.SCROLL)
        override(preferences.publisherStyles, true)
        val book = File.createTempFile("continuous-fixture-", ".epub", context.cacheDir)
        val chapterId = -SystemClock.elapsedRealtimeNanos()
        val sessions = Injekt.get<EpubReaderSessionRepository>()
        try {
            writeBook(book, footnotesOnly)
            val session = LocalEpubPublicationService().open(
                EpubOpenRequest(
                    0,
                    chapterId,
                    -1,
                    "Continuous fixture",
                    null,
                    book.toURI().toString(),
                    EpubOpenRequest.OpenSource.LOCAL,
                ),
                null,
            )
            sessions.put(session)
            ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                lateinit var fragment: EpubReaderFragment
                scenario.onActivity { activity ->
                    fragment = EpubReaderFragment.newInstance(chapterId, -1)
                    activity.supportFragmentManager.beginTransaction().replace(
                        android.R.id.content,
                        fragment,
                    ).commitNow()
                }
                fun visibleWebView(): WebView? {
                    var view: WebView? = null
                    scenario.onActivity { activity ->
                        view = descendants(activity.window.decorView).filterIsInstance<WebView>().firstOrNull {
                            val rect = Rect()
                            it.isShown && it.getGlobalVisibleRect(rect) && rect.width() > it.width / 2 &&
                                rect.height() > it.height / 2
                        }
                    }
                    return view
                }
                fun evaluate(script: String): String? {
                    val view = visibleWebView() ?: return null
                    val ready = CountDownLatch(1)
                    var value: String? = null
                    scenario.onActivity {
                        view.evaluateJavascript(script) {
                            value = it
                            ready.countDown()
                        }
                    }
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    return value
                }
                fun awaitInstalled(expectedIndex: Int? = null) {
                    val deadline = SystemClock.uptimeMillis() + 20000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val condition = "!!window.__kohariaContinuousScroll && !!window.__kohariaImageWarmup && " +
                            "document.scrollingElement.scrollHeight > innerHeight * 2" +
                            (expectedIndex?.let { " && window.__kohariaContinuousScroll.currentIndex === $it" } ?: "")
                        if (evaluate(condition) == "true") return
                        SystemClock.sleep(100)
                    }
                    throw AssertionError("Short chapter did not get continuous content")
                }
                awaitInstalled(0)
                if (captureOnly) {
                    val captured = AtomicReference<Locator>()
                    scenario.onActivity {
                        val host = Proxy.newProxyInstance(
                            EpubReaderFragment.Host::class.java.classLoader,
                            arrayOf(EpubReaderFragment.Host::class.java),
                        ) { _, method, arguments ->
                            if (method.name == "onLocatorChanged") {
                                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                                captured.set(arguments[0] as Locator)
                            }
                            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                        }
                        EpubReaderFragment::class.java.getDeclaredField("host")
                            .apply { isAccessible = true }.set(fragment, host)
                    }
                    for (progression in listOf(0.42, 0.57)) {
                        // Freeze the snapshot to distinguish explicit capture from scroll bridge notifications.
                        evaluate(
                            "window.__kohariaContinuousScroll.currentLocation = function() { " +
                                "return {resourceIndex: 2, progression: $progression}; };",
                        )
                        captured.set(null)
                        assertTrue(Looper.myLooper() != Looper.getMainLooper())
                        fragment.captureContinuousScrollProgress()
                        val locator = checkNotNull(captured.get()) { "Background capture did not deliver a locator" }
                        assertEquals(session.publication.readingOrder[2].href.toString(), locator.href.toString())
                        assertEquals(progression, checkNotNull(locator.locations.progression), 0.000001)
                    }
                    return@use
                }
                val wrongDocument = buildEpubContinuousScrollInstallScript(
                    listOf(EpubContinuousScrollResource(0, "wrong.xhtml", "https://example.invalid/wrong.xhtml")),
                    0,
                    0.0,
                    "",
                    "",
                )
                assertEquals("\"different-resource\"", evaluate(wrongDocument))
                assertEquals("4", evaluate("window.__kohariaContinuousScroll.resources.length"))
                assertEquals("true", evaluate("!!window.__kohariaImageWarmup"))
                val frameReadyDeadline = SystemClock.uptimeMillis() + 15000
                while (evaluate("!!document.querySelector('iframe')?.contentDocument?.getElementById('title-text')") !=
                    "true"
                ) {
                    assertTrue("Embedded title did not load", SystemClock.uptimeMillis() < frameReadyDeadline)
                    SystemClock.sleep(100)
                }
                assertEquals("true", evaluate("typeof window.readium === 'object'"))
                assertEquals(
                    "true",
                    evaluate("typeof document.querySelector('iframe').contentWindow.readium === 'undefined'"),
                )
                assertEquals(
                    "true",
                    evaluate("document.querySelector('iframe').contentWindow.fixtureAuthoredScript === true"),
                )
                evaluate(
                    "window.fixtureTitleFrame = document.querySelector('iframe'); window.scrollBy(0, fixtureTitleFrame.getBoundingClientRect().top - 100)",
                )
                SystemClock.sleep(1000)
                var titlePosition = evaluate("document.scrollingElement.scrollTop")!!.toDouble()
                var taps = 0
                var imageInteractions = 0
                val footnotes = java.util.concurrent.CopyOnWriteArrayList<String>()
                scenario.onActivity {
                    val host = Proxy.newProxyInstance(
                        EpubReaderFragment.Host::class.java.classLoader,
                        arrayOf(EpubReaderFragment.Host::class.java),
                    ) { _, method, arguments ->
                        when (method.name) {
                            "onTap" -> {
                                assertTrue((arguments[0] as Float) in 0f..1f)
                                assertTrue((arguments[1] as Float) in 0f..1f)
                                taps++
                                false
                            }
                            "swipePageTurnsEnabled" -> false
                            "onFootnoteActivated" -> {
                                footnotes += arguments[1] as String
                                null
                            }
                            "onImageInteraction" -> {
                                imageInteractions++
                                null
                            }
                            else -> null
                        }
                    }
                    EpubReaderFragment::class.java.getDeclaredField("host")
                        .apply { isAccessible = true }.set(fragment, host)
                }

                if (!footnotesOnly) {
                    for (selector in listOf("#title-text", "#title-blank", "#title-image")) {
                        evaluate(
                            """
                        (function() {
                            const frame = window.fixtureTitleFrame;
                            const rect = frame.contentDocument.querySelector('$selector').getBoundingClientRect();
                            window.scrollBy(0, frame.getBoundingClientRect().top + rect.top + rect.height / 2 - innerHeight / 2);
                        })()
                            """.trimIndent(),
                        )
                        SystemClock.sleep(400)
                        titlePosition = evaluate("document.scrollingElement.scrollTop")!!.toDouble()

                        val coordinate = evaluate(
                            """
                        (function() {
                            const frame = window.fixtureTitleFrame;
                            const rect = frame.contentDocument.querySelector('$selector').getBoundingClientRect();
                            return frame.getBoundingClientRect().top + rect.top + rect.height / 2;
                        })()
                            """.trimIndent(),
                        )!!.toFloat()
                        val cssViewportHeight = evaluate("innerHeight")!!.toFloat()
                        var tapX = 0f
                        var tapY = 0f
                        scenario.onActivity {
                            val webView = visibleWebView()!!
                            val location = IntArray(2)
                            webView.getLocationOnScreen(location)
                            tapX = location[0] + webView.width / 2f
                            tapY = location[1] + coordinate * webView.height / cssViewportHeight
                            assertTrue("Fixture lost window focus before $selector", webView.rootView.hasWindowFocus())
                            assertTrue(
                                "Click $selector outside viewport: $coordinate / $cssViewportHeight",
                                coordinate in 0f..cssViewportHeight,
                            )
                        }
                        val down = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tapX, tapY, 0)
                            instrumentation.sendPointerSync(event)
                            event.recycle()
                            SystemClock.sleep(80)
                        }
                        SystemClock.sleep(1000)
                        assertEquals(
                            "Tap moved the continuous document",
                            titlePosition,
                            evaluate("document.scrollingElement.scrollTop")!!.toDouble(),
                            3.0,
                        )
                        assertEquals("0", evaluate("window.__kohariaContinuousScroll.currentIndex"))
                        assertEquals("true", evaluate("window.fixtureTitleFrame === document.querySelector('iframe')"))
                    }
                    scenario.onActivity {
                        assertEquals("Text and blank taps must reach the reader once each", 2, taps)
                        assertEquals("Image tap must retain preview handling", 1, imageInteractions)
                    }
                }
                if (footnotesOnly) {
                    for ((reference, expected) in listOf(
                        "note-local" to "Local fixture note",
                        "note-cross" to "Cross chapter fixture note",
                        "note-compat" to "Local fixture note",
                        "note-graphic" to "Local fixture note",
                    )) {
                        val previousCount = footnotes.size
                        evaluate("window.fixtureTitleFrame.contentDocument.getElementById('$reference').click()")
                        val deadline = SystemClock.uptimeMillis() + 4000
                        while (footnotes.size == previousCount &&
                            SystemClock.uptimeMillis() < deadline
                        ) {
                            SystemClock.sleep(100)
                        }
                        assertEquals("Footnote $reference did not reach popup", previousCount + 1, footnotes.size)
                        assertTrue(footnotes.last().contains(expected))
                        assertEquals(titlePosition, evaluate("document.scrollingElement.scrollTop")!!.toDouble(), 3.0)
                        assertEquals("0", evaluate("window.__kohariaContinuousScroll.currentIndex"))
                    }
                    scenario.onActivity {
                        assertEquals("Footnote icons must not open image previews", 0, imageInteractions)
                    }
                }
                evaluate("window.fixtureTitleFrame.contentDocument.getElementById('title-link').click()")
                awaitInstalled(3)
                scenario.onActivity { assertTrue(fragment.goTo(session.publication.readingOrder.first())) }
                awaitInstalled(0)
                if (!footnotesOnly) {
                    var x = 0f
                    var startY = 0f
                    var endY = 0f
                    scenario.onActivity { activity ->
                        val content = activity.window.decorView
                        x = content.width / 2f
                        startY = content.height * 0.75f
                        endY = content.height * 0.3f
                    }
                    fun swipeUp() {
                        val down = SystemClock.uptimeMillis()
                        for (step in 0..20) {
                            val action = when (step) {
                                0 -> MotionEvent.ACTION_DOWN
                                20 -> MotionEvent.ACTION_UP
                                else -> MotionEvent.ACTION_MOVE
                            }
                            val event = MotionEvent.obtain(
                                down,
                                SystemClock.uptimeMillis(),
                                action,
                                x,
                                startY + (endY - startY) * step / 20f,
                                0,
                            )
                            instrumentation.sendPointerSync(event)
                            event.recycle()
                            SystemClock.sleep(20)
                        }
                        SystemClock.sleep(500)
                    }
                    swipeUp()
                    assertEquals("true", evaluate("document.scrollingElement.scrollTop > 0"))
                    scenario.onActivity { assertTrue(fragment.goTo(session.publication.readingOrder.first())) }
                    awaitInstalled(0)
                    // Recreate the observed failure: an unstitched, short document while installation is pending.
                    evaluate(
                        """
                    delete window.__kohariaContinuousScroll;
                    document.getElementById('koharia-continuous-scroll-style')?.remove();
                    document.body.innerHTML = '<p>Short credits</p>';
                    document.documentElement.style.height = 'auto';
                    document.body.style.height = 'auto';
                    window.scrollTo(0, 0);
                    Object.defineProperty(document, 'readyState', { configurable: true, get: () => 'loading' });
                        """.trimIndent(),
                    )
                    scenario.onActivity {
                        EpubReaderFragment::class.java.getDeclaredMethod("clearContinuousScrollState")
                            .apply { isAccessible = true }.invoke(fragment)
                    }
                    SystemClock.sleep(200)
                    assertEquals("true", evaluate("document.scrollingElement.scrollHeight <= innerHeight"))
                    swipeUp()
                    awaitInstalled(1)
                    scenario.onActivity { assertTrue(fragment.goTo(session.publication.tableOfContents.last())) }
                    awaitInstalled(3)
                    assertEquals("3", evaluate("window.__kohariaContinuousScroll.currentIndex"))
                    scenario.recreate()
                    awaitInstalled()
                }
            }
        } finally {
            sessions.remove(chapterId)
            restores.asReversed().forEach { it() }
            book.delete()
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (child in view.children) yieldAll(descendants(child))
    }

    private fun writeBook(file: File, includeFootnotes: Boolean) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
                val item = ZipEntry(name)
                if (name == "mimetype") {
                    item.method = ZipEntry.STORED
                    item.size = bytes.size.toLong()
                    item.compressedSize = bytes.size.toLong()
                    item.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(item)
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("mimetype", "application/epub+zip")
            entry(
                "META-INF/container.xml",
                """
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles><rootfile full-path="OEBPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
                """.trimIndent(),
            )
            entry(
                "OEBPS/book.opf",
                """
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">fixture</dc:identifier>
                <dc:title>Continuous fixture</dc:title><dc:language>en</dc:language>
                <meta property="dcterms:modified">2026-09-12T00:00:00Z</meta></metadata>
                <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="a" href="short.xhtml" media-type="application/xhtml+xml"/>
                <item id="title" href="title.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="long.xhtml" media-type="application/xhtml+xml"/>
                <item id="c" href="end.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="title"/><itemref idref="b"/><itemref idref="c"/></spine></package>
                """.trimIndent(),
            )
            entry(
                "OEBPS/nav.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Contents</title></head><body><nav epub:type="toc"><ol>
                <li><a href="short.xhtml">Credits</a></li><li><a href="long.xhtml">Chapter one</a></li>
                <li><a href="end.xhtml">Chapter two</a></li></ol></nav></body></html>
                """.trimIndent(),
            )
            val footnotes = """
                <a id="note-local" epub:type="noteref" href="#local-note">1</a>
                <a id="note-cross" epub:type="noteref" href="end.xhtml#cross-note">2</a>
                <a id="note-compat" class="duokan-footnote" href="#local-note">3</a>
                <a epub:type="noteref" href="#local-note"><img id="note-graphic" alt="4"
                src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="/></a>
                <aside id="local-note" epub:type="footnote" hidden="hidden">Local fixture note</aside>
            """.trimIndent()
            entry(
                "OEBPS/title.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Title</title>
                <script>window.fixtureAuthoredScript = true;</script></head><body>
                <h1 id="title-text">Title page</h1><div id="title-blank" style="height:150px"></div>
                <img id="title-image" alt="Fixture image" style="display:block;margin:auto;width:80px;height:80px"
                src="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="/>
                ${if (includeFootnotes) footnotes else ""}
                <a id="title-link" href="end.xhtml">Last chapter</a>
                </body></html>
                """.trimIndent(),
            )
            for ((name, count) in listOf("short" to 1, "long" to 180, "end" to 120)) {
                val paragraphs = (1..count).joinToString("") { "<p>Generated reading fixture paragraph $it.</p>" }
                entry(
                    "OEBPS/$name.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$name</title></head>
                    <body><h1>$name</h1>$paragraphs<aside id="cross-note" hidden="hidden">Cross chapter fixture note</aside></body></html>
                    """.trimIndent(),
                )
            }
        }
    }
}
