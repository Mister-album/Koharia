package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
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
class PagerSplitDeviceTest {
    @Test
    fun rightToLeftPreservesBothHalvesAcrossPrefetchAndReopen() = verify(ReadingMode.RIGHT_TO_LEFT)

    @Test
    fun leftToRightPreservesBothHalvesAcrossPrefetchAndReopen() = verify(ReadingMode.LEFT_TO_RIGHT)

    @Test
    fun automaticSinglePageFitsBothHalvesDespiteFitHeightPreference() = verify(
        ReadingMode.RIGHT_TO_LEFT,
        automatic = true,
    )

    @Test
    fun automaticDoubleRestoresWholeWideImagesAcrossRotation() = verify(
        ReadingMode.RIGHT_TO_LEFT,
        rotateAutomaticDouble = true,
    )

    @Test
    fun automaticDoubleRestoresWholeWideImagesAcrossRotationLeftToRight() = verify(
        ReadingMode.LEFT_TO_RIGHT,
        rotateAutomaticDouble = true,
    )

    @Test
    fun manualDoublePageSwitchKeepsWideFirstPageRenderedWhenOtherPairsChange() = verify(
        ReadingMode.RIGHT_TO_LEFT,
        rotateAutomaticDouble = true,
        manualShiftSwitch = true,
    )

