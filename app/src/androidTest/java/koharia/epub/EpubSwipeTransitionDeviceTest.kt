package koharia.epub

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
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
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EpubSwipeTransitionDeviceTest {
    @Test
    fun everyEffectIsUsedByForwardAndBackwardSwipes() = withReader(false) { scenario, fragment, preferences ->
        for (effect in PageTransitionEffect.entries) {
            preferences.pageTransitionEffect.set(effect.value)
            for (forward in listOf(true, false)) {
                val before = location(fragment)
                scenario.onActivity { swipe(fragment.requireView(), forward, false) }
                assertAnimation(scenario, fragment, effect)
                await(scenario, "Exactly one $effect page turn") {
                    val after = location(fragment)
                    after.first == before.first && after.second == before.second + (if (forward) 1 else -1) &&
                        (field(fragment, "pageTransitionOverlay") as View).visibility == View.GONE
                }
                SystemClock.sleep(100)
                scenario.onActivity {
                    assertEquals(before.second + (if (forward) 1 else -1), location(fragment).second)
                }
            }
        }
    }

    @Test
    fun rightToLeftSwipesUseTheSameEffects() = withReader(true) { scenario, fragment, preferences ->
        for (effect in listOf(PageTransitionEffect.CURL, PageTransitionEffect.NONE)) {
            preferences.pageTransitionEffect.set(effect.value)
            val before = location(fragment)
            scenario.onActivity { swipe(fragment.requireView(), true, true) }
            assertAnimation(scenario, fragment, effect)
            await(scenario, "RTL forward $effect") {
                location(fragment).second == before.second + 1 &&
                    (field(fragment, "pageTransitionOverlay") as View).visibility == View.GONE
            }
            scenario.onActivity { swipe(fragment.requireView(), false, true) }
            await(scenario, "RTL backward $effect") {
                location(fragment) == before &&
                    (field(fragment, "pageTransitionOverlay") as View).visibility == View.GONE
            }
        }
    }

    @Test
    fun chapterBoundarySwipesUseSelectedAnimationInBothDirections() = withReader(false) {
            scenario,
            fragment,
            preferences,
        ->
        preferences.pageTransitionEffect.set(PageTransitionEffect.NONE.value)
        val pageCount = field(fragment, "currentTransitionPageCount") as Int
        for (page in 1 until pageCount) {
            scenario.onActivity { assertTrue(fragment.goForward()) }
            await(scenario, "Prepare chapter page $page") { location(fragment).second == page }
        }
        val before = location(fragment)
        preferences.pageTransitionEffect.set(PageTransitionEffect.CURL.value)
        scenario.onActivity { swipe(fragment.requireView(), true, false) }
        assertAnimation(scenario, fragment, PageTransitionEffect.CURL)
        await(scenario, "Next resource") {
            location(fragment).first != before.first && location(fragment).second == 0 &&
                (field(fragment, "pageTransitionOverlay") as View).visibility == View.GONE
        }
        scenario.onActivity { swipe(fragment.requireView(), false, false) }
        assertAnimation(scenario, fragment, PageTransitionEffect.CURL)
        await(scenario, "Previous resource") {
            location(fragment) == before && (field(fragment, "pageTransitionOverlay") as View).visibility == View.GONE
        }
    }

    private fun assertAnimation(
        scenario: ActivityScenario<EpubTransitionFixtureActivity>,
        fragment: EpubReaderFragment,
        effect: PageTransitionEffect,
    ) {
        if (effect == PageTransitionEffect.NONE) {
            scenario.onActivity {
                assertEquals(View.GONE, (field(fragment, "pageTransitionOverlay") as View).visibility)
            }
            return
        }
        await(scenario, "Visible $effect animation") {
            val overlay = field(fragment, "pageTransitionOverlay") as EpubPageTransitionOverlayView
            val controller = field(fragment, "pageTransitionController")!!
            val active = field(controller, "active") ?: return@await false
            if (overlay.visibility != View.VISIBLE || overlay.progress !in 0.05f..0.95f) return@await false
            assertEquals(effect, field(active, "effect"))
            assertEquals(PageTurnCause.GESTURE, (field(active, "origin") as PageTurnOrigin).cause)
            val content = field(controller, "content") as View
            when (effect) {
                PageTransitionEffect.SLIDE -> abs(content.translationX) > 1f && abs(overlay.translationX) > 1f
                PageTransitionEffect.COVER -> abs(content.translationX) > 1f && overlay.translationX == 0f
                PageTransitionEffect.CURL -> field(overlay, "effect") == PageTransitionEffect.CURL
                PageTransitionEffect.VERTICAL -> abs(content.translationY) > 1f && abs(overlay.translationY) > 1f
                PageTransitionEffect.FADE -> overlay.alpha < 1f
                PageTransitionEffect.DEPTH -> overlay.scaleX < 1f
                PageTransitionEffect.NONE -> false
            }
        }
    }

    private fun location(fragment: EpubReaderFragment): Pair<String, Int> {
        val index = field(fragment, "currentTransitionPageIndex") as Int
        val count = field(fragment, "currentTransitionPageCount") as Int
        val preferences = Injekt.get<EpubLayoutPreferences>()
        val logicalIndex = if (preferences.pageDirection.get() == EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT) {
            count - 1 - index
        } else {
            index
        }
        return field(fragment, "currentTransitionHref").toString() to logicalIndex
    }

    private fun await(scenario: ActivityScenario<EpubTransitionFixtureActivity>, name: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = check() }
            if (ready) return
            SystemClock.sleep(15)
        }
        error("Timed out: $name")
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun swipe(view: View, forward: Boolean, rightToLeft: Boolean) {
        val towardLeft = forward != rightToLeft
        val now = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val fraction = if (towardLeft) 0.8f - step * 0.05f else 0.2f + step * 0.05f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(now, now + step * 20L, action, view.width * fraction, view.height * 0.6f, 0)
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun withReader(
        rightToLeft: Boolean,
        test: (ActivityScenario<EpubTransitionFixtureActivity>, EpubReaderFragment, EpubLayoutPreferences) -> Unit,
    ) = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val preferences = Injekt.get<EpubLayoutPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> override(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        override(preferences.readingMode, EpubLayoutPreferences.ReadingMode.PAGINATED)
        override(
            preferences.pageDirection,
            if (rightToLeft) {
                EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT
            } else {
                EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT
            },
        )
        override(preferences.pageTransitionEffect, PageTransitionEffect.SLIDE.value)
        override(Injekt.get<EInkPreferences>().enabled, false)
        val book = File.createTempFile("swipe-transition-fixture-", ".epub", context.cacheDir)
        val chapterId = -SystemClock.elapsedRealtimeNanos()
        val sessions = Injekt.get<EpubReaderSessionRepository>()
        try {
            writeBook(book)
            sessions.put(
                LocalEpubPublicationService().open(
                    EpubOpenRequest(
                        0,
                        chapterId,
                        -1,
                        "Swipe fixture",
                        null,
                        book.toURI().toString(),
                        EpubOpenRequest.OpenSource.LOCAL,
                        publicationKey = "fixture:$chapterId",
                    ),
                    null,
                ),
            )
            ActivityScenario.launch(EpubTransitionFixtureActivity::class.java).use { scenario ->
                lateinit var fragment: EpubReaderFragment
                scenario.onActivity { activity ->
                    fragment = EpubReaderFragment.newInstance(chapterId, -1)
                    activity.supportFragmentManager.beginTransaction().replace(
                        android.R.id.content,
                        fragment,
                    ).commitNow()
                }
                await(scenario, "Book pagination ready") {
                    (field(fragment, "currentTransitionPageCount") as Int) > 1 && location(fragment).second == 0
                }
                test(scenario, fragment, preferences)
            }
        } finally {
            sessions.remove(chapterId)
            restores.asReversed().forEach { it() }
            book.delete()
        }
    }

    private fun writeBook(file: File) {
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray()
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
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
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">swipe-fixture</dc:identifier>
                <dc:title>Swipe fixture</dc:title><dc:language>en</dc:language>
                <meta property="dcterms:modified">2026-10-09T00:00:00Z</meta></metadata>
                <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>
                """.trimIndent(),
            )
            entry(
                "OEBPS/nav.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>Contents</title></head><body><nav epub:type="toc"><ol>
                <li><a href="a.xhtml">A</a></li><li><a href="b.xhtml">B</a></li></ol></nav></body></html>
                """.trimIndent(),
            )
            for (name in listOf("a", "b")) {
                val paragraphs = (1..90).joinToString("") {
                    "<p>Reading fixture paragraph $it for page turn verification.</p>"
                }
                entry(
                    "OEBPS/$name.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$name</title></head><body><h1>$name</h1>$paragraphs</body></html>
                    """.trimIndent(),
                )
            }
        }
    }
}
