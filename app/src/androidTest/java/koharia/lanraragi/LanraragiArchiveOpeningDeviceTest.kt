package koharia.lanraragi

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionScopedPreferenceStoreFactory
import koharia.domain.lanraragi.LanraragiEntry
import koharia.lanraragi.ui.LanraragiArchivePreviewScreen
import koharia.lanraragi.ui.LanraragiLibraryScreen
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicReference

/** Runs only in the isolated fixture package. Keeps its connection and APK available for inspection. */
@RunWith(AndroidJUnit4::class)
class LanraragiArchiveOpeningDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun shelfRoutesAndPreviewPagesWorkWithoutPreviewProgressWrites() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val base = Injekt.get<BasePreferences>()
        val oldIncognito = base.incognitoMode.get()
        val manager = Injekt.get<ConnectionProfileManager>()
        val profile = manager.add(LanraragiConnectionProvider.ID, "Archive preview UI test")
        val preferences = LanraragiPreferences(profile.id)
        preferences.save("http://127.0.0.1:38709/v80/lrr/", "fixture-key", LanraragiArchiveOpenMode.READER)
        Injekt.get<ConnectionPreferences>().activeConnectionId.set(profile.id)
        val source = withTimeout(15_000) {
            while (Injekt.get<SourceManager>().get(profile.id) !is LanraragiSource) delay(100)
            Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
        }
        source.refreshLibrary().getOrThrow()
        val readerPrefs = Injekt.get<ConnectionScopedPreferenceStoreFactory>().readerPreferences(source.id)
        readerPrefs.showNavigationOverlayNewUser.set(false)
        readerPrefs.showNavigationOverlayOnStart.set(false)
        val entries = source.repository.entries(source.id)
        val archive = entries.first { it.title == "Fixture book 5" }
        val tank = entries.first { it.kind == LanraragiEntry.Kind.TANK }
        val navigator = AtomicReference<Navigator>()
        base.incognitoMode.set(true)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(LanraragiLibraryScreen(source.id, null, false)) {
                                navigator.set(it)
                                CurrentScreen()
                            }
                        }
                    }
                }
                click("Dynamic fixture")
                click(context.stringResource(MR.strings.all))
                click(context.stringResource(MR.strings.action_search))
                awaitLabel(context.stringResource(MR.strings.pref_connection_management), scroll = false)
                assertNull(
                    find(instrumentation.uiAutomation.rootInActiveWindow) {
                        it.text?.toString() == context.stringResource(MR.strings.lanraragi_local_search) ||
                            it.text?.toString() == context.stringResource(MR.strings.lanraragi_advanced_search)
                    },
                )
                capture("search")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                delay(300)
                capture("shelf")
                click(context.stringResource(MR.strings.action_filter))
                awaitLabel(context.stringResource(MR.strings.lanraragi_group_tanks), scroll = false)
                awaitLabel(context.stringResource(MR.strings.action_reset), scroll = false)
                capture("filters")
                click(context.stringResource(MR.strings.action_filter))
                openReader(archive.title, 0)
                click(tank.title)
                withTimeout(15_000) {
                    while (navigator.get().lastItem !is MangaScreen) delay(100)
                }
                val tankId = (navigator.get().lastItem as MangaScreen).mangaId
                withTimeout(20_000) {
                    while (Injekt.get<ChapterRepository>().getChapterByMangaId(tankId).size < 3) delay(100)
                }
                capture("tank")
                instrumentation.runOnMainSync { navigator.get().pop() }
                val beforeChapters = Injekt.get<ChapterRepository>().getChapterByMangaId(tankId).associateBy { it.url }
                val extra = entries.first { it.title == "Fixture book 4" }
                val changedTank = tank.copy(members = tank.members.reversed() + extra.id)
                val generation = source.repository.lastSync(source.id) + 1
                source.repository.stage(
                    source.id,
                    generation,
                    entries.map {
                        if (it.id ==
                            tank.id
                        ) {
                            changedTank
                        } else {
                            it
                        }
                    },
                )
                source.repository.publish(source.id, generation, generation)
                click(tank.title)
                withTimeout(15_000) {
                    while (Injekt.get<ChapterRepository>().getChapterByMangaId(tankId).size != 4) delay(100)
                }
                val updated = Injekt.get<ChapterRepository>().getChapterByMangaId(tankId).sortedBy { it.sourceOrder }
                assertEquals(changedTank.members.reversed(), updated.map { source.archiveId(it.url) })
                updated.forEach { chapter -> beforeChapters[chapter.url]?.let { assertEquals(it.id, chapter.id) } }
                instrumentation.runOnMainSync { navigator.get().pop() }
                preferences.save(preferences.address, preferences.apiKey, LanraragiArchiveOpenMode.PAGE_PREVIEW)
                base.incognitoMode.set(false)
                click(archive.title)
                withTimeout(15_000) {
                    while (navigator.get().lastItem !is LanraragiArchivePreviewScreen) delay(100)
                }
                val manga = source.materialize(archive)
                val chapter = Injekt.get<ChapterRepository>().getChapterByUrlAndMangaId(manga.url, manga.id)!!
                val cache = Injekt.get<ChapterCache>()
                val pages = source.getPageList(
                    source.getChapterList(
                        eu.kanade.tachiyomi.source.model.SManga.create().apply { url = manga.url },
                    ).single(),
                )
                awaitLabel(context.stringResource(MR.strings.lanraragi_page_number, 1), scroll = true)
                withTimeout(20_000) {
                    while (!pages.all { cache.isImageInCache(it.imageUrl!!) }) delay(100)
                }
                capture("preview")
                assertFalse(source.repository.readStates(source.id).any { it.pending })
                assertEquals(0, source.api.archive(archive.id).progress)
                assertTrue(source.api.archive(archive.id).isNew)
                assertEquals(0L, Injekt.get<ChapterRepository>().getChapterById(chapter.id)!!.lastPageRead)
                base.incognitoMode.set(true)
                for (index in 0..2) {
                    openReader(
                        context.stringResource(MR.strings.lanraragi_page_number, index + 1),
                        index,
                        explicit = true,
                    )
                }
                openReader(context.stringResource(MR.strings.action_start), 0)
            }
        } finally {
            base.incognitoMode.set(oldIncognito)
        }
    }

    private suspend fun openReader(label: String, pageIndex: Int, explicit: Boolean = false) {
        val started = System.nanoTime()
        val monitor = Instrumentation.ActivityMonitor(ReaderActivity::class.java.name, null, false)
        instrumentation.addMonitor(monitor)
        var reader: ReaderActivity? = null
        try {
            click(label)
            reader = instrumentation.waitForMonitorWithTimeout(monitor, 20_000) as? ReaderActivity
            checkNotNull(reader) { "Reader did not open for $label" }
            assertEquals(explicit, reader.intent.getBooleanExtra("explicit_page_selection", false))
            if (explicit) assertEquals(pageIndex, reader.intent.getIntExtra("page_index", -1))
            withTimeout(30_000) {
                while (true) {
                    var ready = false
                    instrumentation.runOnMainSync {
                        val state = reader.viewModel.state.value
                        ready = state.currentPage == pageIndex + 1 &&
                            state.currentChapter?.pages?.getOrNull(pageIndex)?.status == Page.State.Ready
                    }
                    if (ready) break
                    delay(100)
                }
            }
            println("LANraragi startup: page=${pageIndex + 1} readyMs=${(System.nanoTime() - started) / 1_000_000}")
            capture("reader-${pageIndex + 1}")
        } finally {
            reader?.let { activity ->
                instrumentation.runOnMainSync { activity.finish() }
                withTimeout(10_000) { while (!activity.isDestroyed) delay(100) }
            }
            instrumentation.removeMonitor(monitor)
        }
    }

    private suspend fun click(label: String) {
        println("LANraragi UI: clicking $label")
        try {
            withTimeout(20_000) {
                while (true) {
                    val node = awaitLabel(label, scroll = true)
                    var target: AccessibilityNodeInfo? = node
                    while (target != null && !target.isClickable) target = target.parent
                    if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                    delay(250)
                }
            }
        } catch (error: Exception) {
            capture("failure")
            fun dump(node: AccessibilityNodeInfo?) {
                if (node == null) return
                println("LANraragi UI node: ${node.text} / ${node.contentDescription} clickable=${node.isClickable}")
                for (index in 0 until node.childCount) dump(node.getChild(index))
            }
            dump(instrumentation.uiAutomation.rootInActiveWindow)
            throw AssertionError("Could not click $label", error)
        }
        delay(300)
    }

    private suspend fun awaitLabel(label: String, scroll: Boolean): AccessibilityNodeInfo = withTimeout(20_000) {
        while (true) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            find(root) { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let {
                return@withTimeout it
            }
            if (scroll) find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(250)
        }
        error("Unreachable")
    }

    private fun find(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun capture(name: String) {
        instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/lanraragi-opening-$name.png",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }
}
