package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.connection.SharedAppPreferences
import koharia.domain.manga.model.toDomainManga
import koharia.importing.IncomingMediaSessionLocator
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
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

/** Verifies that selected pages and visible loading feedback do not wait for image readiness. */
@RunWith(AndroidJUnit4::class)
class PagerVolumeKeyReadyDeviceTest {
    @Test
    fun twoFollowingPagesRenderBeforeSelection() = verify(prefetchOnly = true)

    @Test
    fun rightToLeftPreparesTwoFollowingPages() = verify(mode = ReadingMode.RIGHT_TO_LEFT, prefetchOnly = true)

    @Test
    fun eInkPreparesTwoFollowingPages() = verify(eInkMode = true, prefetchOnly = true)

    @Test
    fun allEffectsNavigateBeforeSlowImageFinishes() {
        PageTransitionEffect.entries.forEach { effect -> verify(effect = effect, readyDelayMs = 2_500) }
    }

    @Test
    fun eInkNavigatesBeforeSlowImageFinishes() = verify(eInkMode = true, readyDelayMs = 2_500)

    @Test
    fun rightToLeftNavigatesBeforeSlowImageFinishes() = verify(
        mode = ReadingMode.RIGHT_TO_LEFT,
        readyDelayMs = 2_500,
    )

    @Test
    fun verticalNavigatesBeforeSlowImageFinishes() = verify(mode = ReadingMode.VERTICAL, readyDelayMs = 2_500)

    @Test
    fun rapidVolumeKeysAndReverseDoNotWaitForDecode() = verify(reverseAfterWaiting = true)

    private fun verify(
        prefetchOnly: Boolean = false,
        reverseAfterWaiting: Boolean = false,
        eInkMode: Boolean = false,
        mode: ReadingMode = ReadingMode.LEFT_TO_RIGHT,
        readyDelayMs: Long = 0,
        effect: PageTransitionEffect = PageTransitionEffect.NONE,
    ): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val connections = Injekt.get<ConnectionPreferences>()
        val previousProfiles = connections.getProfiles()
        val previousActiveConnection = connections.activeConnectionId.get()
        val sourceId = connections.allocateConnectionId()
        val preferences = Injekt.get<SharedAppPreferences>().readerPreferences()
        val eInkPreferences = Injekt.get<EInkPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> override(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }

