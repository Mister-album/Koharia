package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedAppPreferences
import koharia.domain.manga.model.toDomainManga
import koharia.importing.IncomingMediaSessionLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ComicSwipeTransitionDeviceTest {
    @Test
    fun leftToRightNoAnimation() = verify(ReadingMode.LEFT_TO_RIGHT, PageTransitionEffect.NONE)

    @Test
    fun rightToLeftNoAnimation() = verify(ReadingMode.RIGHT_TO_LEFT, PageTransitionEffect.NONE)

    @Test
    fun verticalNoAnimation() = verify(ReadingMode.VERTICAL, PageTransitionEffect.NONE)

    @Test
    fun curlStillUsesPageFlipAfterSwipe() = verify(ReadingMode.LEFT_TO_RIGHT, PageTransitionEffect.CURL)

    @Test
    fun fadeStillTransformsNativeDragging() = verify(ReadingMode.LEFT_TO_RIGHT, PageTransitionEffect.FADE)

    private fun verify(mode: ReadingMode, effect: PageTransitionEffect): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val connections = Injekt.get<ConnectionPreferences>()
        val profiles = connections.getProfiles()
        val active = connections.activeConnectionId.get()
        val sourceId = connections.allocateConnectionId()
        val preferences = Injekt.get<SharedAppPreferences>().readerPreferences()
        val restores = mutableListOf<() -> Unit>()
        fun <T> override(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val repository = Injekt.get<MangaRepository>()
        val sessionId = UUID.randomUUID().toString()
        val root = IncomingMediaSessionLocator.cacheRoot(context)
        val directory = File(root, sessionId).apply { mkdirs() }
        var mangaId: Long? = null
        try {
            connections.setProfiles(profiles + LibraryConnectionProfile(sourceId, "local-folder", "Swipe fixture"))
            val sourceDeadline = SystemClock.uptimeMillis() + 5_000
            while (Injekt.get<SourceManager>().get(sourceId) == null && SystemClock.uptimeMillis() < sourceDeadline) {
                SystemClock.sleep(25)
            }
            check(Injekt.get<SourceManager>().get(sourceId) != null)
            override(preferences.persistReaderSettingsChanges, false)
            override(preferences.pageLayout, PageLayout.SINGLE_PAGE.value)
            override(preferences.pagerPageTransitionEffect, effect.value)
            override(preferences.swipePageTurns, true)
            override(preferences.imageScaleType, SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
            override(preferences.landscapeZoom, false)
            override(preferences.cropBorders, false)
            override(Injekt.get<EInkPreferences>().enabled, false)
            val comic = File(directory, "swipe-fixture.cbz")
            ZipOutputStream(comic.outputStream()).use { zip ->
                repeat(5) { index ->
                    val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(if (index % 2 == 0) Color.WHITE else Color.LTGRAY)
                    try {
                        zip.putNextEntry(ZipEntry("$index.png"))
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            val manga = repository.insertNetworkManga(
                listOf(
                    SManga.create().apply {
                        url = IncomingMediaSessionLocator.seriesUrl(sourceId, sessionId)
                        title = "Swipe fixture $sessionId"
                        initialized = true
                    }.toDomainManga(sourceId).copy(
                        viewerFlags = (mode.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue).toLong(),
                    ),
                ),
            ).single()
            mangaId = manga.id
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = IncomingMediaSessionLocator.chapterUrl(sourceId, sessionId, comic.name),
                        name = "Swipe fixture",
                        chapterNumber = 1.0,
                    ),
                ),
            ).single()
            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId, pageIndex = 1),
            ).use { scenario ->
                lateinit var viewer: PagerViewer
                await(scenario, "Comic page ready") { activity ->
                    viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@await false
                    val adapter = viewer.pager.adapter as PagerViewerAdapter
                    val slot = adapter.slots.getOrNull(viewer.pager.currentItem) as? PagerSlot.Pages
                        ?: return@await false
                    viewer.pager.children.filterIsInstance<PagerPageHolder>()
                        .firstOrNull { it.slot == slot }?.isTransitionTargetReady() == true
                }
                val states = mutableListOf<Int>()
                var initialItem = 0
                val delta = if (mode == ReadingMode.RIGHT_TO_LEFT) -1 else 1
                scenario.onActivity {
                    assertEquals(effect, viewer.config.pageTransitionEffect)
                    initialItem = viewer.pager.currentItem
                    viewer.pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                        override fun onPageScrollStateChanged(state: Int) {
                            states += state
                        }
                    })
                    swipe(viewer.pager, mode, beforeRelease = {
                        if (effect == PageTransitionEffect.NONE || effect == PageTransitionEffect.CURL) {
                            assertEquals(initialItem, viewer.pager.currentItem)
                            assertFalse(states.contains(ViewPager.SCROLL_STATE_DRAGGING))
                        } else {
                            assertTrue(states.contains(ViewPager.SCROLL_STATE_DRAGGING))
                        }
                    })
                    if (effect == PageTransitionEffect.CURL) {
                        val controller = PagerViewer::class.java.getDeclaredField("pageFlipController")
                            .apply { isAccessible = true }.get(viewer) as ComicPageFlipController
                        assertTrue(controller.isRunning)
                    }
                }
                await(scenario, "One comic page turn") { viewer.pager.currentItem == initialItem + delta }
                SystemClock.sleep(500)
                scenario.onActivity {
                    assertEquals(initialItem + delta, viewer.pager.currentItem)
                    if (effect == PageTransitionEffect.NONE) {
                        assertFalse(states.contains(ViewPager.SCROLL_STATE_SETTLING))
                    }
                }
            }
        } finally {
            mangaId?.let { repository.deleteMangaById(it) }
            restores.asReversed().forEach { it() }
            connections.setProfiles(profiles)
            connections.activeConnectionId.set(active)
            check(directory.canonicalFile.parentFile == root.canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun await(
        scenario: ActivityScenario<ReaderActivity>,
        name: String,
        check: (ReaderActivity) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = check(it) }
            if (ready) return
            SystemClock.sleep(25)
        }
        error("Timed out: $name")
    }

    private fun swipe(pager: View, mode: ReadingMode, beforeRelease: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        for (step in 0..12) {
            val fraction = if (mode == ReadingMode.RIGHT_TO_LEFT) 0.2f + step * 0.05f else 0.8f - step * 0.05f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(
                now,
                now + step * 20L,
                action,
                pager.width * if (mode == ReadingMode.VERTICAL) 0.5f else fraction,
                pager.height * if (mode == ReadingMode.VERTICAL) fraction else 0.5f,
                0,
            )
            try {
                pager.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            if (step == 11) beforeRelease()
        }
    }
}
