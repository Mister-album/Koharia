package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.ViewPager
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
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

/**
 * Verifies volume key paging while E-Ink mode forces [PageTransitionEffect.NONE], which is the
 * configuration in which a page that is still loading used to be revealed as a blank placeholder.
 */
@RunWith(AndroidJUnit4::class)
class PagerVolumeKeyReadyDeviceTest {

    @Test
    fun rapidVolumeKeysNeverRevealAPageThatIsStillRendering() = verify(presses = 10)

    @Test
    fun queuedReverseKeysDrainWithoutAnimation() = verify(presses = 3, reverseAfterWaiting = true, eInkMode = false)

    @Test
    fun queuedReverseKeysDrainInEInkMode() = verify(presses = 3, reverseAfterWaiting = true)

    @Test
    fun queuedReverseKeysDrainWithDefaultPrefetch() = verify(
        presses = 3,
        reverseAfterWaiting = true,
        eInkMode = false,
        retainReadyTargets = false,
    )

    @Test
    fun queuedReverseKeysDrainRightToLeft() = verify(
        presses = 3,
        reverseAfterWaiting = true,
        eInkMode = false,
        mode = ReadingMode.RIGHT_TO_LEFT,
    )

    @Test
    fun queuedReverseKeysDrainVertically() = verify(
        presses = 3,
        reverseAfterWaiting = true,
        eInkMode = false,
        mode = ReadingMode.VERTICAL,
    )

    private fun verify(
        presses: Int,
        reverseAfterWaiting: Boolean = false,
        eInkMode: Boolean = true,
        retainReadyTargets: Boolean = true,
        mode: ReadingMode = ReadingMode.LEFT_TO_RIGHT,
    ) = runBlocking(Dispatchers.IO) {
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
            override(preferences.pagerPageTransitionEffect, PageTransitionEffect.NONE.value)
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
            val initialPage = if (reverseAfterWaiting) 5 else 0
            val intent = ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId, pageIndex = initialPage)
            FixtureActivityLauncher.launch<ReaderActivity>(intent).use { scenario ->
                awaitRenderedPage(scenario, initialPage)
                if (reverseAfterWaiting) {
                    if (retainReadyTargets) {
                        scenario.onActivity { activity ->
                            val viewer = activity.viewModel.state.value.viewer as PagerViewer
                            viewer.pager.offscreenPageLimit = presses + 1
                        }
                        for (page in initialPage + 1 - presses..initialPage + 1) {
                            awaitRenderedPage(scenario, page, requireSelected = false)
                        }
                    } else {
                        awaitRenderedPage(scenario, initialPage + 1, requireSelected = false)
                    }
                }
                val revealedWhileLoading = mutableListOf<String>()
                scenario.onActivity { activity ->
                    activity.hideMenu()
                    val viewer = activity.viewModel.state.value.viewer as PagerViewer
                    assertFalse(
                        "Volume keys must be usable in this scenario",
                        activity.viewModel.state.value.menuVisible,
                    )
                    assertEquals(
                        "E-Ink mode must select the instant page turn this test covers",
                        PageTransitionEffect.NONE,
                        viewer.config.pageTransitionEffect,
                    )
                    viewer.pager.addOnPageChangeListener(
                        object : ViewPager.SimpleOnPageChangeListener() {
                            override fun onPageSelected(position: Int) {
                                val slot = adapter(viewer).slots.getOrNull(position) as? PagerSlot.Pages
                                    ?: return
                                val holder = viewer.pager.children.filterIsInstance<PagerPageHolder>()
                                    .firstOrNull { it.slot == slot }
                                if (holder?.isTransitionTargetReady() != true) {
                                    revealedWhileLoading += "position=$position page=${slot.progressPage.number}"
                                }
                            }
                        },
                    )
                    fun press(key: Int) {
                        viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                        viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                    }
                    if (reverseAfterWaiting) {
                        val currentPosition = viewer.pager.currentItem
                        val target = adapter(viewer).slots.filterIsInstance<PagerSlot.Pages>()
                            .single { it.first.index == initialPage + 1 }
                        val holder = viewer.pager.children.filterIsInstance<PagerPageHolder>().single {
                            it.slot ==
                                target
                        }
                        // Hold readiness to reproduce a slow offscreen render without timing a network response.
                        val readiness = PagerPageHolder::class.java.getDeclaredField("spreadDisplayed")
                            .apply { isAccessible = true }
                        readiness.setBoolean(holder, false)
                        try {
                            press(KeyEvent.KEYCODE_VOLUME_DOWN)
                            assertEquals(currentPosition, viewer.pager.currentItem)
                            repeat(presses) { press(KeyEvent.KEYCODE_VOLUME_UP) }
                            assertEquals(
                                if (mode == ReadingMode.RIGHT_TO_LEFT) presses else -presses,
                                pendingPageTurnDelta(viewer),
                            )
                        } finally {
                            readiness.setBoolean(holder, true)
                        }
                        viewer.onTransitionTargetReady(target)
                    } else {
                        repeat(presses) { press(KeyEvent.KEYCODE_VOLUME_DOWN) }
                    }
                }
                // Every requested turn must still be honoured, however slowly pages arrive.
                awaitRenderedPage(scenario, if (reverseAfterWaiting) initialPage + 1 - presses else presses)
                scenario.onActivity { activity ->
                    assertEquals(0, pendingPageTurnDelta(activity.viewModel.state.value.viewer as PagerViewer))
                }
                logcat {
                    "PagerVolumeKeyReady: revealedWhileLoading=$revealedWhileLoading"
                }
                assertTrue(
                    "Pager selected a page before it finished rendering: $revealedWhileLoading",
                    revealedWhileLoading.isEmpty(),
                )
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