        val repository = Injekt.get<MangaRepository>()
        val sessionId = UUID.randomUUID().toString()
        val directory = File(IncomingMediaSessionLocator.cacheRoot(context), sessionId).apply { mkdirs() }
        var mangaId: Long? = null
        try {
            connections.setProfiles(
                previousProfiles + LibraryConnectionProfile(sourceId, "local-folder", "Volume key test"),
            )
            val sourceDeadline = SystemClock.uptimeMillis() + 5_000
            while (Injekt.get<SourceManager>().get(sourceId) == null && SystemClock.uptimeMillis() < sourceDeadline) {
                SystemClock.sleep(25)
            }
            check(Injekt.get<SourceManager>().get(sourceId) != null)
            override(preferences.persistReaderSettingsChanges, false)
            override(preferences.readWithVolumeKeys, true)
            override(preferences.readWithVolumeKeysInverted, false)
            override(preferences.pageLayout, PageLayout.SINGLE_PAGE.value)
            override(preferences.dualPageSplitPaged, false)
            override(preferences.navigateToPan, false)
            override(preferences.pagerPageTransitionEffect, effect.value)
            override(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            // E-Ink mode is what forces the transition effect to NONE on e-ink hardware.
            override(eInkPreferences.enabled, eInkMode)

            val comic = File(directory, "volume-key-test.cbz")
            val pageWidth = if (reverseAfterWaiting) 600 else PAGE_WIDTH
            val pageHeight = if (reverseAfterWaiting) 900 else PAGE_HEIGHT
            ZipOutputStream(comic.outputStream()).use { zip ->
                repeat(PAGE_COUNT) { index ->
                    val image = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    canvas.drawColor(Color.rgb(220, 220, 220))
                    canvas.drawRect(
                        0f,
                        0f,
                        pageWidth.toFloat(),
                        pageHeight / 4f,
                        Paint().apply { color = Color.rgb(30, 60 + index * 12, 140) },
                    )
                    zip.putNextEntry(ZipEntry("%03d.png".format(index)))
                    image.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    image.recycle()
                }
            }
            val manga = repository.insertNetworkManga(
                listOf(
                    SManga.create().apply {
                        url = IncomingMediaSessionLocator.seriesUrl(sourceId, sessionId)
                        title = "Volume key readiness $sessionId"
                        initialized = true
                    }.toDomainManga(sourceId).copy(viewerFlags = mode.flagValue.toLong()),
                ),
            ).single()
            mangaId = manga.id
            val chapter = Injekt.get<ChapterRepository>().addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = IncomingMediaSessionLocator.chapterUrl(sourceId, sessionId, comic.name),
                        name = "Volume key test",
                        chapterNumber = 1.0,
                    ),
                ),
            ).single()
            val initialPage = 5
            val intent = ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId, pageIndex = initialPage)
            FixtureActivityLauncher.launch<ReaderActivity>(intent).use { scenario ->
                awaitRenderedPage(scenario, initialPage)
                awaitRenderedPage(scenario, initialPage + 1, requireSelected = false)
                if (prefetchOnly) {
                    awaitRenderedPage(scenario, initialPage + 2, requireSelected = false)
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as PagerViewer
                        assertEquals(initialPage, (adapter(viewer).currentSlot() as PagerSlot.Pages).first.index)
                        assertTrue(viewer.pager.children.filterIsInstance<PagerPageHolder>().count() <= 5)
                    }
                    return@use
                }
                lateinit var slowPage: eu.kanade.tachiyomi.ui.reader.model.ReaderPage
                scenario.onActivity { activity ->
                    activity.hideMenu()
                    val viewer = activity.viewModel.state.value.viewer as PagerViewer
                    val target = adapter(viewer).slots.filterIsInstance<PagerSlot.Pages>()
                        .single { it.first.index == initialPage + 1 }
                    val holder = viewer.pager.children.filterIsInstance<PagerPageHolder>().single { it.slot == target }
                    slowPage = target.first
                    // Isolated fixture only: hide the decoded image and hold the real status at
                    // LoadPage. Navigation must reveal its actual progress UI before Ready.
                    holder.recycle()
                    PagerPageHolder::class.java.getDeclaredField("spreadDisplayed")
                        .apply { isAccessible = true }.setBoolean(holder, false)
                    slowPage.status = Page.State.LoadPage
                }
                SystemClock.sleep(100)
                val requestedAt = SystemClock.uptimeMillis()
                scenario.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as PagerViewer
                    viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
                }
                val deadline = requestedAt + 1_000
                var selectedWithFeedback = false
                while (!selectedWithFeedback && SystemClock.uptimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as PagerViewer
                        val slot = adapter(viewer).currentSlot() as? PagerSlot.Pages
                        val holder = viewer.pager.children.filterIsInstance<PagerPageHolder>()
                            .firstOrNull { it.slot == slot }
                        val flip = PagerViewer::class.java.getDeclaredField("pageFlipController")
                            .apply { isAccessible = true }.get(viewer) as ComicPageFlipController
                        val idle = PagerViewer::class.java.getDeclaredField("isIdle")
                            .apply { isAccessible = true }.getBoolean(viewer)
                        selectedWithFeedback = slot?.first === slowPage && holder != null &&
                            !flip.isRunning && idle &&
                            !holder.isTransitionTargetReady() &&
                            holder.children.filterIsInstance<ReaderProgressIndicator>().any { it.isShown }
                    }
                    if (!selectedWithFeedback) SystemClock.sleep(20)
                }
                assertTrue(
                    "$effect did not navigate to visible loading UI before image readiness",
                    selectedWithFeedback,
                )
                logcat { "PagerResponse effect=$effect selectedLoadingMs=${SystemClock.uptimeMillis() - requestedAt}" }
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.getExternalFilesDir(null), "response-$effect-$eInkMode-$mode.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                SystemClock.sleep(readyDelayMs.coerceAtLeast(500))
                scenario.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as PagerViewer
                    assertEquals(initialPage + 1, (adapter(viewer).currentSlot() as PagerSlot.Pages).first.index)
                    if (reverseAfterWaiting) {
                        repeat(3) {
                            viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
                        }
                        assertEquals(initialPage - 2, (adapter(viewer).currentSlot() as PagerSlot.Pages).first.index)
                    }
                    slowPage.status = Page.State.Ready
                }
                val expected = if (reverseAfterWaiting) initialPage - 2 else initialPage + 1
                awaitRenderedPage(scenario, expected)
                SystemClock.sleep(400)
                scenario.onActivity { activity ->
                    val viewer = activity.viewModel.state.value.viewer as PagerViewer
                    assertEquals(
                        "Late readiness must not perform another navigation",
                        expected,
                        (adapter(viewer).currentSlot() as PagerSlot.Pages).first.index,
                    )
                    assertEquals(0, pendingPageTurnDelta(viewer))
                }
            }
        } finally {
            mangaId?.let { repository.deleteMangaById(it) }
            restores.asReversed().forEach { it() }
            connections.setProfiles(previousProfiles)
            connections.activeConnectionId.set(previousActiveConnection)
            check(directory.canonicalFile.parentFile == IncomingMediaSessionLocator.cacheRoot(context).canonicalFile)
            directory.deleteRecursively()
            // Leave the app in the foreground so later reader tests in the same session can start
            // their activities on devices that refuse background activity starts.
            FixtureActivityLauncher.ensureForeground()
        }
    }

    private fun awaitRenderedPage(
        scenario: ActivityScenario<ReaderActivity>,
        pageIndex: Int,
        requireSelected: Boolean = true,
    ) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var diagnostic = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@onActivity
                val adapter = adapter(viewer)
                val slot = if (requireSelected) {
                    adapter.currentSlot() as? PagerSlot.Pages
                } else {
                    adapter.slots.filterIsInstance<PagerSlot.Pages>().firstOrNull { it.first.index == pageIndex }
                }
                diagnostic = "position=${viewer.pager.currentItem} count=${adapter.count} " +
                    "slots=${adapter.slots.mapIndexed { index, value ->
                        "$index:${value::class.simpleName}"
                    }} " +
                    "holdings=${viewer.pager.children.filterIsInstance<PagerPageHolder>().joinToString {
                        "${it.slot.first.index}:${it.isTransitionTargetReady()}"
                    }} pending=${pendingPageTurnDelta(viewer)}"
                ready = slot != null && slot.first.index == pageIndex &&
                    viewer.pager.children.filterIsInstance<PagerPageHolder>()
                        .any { it.slot == slot && it.isTransitionTargetReady() }
            }
            if (ready) return
            SystemClock.sleep(25)
        }
        assertTrue("Page $pageIndex was never rendered; $diagnostic", false)
    }

    private fun adapter(viewer: PagerViewer): PagerViewerAdapter =
        PagerViewer::class.java.getDeclaredField("adapter").apply { isAccessible = true }
            .get(viewer) as PagerViewerAdapter

    private fun pendingPageTurnDelta(viewer: PagerViewer): Int =
        PagerViewer::class.java.getDeclaredField("pendingPageTurnDelta").apply { isAccessible = true }
            .getInt(viewer)

    private companion object {
        const val PAGE_COUNT = 12
        const val PAGE_WIDTH = 1_400
        const val PAGE_HEIGHT = 2_000
    }
}
