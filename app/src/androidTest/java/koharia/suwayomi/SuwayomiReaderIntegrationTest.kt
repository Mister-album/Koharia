package koharia.suwayomi

import android.app.ActivityOptions
import android.graphics.BitmapFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiConnectionProvider
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Only the disposable localhost server is writable. Run with adb reverse and suwayomiFixtureUrl. */
@RunWith(AndroidJUnit4::class)
class SuwayomiReaderIntegrationTest {
    @Test
    fun onlineReadingLocalDownloadsOfflinePagesAndConnectionCleanup(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val address = InstrumentationRegistry.getArguments().getString("suwayomiFixtureUrl")
        assumeTrue(!address.isNullOrBlank())
        val url = SuwayomiApi.normalizeBase(checkNotNull(address))
        check(url.host == "127.0.0.1" && url.port == 14567)
        val manager: ConnectionProfileManager = Injekt.get()
        val connections: ConnectionPreferences = Injekt.get()
        val mangas: MangaRepository = Injekt.get()
        val chapters: ChapterRepository = Injekt.get()
        val downloads: DownloadManager = Injekt.get()
        val reader = Injekt.get<SharedAppPreferences>().readerPreferences()
        val base: BasePreferences = Injekt.get()
        val storage: StoragePreferences = Injekt.get()
        val downloadPreferences: DownloadPreferences = Injekt.get()
        assertTrue("Fixture queue must be idle", downloads.queueState.value.isEmpty())
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val saved = preference.get()
            val existed = preference.isSet()
            restores += { if (existed) preference.set(saved) else preference.delete() }
            preference.set(value)
        }
        val directory = File(context.getExternalFilesDir(null), "suwayomi-test-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val profile = manager.add(SuwayomiConnectionProvider.ID, "Suwayomi fixture")
        var source: SuwayomiSource? = null
        var account: String? = null
        try {
            SuwayomiPreferences(profile.id).save(address, "", SuwayomiAuthMode.NONE, "", "")
            val active = withTimeout(15_000) {
                while (true) {
                    val candidate = Injekt.get<SourceManager>().get(profile.id) as? SuwayomiSource
                    if (candidate != null) return@withTimeout candidate.also { it.reload() }
                    delay(100)
                }
                error("Unreachable")
            }
            source = active
            assertTrue(active.downloadDirectoryName().contains(profile.id.toString()))
            replace(base.shownOnboardingFlow, true)
            replace(base.incognitoMode, false)
            replace(base.downloadedOnly, false)
            replace(connections.activeConnectionId, profile.id)
            replace(storage.baseStorageDirectory, directory.toURI().toString())
            replace(downloadPreferences.downloadOnlyOverWifi, false)
            replace(reader.showNavigationOverlayNewUser, false)
            replace(reader.showNavigationOverlayOnStart, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            replace(reader.dualPageSplitPaged, false)
            replace(reader.dualPageSplitWebtoon, false)
            val session = active.session()
            account = session.identity.account
            session.api.validate()
            val shelf = session.catalog.shelf()
            val entry = shelf.mangas.single { it.title == "Koharia integration fixture" }
            val manga = active.materialize(active.toManga(entry))
            val remoteUnits = active.getChapterList(manga.toSManga())
            Injekt.get<SyncChaptersWithSource>().await(remoteUnits, manga, active)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            val remoteId = session.identity.chapter(chapter.url).second
            session.api.updateChapter(remoteId, 0, false)
            active.syncMangaProgress(manga)
            assertEquals(4, active.getPageList(remoteUnits.single()).size)
            println("Suwayomi fixture: shelf, database details and physical pages ready")
            assertFalse(
                downloads.isChapterDownloaded(chapter.name, chapter.scanlator, chapter.url, manga.title, active.id),
            )
            launchReader(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = active.id, pageIndex = 0),
            ).use { scenario ->
                awaitPage(scenario, 0)
                println("Suwayomi fixture: native reader first page ready")
                move(scenario, 1)
                awaitRemote(session.api, remoteId, session.reading) { it.lastPageRead == 1 }
                move(scenario, 3)
                awaitRemote(session.api, remoteId) { it.lastPageRead == 3 && it.isRead }
                move(scenario, 0)
                awaitRemote(session.api, remoteId) { it.lastPageRead == 0 && it.isRead }
            }
            launchReader(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = active.id, pageIndex = 0),
            ).use { scenario ->
                awaitPage(scenario, 0)
                scenario.onActivity { it.viewModel.setMangaReadingMode(ReadingMode.WEBTOON) }
                withTimeout(15_000) {
                    var webtoon = false
                    while (!webtoon) {
                        scenario.onActivity {
                            webtoon = it.viewModel.state.value.viewer is
                                eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
                        }
                        if (!webtoon) delay(100)
                    }
                }
                awaitPage(scenario, 0)
                move(scenario, 1)
                awaitRemote(session.api, remoteId, session.reading) { it.lastPageRead == 1 }
            }
            active.setChapterReadStatus(chapter.url, false)
            awaitRemote(session.api, remoteId) { !it.isRead && it.lastPageRead == 0 }
            println("Suwayomi fixture: progress and explicit unread passed")
            downloads.downloadChapters(manga, listOf(chapter))
            withTimeout(120_000) {
                while (!downloads.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        active.id,
                        skipCache = true,
                    )
                ) {
                    delay(250)
                }
            }
            session.api.close()
            println("Suwayomi fixture: manual download completed; API closed")
            assertEquals(shelf, SuwayomiCatalog(session.identity, Injekt.get(), session.api, Injekt.get(), {}).shelf())
            val loader = DownloadPageLoader(
                ReaderChapter(chapter),
                manga,
                active,
                downloads,
                Injekt.get<DownloadProvider>(),
            )
            try {
                val pages = loader.getPages()
                assertEquals(4, pages.size)
                pages.forEach { page ->
                    page.stream!!.invoke().use {
                        val bitmap = checkNotNull(BitmapFactory.decodeStream(it))
                        assertTrue(bitmap.width > 0)
                        bitmap.recycle()
                    }
                }
            } finally {
                loader.recycle()
            }
            launchReader(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = active.id, pageIndex = 0),
            ).use { scenario ->
                awaitPage(scenario, 0)
                scenario.onActivity {
                    assertTrue(it.viewModel.state.value.currentChapter!!.pageLoader is DownloadPageLoader)
                }
                move(scenario, 3)
            }
            println("Suwayomi fixture: native reader, progress, four physical download pages, offline reader passed")
        } finally {
            withContext(NonCancellable) {
                val failures = mutableListOf<Throwable>()
                suspend fun clean(block: suspend () -> Unit) {
                    runCatching { block() }.onFailure { failures += it }
                }
                clean {
                    source?.let { current ->
                        downloads.cancelQueuedDownloads(
                            downloads.queueState.value.filter { it.source.id == profile.id },
                        )
                        mangas.getMangaBySourceId(profile.id).forEach { downloads.deleteManga(it, current) }
                    }
                }
                source?.close()
                clean { manager.remove(profile.id).getOrThrow() }
                clean { assertTrue(source?.downloadDirectoryNames()?.isNotEmpty() == true) }
                clean {
                    assertTrue(Injekt.get<SuwayomiRepository>().operations(profile.id, account.orEmpty()).isEmpty())
                    assertTrue(context.getSharedPreferences("source_${profile.id}", 0).all.isEmpty())
                }
                clean { mangas.deleteMangaBySourceId(profile.id) }
                restores.asReversed().forEach { restore -> clean { restore() } }
                clean {
                    check(directory.canonicalFile.parentFile == context.getExternalFilesDir(null)!!.canonicalFile)
                    directory.deleteRecursively()
                }
                assertTrue("Fixture cleanup must succeed", failures.isEmpty())
            }
        }
    }

    private fun launchReader(intent: android.content.Intent): ActivityScenario<ReaderActivity> =
        if (InstrumentationRegistry.getArguments().getString("suwayomiForegroundFixture") == "true") {
            FixtureActivityLauncher.launch(intent)
        } else {
            ActivityScenario.launch(intent, ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle())
        }

    private suspend fun move(scenario: ActivityScenario<ReaderActivity>, index: Int) {
        scenario.onActivity { activity ->
            val state = activity.viewModel.state.value
            state.viewer!!.moveToPage(state.currentChapter!!.pages!![index])
        }
        awaitPage(scenario, index)
    }

    private suspend fun awaitPage(scenario: ActivityScenario<ReaderActivity>, index: Int) = withTimeout(60_000) {
        var ready = false
        while (!ready) {
            scenario.onActivity { activity ->
                val state = activity.viewModel.state.value
                ready = state.currentChapter?.pages?.getOrNull(index)?.status == Page.State.Ready &&
                    state.currentPage == index + 1
            }
            if (!ready) delay(100)
        }
        delay(500)
    }

    private suspend fun awaitRemote(
        api: SuwayomiApi,
        id: Int,
        reading: SuwayomiReadingCoordinator? = null,
        predicate: (SuwayomiChapter) -> Boolean,
    ) {
        val completed = withTimeoutOrNull(30_000) {
            while (!predicate(api.chapter(id))) delay(250)
            true
        }
        assertTrue("Remote=${api.chapter(id)}, pending=${reading?.operation(id)}", completed == true)
    }
}