    private fun verify(
        mode: ReadingMode,
        automatic: Boolean = false,
        rotateAutomaticDouble: Boolean = false,
        manualShiftSwitch: Boolean = false,
    ) = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val connections = Injekt.get<ConnectionPreferences>()
        val previousProfiles = connections.getProfiles()
        val previousActiveConnection = connections.activeConnectionId.get()
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
        val directory = File(IncomingMediaSessionLocator.cacheRoot(context), sessionId).apply { mkdirs() }
        var mangaId: Long? = null
        try {
            connections.setProfiles(previousProfiles + LibraryConnectionProfile(sourceId, "local-folder", "Split test"))
            val sourceDeadline = SystemClock.uptimeMillis() + 5_000
            while (Injekt.get<SourceManager>().get(sourceId) == null && SystemClock.uptimeMillis() < sourceDeadline) {
                SystemClock.sleep(25)
            }
            check(Injekt.get<SourceManager>().get(sourceId) != null)
            override(preferences.persistReaderSettingsChanges, false)
            override(preferences.dualPageSplitPaged, !automatic && !manualShiftSwitch)
            override(preferences.dualPageInvertPaged, false)
            override(preferences.shiftDoublePages, manualShiftSwitch)
            override(preferences.invertDoublePages, false)
            override(preferences.dualPageRotateToFit, false)
            override(
                preferences.pageLayout,
                when {
                    manualShiftSwitch -> PageLayout.SINGLE_PAGE.value
                    rotateAutomaticDouble -> PageLayout.AUTOMATIC_DOUBLE_PAGES.value
                    automatic -> PageLayout.AUTOMATIC_SINGLE_PAGE.value
                    else -> PageLayout.SINGLE_PAGE.value
                },
            )
            override(preferences.pagerPageTransitionEffect, 0)
            override(preferences.navigateToPan, !rotateAutomaticDouble)
            override(preferences.landscapeZoom, true)
            val preferredScale = if (automatic) {
                SubsamplingScaleImageView.SCALE_TYPE_FIT_HEIGHT
            } else {
                SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
            }
            override(preferences.imageScaleType, preferredScale)
            val comic = File(directory, "split-test.cbz")
            ZipOutputStream(comic.outputStream()).use { zip ->
                repeat(6) { index ->
                    val width = if (rotateAutomaticDouble && index > 0) {
                        300
                    } else if (index % 2 == 0) {
                        1200
                    } else {
                        3600
                    }
                    val image = Bitmap.createBitmap(width, 600, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(image)
                    canvas.drawColor(Color.rgb(30 + index * 30, 60, 140))
                    canvas.drawRect(
                        width / 2f,
                        0f,
                        width.toFloat(),
                        600f,
                        Paint().apply {
                            color = Color.rgb(190, 30 + index * 30, 50)
                        },
                    )
                    zip.putNextEntry(ZipEntry("$index.png"))
                    image.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                    image.recycle()
                }
            }
            val manga = repository.insertNetworkManga(
                listOf(
                    SManga.create().apply {
                        url = IncomingMediaSessionLocator.seriesUrl(sourceId, sessionId)
                        title = "Issue 82 split regression $sessionId"
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
                        name = "Split test",
                        chapterNumber = 1.0,
                    ),
                ),
            ).single()
            val intent = ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId, pageIndex = 0)
            repeat(if (automatic || rotateAutomaticDouble) 1 else 2) {
                ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                    if (manualShiftSwitch) {
                        awaitLogicalSlot(scenario, 0, null)
                        repeat(3) {
                            scenario.onActivity { activity ->
                                checkNotNull(activity.viewModel.state.value.viewerChapters?.currChapter?.pages)
                                    .forEach { it.spreadInfo = ReaderPage.SpreadInfo.UNKNOWN }
                                activity.readerPreferences.pageLayout.set(PageLayout.DOUBLE_PAGES.value)
                            }
                            awaitManualLayout(scenario, doublePages = true)
                            scenario.onActivity { activity ->
                                val slots = adapter(activity.viewModel.state.value.viewer as PagerViewer)
                                    .slots.filterIsInstance<PagerSlot.Pages>()
                                assertTrue(slots.any { it.first.index == 0 && it.second == null })
                                assertTrue(slots.any { it.first.index == 1 && it.second == null })
                                assertTrue(slots.any { it.first.index == 2 && it.second?.index == 3 })
                                activity.readerPreferences.pageLayout.set(PageLayout.SINGLE_PAGE.value)
                            }
                            awaitManualLayout(scenario, doublePages = false)
                        }
                        return@use
                    }
                    awaitHalf(scenario, 0)
                    if (rotateAutomaticDouble) {
                        scenario.onActivity { activity ->
                            (activity.viewModel.state.value.viewer as PagerViewer).moveToNext()
                        }
                        awaitHalf(scenario, 1)
                        scenario.onActivity { it.viewModel.setMangaOrientationType(ReaderOrientation.LOCKED_LANDSCAPE) }
                        awaitWholeWidePage(scenario)
                        scenario.onActivity { it.viewModel.setMangaOrientationType(ReaderOrientation.LOCKED_PORTRAIT) }
                        awaitHalf(scenario, 0)
                        scenario.onActivity { it.viewModel.setMangaOrientationType(ReaderOrientation.LOCKED_LANDSCAPE) }
                        awaitWholeWidePage(scenario)
                        scenario.onActivity { (it.viewModel.state.value.viewer as PagerViewer).moveToNext() }
                        awaitLogicalSlot(scenario, 1, null)
                        scenario.onActivity { (it.viewModel.state.value.viewer as PagerViewer).moveToNext() }
                        awaitLogicalSlot(scenario, 2, 3)
                        scenario.onActivity { activity ->
                            assertEquals(3, activity.viewModel.state.value.viewerChapters?.currChapter?.requestedPage)
                            activity.viewModel.setMangaOrientationType(ReaderOrientation.LOCKED_PORTRAIT)
                        }
                        awaitHalf(scenario, 4)
                        scenario.onActivity { activity ->
                            assertEquals(
                                "Rotation must not rewind committed reading progress",
                                3,
                                activity.viewModel.state.value.viewerChapters?.currChapter?.requestedPage,
                            )
                        }
                        return@use
                    }
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as PagerViewer
                        val adapter = adapter(viewer)
                        val farPage = checkNotNull(
                            activity.viewModel.state.value.viewerChapters?.currChapter?.pages,
                        ).last()
                        val countBefore = adapter.count
                        assertTrue(viewer.pager.beginFakeDrag())
                        try {
                            viewer.pager.fakeDragBy(0f)
                            viewer.onPageSplit(farPage, InsertPage(farPage))
                            assertEquals("Splits must wait until dragging ends", countBefore, adapter.count)
                        } finally {
                            viewer.pager.endFakeDrag()
                        }
                    }
                    awaitHalf(scenario, 0)
                    for (half in 1 until 12) {
                        scenario.onActivity { activity ->
                            (activity.viewModel.state.value.viewer as PagerViewer).moveToNext()
                        }
                        awaitHalf(scenario, half)
                        if (half == 2) {
                            verifyZoomAndReset(scenario, mode)
                            awaitHalf(scenario, half)
                        }
                        if (half == 1) {
                            scenario.onActivity { activity ->
                                val viewer = activity.viewModel.state.value.viewer as PagerViewer
                                val adapter = adapter(viewer)
                                val before = adapter.currentSlot()
                                viewer.setChapters(checkNotNull(activity.viewModel.state.value.viewerChapters))
                                assertEquals(
                                    "Chapter refresh must preserve the second half",
                                    before,
                                    adapter.currentSlot(),
                                )
                            }
                            awaitHalf(scenario, half)
                        }
                    }
                    for (half in 10 downTo 0) {
                        scenario.onActivity { activity ->
                            (activity.viewModel.state.value.viewer as PagerViewer).moveToPrevious()
                        }
                        awaitHalf(scenario, half)
                    }
                    scenario.onActivity { activity ->
                        val viewer = activity.viewModel.state.value.viewer as PagerViewer
                        val adapter = adapter(viewer)
                        val pagesBefore = adapter.slots.filterIsInstance<PagerSlot.Pages>().map { it.first }
                        viewer.setChapters(checkNotNull(activity.viewModel.state.value.viewerChapters))
                        assertEquals(pagesBefore, adapter.slots.filterIsInstance<PagerSlot.Pages>().map { it.first })
                        assertEquals(preferredScale, activity.readerPreferences.imageScaleType.get())
                        assertTrue(activity.readerPreferences.landscapeZoom.get())
                        assertTrue(activity.readerPreferences.navigateToPan.get())
                    }
                    awaitHalf(scenario, 0)
                }
            }
        } finally {
            mangaId?.let { repository.deleteMangaById(it) }
            restores.asReversed().forEach { it() }
            connections.setProfiles(previousProfiles)
            connections.activeConnectionId.set(previousActiveConnection)
            check(directory.canonicalFile.parentFile == IncomingMediaSessionLocator.cacheRoot(context).canonicalFile)
            directory.deleteRecursively()
        }
    }

    private fun awaitManualLayout(scenario: ActivityScenario<ReaderActivity>, doublePages: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@onActivity
                val slot = adapter(viewer).currentSlot() as? PagerSlot.Pages ?: return@onActivity
                ready = viewer.config.doublePages == doublePages && slot.first.index == 0 && slot.second == null &&
                    viewer.pager.children.filterIsInstance<PagerPageHolder>().any { holder ->
                        holder.slot == slot && holder.renderedLayout == viewer.config.resolvedLayout &&
                            holder.isTransitionTargetReady() &&
                            holder.children.filterIsInstance<SubsamplingScaleImageView>().any {
                                it.sWidth == 1200 && it.sHeight == 600
                            }
                    }
            }
            if (ready) {
                if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - stableSince > 500) return
            } else {
                stableSince = 0L
            }
            SystemClock.sleep(25)
        }
        assertTrue("Manual layout switch must finish drawing the retained wide page; double=$doublePages", false)
    }

    private fun awaitLogicalSlot(scenario: ActivityScenario<ReaderActivity>, first: Int, second: Int?) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@onActivity
                val slot = adapter(viewer).currentSlot() as? PagerSlot.Pages ?: return@onActivity
                ready = slot.first.index == first && slot.second?.index == second &&
                    viewer.pager.children.filterIsInstance<PagerPageHolder>().any {
                        it.slot == slot && it.isTransitionTargetReady()
                    }
            }
            if (ready) {
                if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - stableSince > 500) return
            } else {
                stableSince = 0L
            }
            SystemClock.sleep(25)
        }
        assertTrue("Expected stable logical slot $first/$second", false)
    }

    private fun awaitWholeWidePage(scenario: ActivityScenario<ReaderActivity>) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var readySince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@onActivity
                val adapter = adapter(viewer)
                val slot = adapter.currentSlot() as? PagerSlot.Pages ?: return@onActivity
                ready = viewer.config.doublePages && !viewer.config.splitsWidePages && slot.first.index == 0 &&
                    slot.first !is InsertPage && slot.second == null &&
                    adapter.slots.filterIsInstance<PagerSlot.Pages>().none {
                        it.first is InsertPage ||
                            it.second is InsertPage
                    } &&
                    viewer.pager.children.filterIsInstance<PagerPageHolder>().any { holder ->
                        holder.slot == slot && holder.isTransitionTargetReady() &&
                            holder.children.filterIsInstance<SubsamplingScaleImageView>().any {
                                it.sWidth == 1200 && it.sHeight == 600
                            }
                    }
            }
            if (ready) {
                if (readySince == 0L) readySince = SystemClock.uptimeMillis()
                if (SystemClock.uptimeMillis() - readySince > 500) return
            } else {
                readySince = 0
            }
            SystemClock.sleep(25)
        }
        assertTrue("Landscape must display the whole original wide page without inserted halves", false)
    }

    private fun awaitHalf(scenario: ActivityScenario<ReaderActivity>, expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var stableSince = 0L
        var actual: Int? = null
        var diagnostic = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { activity ->
                val viewer = activity.viewModel.state.value.viewer as? PagerViewer ?: return@onActivity
                val slot = adapter(viewer).currentSlot() as? PagerSlot.Pages ?: return@onActivity
                actual = slot.first.index * 2 + if (slot.first is InsertPage) 1 else 0
                diagnostic = "double=${viewer.config.doublePages}, split=${viewer.config.splitsWidePages}, " +
                    "viewport=${viewer.pager.width}x${viewer.pager.height}, holders=" +
                    viewer.pager.children.filterIsInstance<PagerPageHolder>().joinToString { holder ->
                        val image = holder.children.filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                        "${holder.slot.first.index}/${holder.slot.first is InsertPage}:" +
                            "ready=${holder.isTransitionTargetReady()},selected=${holder.slot == slot}," +
                            "size=${image?.sWidth}x${image?.sHeight},scale=${image?.scale}/${image?.minScale}," +
                            "pan=${holder.canNavigatePanLeft()}/${holder.canNavigatePanRight()}"
                    }
                ready = viewer.pager.children.filterIsInstance<PagerPageHolder>()
                    .any { holder ->
                        val image = holder.children.filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                        holder.slot == slot && holder.isTransitionTargetReady() && image != null &&
                            kotlin.math.abs(image.scale - image.minScale) < 0.001f &&
                            !holder.canNavigatePanLeft() && !holder.canNavigatePanRight()
                    }
            }
            if (actual == expected && ready) {
                if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
                val settleMillis = if ((expected / 2) % 2 == 1) 1_100 else 300
                if (SystemClock.uptimeMillis() - stableSince >= settleMillis) return
            } else {
                stableSince = 0
            }
            SystemClock.sleep(25)
        }
        assertTrue("Expected half $expected to remain ready; actual=$actual; $diagnostic", false)
    }

    private fun verifyZoomAndReset(scenario: ActivityScenario<ReaderActivity>, mode: ReadingMode) {
        lateinit var holder: PagerPageHolder
        lateinit var image: SubsamplingScaleImageView
        lateinit var initialSlot: PagerSlot
        scenario.onActivity { activity ->
            val viewer = activity.viewModel.state.value.viewer as PagerViewer
            initialSlot = checkNotNull(adapter(viewer).currentSlot())
            holder = viewer.pager.children.filterIsInstance<PagerPageHolder>().first { it.slot == initialSlot }
            image = holder.children.filterIsInstance<SubsamplingScaleImageView>().single()
            image.setScaleAndCenter(image.minScale * 2, PointF(image.sWidth / 2f, image.sHeight / 2f))
        }
        val deadline = SystemClock.uptimeMillis() + 3_000
        var canPan = false
        while (!canPan && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity {
                canPan =
                    if (mode == ReadingMode.RIGHT_TO_LEFT) holder.canNavigatePanLeft() else holder.canNavigatePanRight()
            }
            if (!canPan) SystemClock.sleep(25)
        }
        assertTrue("A deliberately zoomed half-page must still allow panning", canPan)
        var centerBefore = 0f
        scenario.onActivity { activity ->
            centerBefore = checkNotNull(image.center).x
            (activity.viewModel.state.value.viewer as PagerViewer).moveToNext()
        }
        SystemClock.sleep(350)
        scenario.onActivity { activity ->
            assertEquals(initialSlot, adapter(activity.viewModel.state.value.viewer as PagerViewer).currentSlot())
            assertFalse(
                "Navigation must pan the zoomed image",
                kotlin.math.abs(checkNotNull(image.center).x - centerBefore) < 1f,
            )
            image.setScaleAndCenter(image.minScale, PointF(image.sWidth / 2f, image.sHeight / 2f))
        }
    }

    private fun adapter(viewer: PagerViewer): PagerViewerAdapter =
        PagerViewer::class.java.getDeclaredField("adapter").apply { isAccessible = true }
            .get(viewer) as PagerViewerAdapter
}
