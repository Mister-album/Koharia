package koharia.lanraragi

import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileInputStream

/** Opt-in live test: enable with runLanraragiDemo=true. Public demo requests are read-only. */
@RunWith(AndroidJUnit4::class)
class LanraragiOfficialDemoTest {
    @Test
    fun browseReadAndDownloadPublicDemoWithoutWritingRemoteProgress() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLanraragiDemo") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager: ConnectionProfileManager = Injekt.get()
        val preferences: ConnectionPreferences = Injekt.get()
        val base: BasePreferences = Injekt.get()
        val storage: StoragePreferences = Injekt.get()
        val downloadPreferences: DownloadPreferences = Injekt.get()
        val downloads: DownloadManager = Injekt.get()
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        assertTrue("Use an emulator with an empty download queue", downloads.queueState.value.isEmpty())
        val previousActive = preferences.activeConnectionId.get()
        val previousIncognito = base.incognitoMode.get()
        val previousOnboarding = base.shownOnboardingFlow.get()
        val previousWifi = downloadPreferences.downloadOnlyOverWifi.get()
        val previousStorage = storage.baseStorageDirectory.get()
        val hadStorage = storage.baseStorageDirectory.isSet()
        val directory = File(context.getExternalFilesDir(null), "lanraragi-demo-${System.nanoTime()}").apply {
            mkdirs()
        }
        base.incognitoMode.set(true)
        base.shownOnboardingFlow.set(true)
        downloadPreferences.downloadOnlyOverWifi.set(false)
        storage.baseStorageDirectory.set(directory.toURI().toString())
        val profile = manager.add(LanraragiConnectionProvider.ID, "LANraragi official Demo")
        LanraragiPreferences(profile.id).save("https://lrr.tvc-16.science/", "")
        var source: LanraragiSource? = null
        try {
            source = withTimeout(10_000) {
                var current: LanraragiSource?
                do {
                    current = Injekt.get<SourceManager>().get(profile.id) as? LanraragiSource
                    if (current == null) delay(100)
                } while (current == null)
                current
            }
            val info = source.api.serverInfo(true)
            val result = source.refreshLibrary().getOrThrow()
            val entries = source.repository.entries(source.id)
            val archives = entries.filter { it.kind == LanraragiEntry.Kind.ARCHIVE }
            val tanks = entries.filter { it.kind == LanraragiEntry.Kind.TANK }
            val categories = entries.filter { it.kind == LanraragiEntry.Kind.CATEGORY }
            assertTrue(archives.isNotEmpty())
            assertEquals(archives.size, result.itemCount)
            assertTrue(tanks.isNotEmpty())
            assertTrue(categories.isNotEmpty())
            val selected = archives.first { it.title.contains("Saturn", true) && it.pageCount in 1..10 }
            assertTrue(
                filterLanraragiCatalog(entries, emptyList(), LanraragiFilter(query = "Saturn", grouped = false)).any {
                    it.id ==
                        selected.id
                },
            )
            assertTrue(source.api.search("Saturn").any { it.id == selected.id })
            val tank = source.materialize(tanks.first())
            assertTrue(source.getChapterList(tank.toSManga()).isNotEmpty())
            val manga = source.materialize(selected)
            val sourceChapter = source.getChapterList(manga.toSManga()).single()
            Injekt.get<SyncChaptersWithSource>().await(listOf(sourceChapter), manga, source)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            val pages = source.getPageList(sourceChapter)
            assertEquals(selected.pageCount, pages.size)
            source.getImage(pages.first()).use { response ->
                val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            }
            preferences.activeConnectionId.set(profile.id)
            val readerPreferences = Injekt.get<koharia.connection.ConnectionScopedPreferenceStoreFactory>()
                .readerPreferences(source.id)
            val oldNewUserOverlay = readerPreferences.showNavigationOverlayNewUser.get()
            val oldStartupOverlay = readerPreferences.showNavigationOverlayOnStart.get()
            readerPreferences.showNavigationOverlayNewUser.set(false)
            readerPreferences.showNavigationOverlayOnStart.set(false)
            base.incognitoMode.set(true)
            try {
                ActivityScenario.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = source.id, pageIndex = 0),
                ).use { scenario ->
                    awaitPage(scenario, 0)
                    capture("reader")
                    for (index in listOf(1, pages.lastIndex)) {
                        scenario.onActivity { activity ->
                            val state = activity.viewModel.state.value
                            state.viewer!!.moveToPage(state.currentChapter!!.pages!![index])
                        }
                        awaitPage(scenario, index)
                    }
                    capture("reader-last")
                }
            } finally {
                readerPreferences.showNavigationOverlayNewUser.set(oldNewUserOverlay)
                readerPreferences.showNavigationOverlayOnStart.set(oldStartupOverlay)
            }
            assertTrue(
                "Incognito reading must not create connection progress",
                source.repository.readStates(source.id).isEmpty(),
            )
            ActivityScenario.launch(MainActivity::class.java).use {
                delay(12000)
                capture("library")
            }
            downloads.downloadChapters(manga, listOf(chapter))
            // The public demo can take over a minute per image body, plus queue startup.
            withTimeout(300_000) {
                while (!downloads.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        source.id,
                        skipCache = true,
                    )
                ) {
                    delay(500)
                }
            }
            // Close this connection's HTTP client before exercising the downloaded page loader.
            source.api.close()
            val loader =
                DownloadPageLoader(ReaderChapter(chapter), manga, source, downloads, Injekt.get<DownloadProvider>())
            try {
                val offline = loader.getPages()
                assertEquals(pages.size, offline.size)
                offline.first().stream!!.invoke().use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    bitmap.recycle()
                }
            } finally {
                loader.recycle()
            }
            assertFalse(source.repository.readStates(source.id).any { it.pending })
            println(
                "LANraragi live demo: version=${info.version}, archives=${archives.size}, tanks=${tanks.size}, categories=${categories.size}, pages=${pages.size}, serverProgress=${info.tracksProgress}",
            )
        } finally {
            downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
            source?.let { current ->
                mangas.getMangaBySourceId(profile.id).forEach { downloads.deleteManga(it, current) }
            }
            manager.remove(profile.id)
            mangas.deleteMangaBySourceId(profile.id)
            if (manager.profiles().any { it.id == previousActive }) preferences.activeConnectionId.set(previousActive)
            base.incognitoMode.set(previousIncognito)
            base.shownOnboardingFlow.set(previousOnboarding)
            downloadPreferences.downloadOnlyOverWifi.set(previousWifi)
            if (hadStorage) storage.baseStorageDirectory.set(previousStorage) else storage.baseStorageDirectory.delete()
            check(directory.parentFile == context.getExternalFilesDir(null))
            directory.deleteRecursively()
        }
    }

    private fun capture(name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/lanraragi-demo-$name.png",
        ).use { descriptor -> FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
    }

    private suspend fun awaitPage(scenario: ActivityScenario<ReaderActivity>, index: Int) {
        withTimeout(60_000) {
            var ready = false
            while (!ready) {
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val page = state.currentChapter?.pages?.getOrNull(index)
                    ready = page?.status == eu.kanade.tachiyomi.source.model.Page.State.Ready &&
                        state.currentPage == index + 1
                }
                if (!ready) delay(250)
            }
        }
        delay(1500)
    }
}
