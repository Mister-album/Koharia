package koharia.lanraragi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionProgressSyncStatus
import koharia.connection.ConnectionReadStatus
import koharia.connection.SharedAppPreferences
import koharia.domain.lanraragi.LanraragiEntry
import koharia.lanraragi.ui.LanraragiPreviewImage
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

/** Requires tools/lanraragi/fixture_server.py and adb reverse tcp:38709 tcp:38709. */
@RunWith(AndroidJUnit4::class)
class LanraragiConnectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val chapters: ChapterRepository = Injekt.get()
    private val mangas: MangaRepository = Injekt.get()
    private val downloads: DownloadManager = Injekt.get()

    private fun control(version: String, offline: Boolean = false, reset: Boolean = false) {
        val url =
            "http://127.0.0.1:38709/_fixture/control?version=$version&offline=$offline" +
                if (reset) "&reset=true" else ""
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { assertTrue(it.isSuccessful) }
    }

    private suspend fun withConnection(version: String, block: suspend (LanraragiSource) -> Unit) {
        control(version, reset = true)
        val manager: ConnectionProfileManager = Injekt.get()
        val connectionPreferences: ConnectionPreferences = Injekt.get()
        val previousActive = connectionPreferences.activeConnectionId.get()
        val profile = manager.add(LanraragiConnectionProvider.ID, "LANraragi fixture $version")
        LanraragiPreferences(profile.id).save("http://127.0.0.1:38709/$version/lrr/", "fixture-key")
        var source: LanraragiSource? = null
        try {
            source = withTimeout(10_000) {
                var registered: LanraragiSource?
                do {
                    registered = Injekt.get<SourceManager>().get(profile.id) as? LanraragiSource
                    if (registered == null) delay(100)
                } while (registered == null)
                registered
            }
            source.refreshLibrary().getOrThrow()
            block(source)
        } finally {
            control(version)
            downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
            if (source != null) mangas.getMangaBySourceId(profile.id).forEach { downloads.deleteManga(it, source) }
            manager.remove(profile.id).getOrThrow()
            mangas.deleteMangaBySourceId(profile.id)
            if (manager.profiles().any {
                    it.id == previousActive
                }
            ) {
                connectionPreferences.activeConnectionId.set(previousActive)
            }
        }
    }

    @Test
    fun shelfProgressPreservesPendingConflictsUntilReaderChoice() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        withConnection("v80_test_progress_choice") { source ->
            val entry = source.repository.entries(source.id).first { it.kind == LanraragiEntry.Kind.ARCHIVE }
            val manga = source.materialize(entry)
            Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            val catalogTime = source.repository.lastSync(source.id)
            source.api.pushProgress(entry.id, 2)
            source.refreshShelfProgress()
            assertEquals(1L, chapters.getChapterByMangaId(manga.id).single().lastPageRead)
            assertEquals(catalogTime, source.repository.lastSync(source.id))

            val remote = source.api.archive(entry.id)
            val pending = koharia.domain.lanraragi.LanraragiReadState(entry.id, 0, 3, remote.lastRead - 1000)
            source.repository.record(source.id, pending)
            source.refreshShelfProgress()
            assertTrue(source.repository.readStates(source.id).single { it.archiveId == entry.id }.pending)
            val conflict = checkNotNull(source.pullPageProgress(chapter.url, chapter.memo))
            assertTrue(conflict.requiresConfirmation)
            assertEquals(1, conflict.pageIndex)
            assertEquals(0, source.repository.readStates(source.id).single { it.archiveId == entry.id }.pageIndex)

            source.acceptRemotePageProgress(chapter.url, 1, 3, remote.lastRead)
            assertFalse(source.repository.readStates(source.id).single { it.archiveId == entry.id }.pending)
            assertFalse(checkNotNull(source.pullPageProgress(chapter.url, chapter.memo)).requiresConfirmation)
            source.repository.record(source.id, pending.copy(readAt = remote.lastRead + 1000))
            val local = checkNotNull(source.pullPageProgress(chapter.url, chapter.memo))
            assertFalse(local.requiresConfirmation)
            assertEquals(0, local.pageIndex)
        }
    }

    @Test
    fun readerAppliesRemoteProgressBeforeLoadingItsFirstImage() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        withConnection("v80_test_progress_choice") { source ->
            val entry = source.repository.entries(source.id).first { it.kind == LanraragiEntry.Kind.ARCHIVE }
            val manga = source.materialize(entry)
            Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            source.api.pushProgress(entry.id, 2)
            Injekt.get<koharia.connection.SharedAppPreferences>()
                .readerPreferences()
                .apply {
                    showNavigationOverlayNewUser.set(false)
                    showNavigationOverlayOnStart.set(false)
                }

            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = source.id),
            ).use { scenario ->
                withTimeout(20_000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity { activity ->
                            val state = activity.viewModel.state.value
                            ready = state.currentPage == 2 &&
                                state.currentChapter?.pages?.getOrNull(1)?.status ==
                                eu.kanade.tachiyomi.source.model.Page.State.Ready
                        }
                        if (ready) break
                        delay(100)
                    }
                }
            }
        }
    }

    @Test
    fun firstDisplayedUnreadArchiveClearsNewAfterProgressPrecheck() = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        withConnection("v80") { source ->
            val entry = source.repository.entries(source.id).first { it.kind == LanraragiEntry.Kind.ARCHIVE }
            val manga = source.materialize(entry)
            Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            assertTrue(source.api.archive(entry.id).isNew)
            Injekt.get<koharia.connection.SharedAppPreferences>()
                .readerPreferences()
                .apply {
                    showNavigationOverlayNewUser.set(false)
                    showNavigationOverlayOnStart.set(false)
                }

            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = source.id),
            ).use { scenario ->
                withTimeout(20_000) {
                    while (true) {
                        var ready = false
                        scenario.onActivity { activity ->
                            val state = activity.viewModel.state.value
                            ready = state.currentPage == 1 &&
                                state.currentChapter?.pages?.firstOrNull()?.status ==
                                eu.kanade.tachiyomi.source.model.Page.State.Ready
                        }
                        if (ready) break
                        delay(100)
                    }
                }
                withTimeout(20_000) {
                    while (source.api.archive(entry.id).isNew) delay(250)
                }
            }
        }
    }

    @Test
    fun bothVersionsSupportCompleteIndexesAndSharedArchiveProgress() = runBlocking(Dispatchers.IO) {
        for (version in listOf("v70", "v80")) {
            withConnection(version) { source ->
                val entries = source.repository.entries(source.id)
                assertEquals(5, entries.count { it.kind == LanraragiEntry.Kind.ARCHIVE })
                assertEquals(3, entries.first { it.id == "SET_1234567891" }.members.size)
                val archive = entries.first { it.kind == LanraragiEntry.Kind.ARCHIVE }
                val standalone = source.materialize(archive)
                val tank = source.materialize(entries.first { it.id == "TANK_1234567891" })
                val sync: SyncChaptersWithSource = Injekt.get()
                sync.fetchAndSync(standalone, source, ChapterFetchOrigin.OPEN)
                sync.fetchAndSync(tank, source, ChapterFetchOrigin.OPEN)
                val chapter = chapters.getChapterByMangaId(standalone.id).single()
                assertEquals(2, chapters.getChaptersByUrlAndSourceId(chapter.url, source.id).size)
                val pages = source.getPageList(source.getChapterList(standalone.toSManga()).single())
                assertEquals(3, pages.size)
                source.getImage(pages[0]).use { response ->
                    val bitmap = BitmapFactory.decodeStream(response.body.byteStream())
                    assertEquals(64, bitmap.width)
                    assertEquals(96, bitmap.height)
                    bitmap.recycle()
                }
                assertTrue(source.api.archive(archive.id).isNew)
                source.recordLocalPageProgress(chapter.url, 1, 3, System.currentTimeMillis())
                source.syncConnectionHistory()
                assertEquals(2, source.api.archive(archive.id).progress)
                assertFalse(source.api.archive(archive.id).isNew)
                assertTrue(chapters.getChaptersByUrlAndSourceId(chapter.url, source.id).all { it.lastPageRead == 1L })
                source.setChapterReadStatus(chapter.url, false)
                assertTrue(source.repository.readStates(source.id).first { it.archiveId == archive.id }.localUnread)
                assertEquals(2, source.api.archive(archive.id).progress)
                assertTrue(
                    chapters.getChaptersByUrlAndSourceId(chapter.url, source.id).all {
                        !it.read &&
                            LanraragiSource.UNREAD_AT in it.memo
                    },
                )
                val beforeRestore = source.repository.readStates(source.id)
                koharia.connection.ConnectionRestoreState.duringRestore {
                    source.prepareReadingStateRestore(listOf(chapter.url))
                    source.recordLocalPageProgress(chapter.url, 2, 3, System.currentTimeMillis())
                    assertEquals(beforeRestore, source.repository.readStates(source.id))
                }
                val lastSync = source.repository.lastSync(source.id)
                val savedEntries = source.repository.entries(source.id)
                control(version, offline = true)
                assertTrue(source.refreshLibrary().isFailure)
                assertEquals(savedEntries, source.repository.entries(source.id))
                assertEquals(lastSync, source.repository.lastSync(source.id))
                control(version)
            }
        }
    }

    @Test
    fun downloadedPagesRemainReadableWhenServerIsUnavailable() = verifyOfflineDownload(saveAsCbz = true)

    @Test
    fun downloadedDirectoryRemainsReadableWhenServerIsUnavailable() = verifyOfflineDownload(saveAsCbz = false)

    @Test
    fun downloadedOfflineMarkReopenAndHistoryPreserveProgress() =
        verifyOfflineDownload(saveAsCbz = true, progressJourney = true)

    @Test
    fun restoreOfflineDownloadPreferencesFromCheckpoint() {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val encoded = InstrumentationRegistry.getArguments().getString("offlineRecoveryCheckpoint")
        org.junit.Assume.assumeTrue("Requires an explicitly retained fixture checkpoint", encoded != null)
        val checkpoint = org.json.JSONObject(
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP).decodeToString(),
        )
        check(checkpoint.getString("root").matches(Regex("lanraragi-download-fixture-[0-9]+")))
        val preferences = Injekt.get<tachiyomi.domain.download.service.DownloadPreferences>()
        for ((key, preference) in listOf(
            "wifi" to preferences.downloadOnlyOverWifi,
            "split" to preferences.splitTallImages,
            "cbz" to preferences.saveChaptersAsCBZ,
        )) {
            check(preference.get() == checkpoint.getBoolean(key) || preference.get() == (key == "cbz"))
            if (checkpoint.getBoolean("${key}_set")) {
                preference.set(checkpoint.getBoolean(key))
            } else {
                preference.delete()
            }
            assertEquals(checkpoint.getBoolean("${key}_set"), preference.isSet())
            assertEquals(checkpoint.getBoolean(key), preference.get())
        }
        InstrumentationRegistry.getArguments().getString("offlineRecoveryStorage")?.let { encodedStorage ->
            val storage = android.util.Base64.decode(encodedStorage, android.util.Base64.NO_WRAP).decodeToString()
            val original = File(java.net.URI(storage)).canonicalFile
            check(original.parentFile == context.cacheDir.canonicalFile)
            check(original.name.matches(Regex("offline-reader-[0-9a-f-]{36}")))
            Injekt.get<StoragePreferences>().baseStorageDirectory.set(storage)
        }
        assertTrue(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
    }

    private fun verifyOfflineDownload(
        saveAsCbz: Boolean,
        progressJourney: Boolean = false,
    ) = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        assertTrue("Fixture requires an empty download queue", downloads.queueState.value.isEmpty())
        val storage: StoragePreferences = Injekt.get()
        val downloadPreferences: tachiyomi.domain.download.service.DownloadPreferences = Injekt.get()
        val hadWifiOnly = downloadPreferences.downloadOnlyOverWifi.isSet()
        val hadSplitTall = downloadPreferences.splitTallImages.isSet()
        val hadSaveAsCbz = downloadPreferences.saveChaptersAsCBZ.isSet()
        val oldWifiOnly = downloadPreferences.downloadOnlyOverWifi.get()
        val oldSplitTall = downloadPreferences.splitTallImages.get()
        val oldSaveAsCbz = downloadPreferences.saveChaptersAsCBZ.get()
        val hadStorage = storage.baseStorageDirectory.isSet()
        val oldStorage = storage.baseStorageDirectory.get()
        val directory = File(context.getExternalFilesDir(null), "lanraragi-download-fixture-${System.nanoTime()}")
        check(directory.canonicalFile.parentFile == context.getExternalFilesDir(null)!!.canonicalFile)
        check(directory.mkdirs())
        try {
            val checkpoint = org.json.JSONObject().apply {
                put("root", directory.name)
                put("wifi", oldWifiOnly)
                put("wifi_set", hadWifiOnly)
                put("split", oldSplitTall)
                put("split_set", hadSplitTall)
                put("cbz", oldSaveAsCbz)
                put("cbz_set", hadSaveAsCbz)
            }.toString()
            // Only non-sensitive booleans: retain a recovery baseline before any preference mutation.
            File(directory, "offline-checkpoint.json").outputStream().use {
                it.write(checkpoint.toByteArray())
                it.fd.sync()
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("offline_checkpoint", checkpoint) })
            downloadPreferences.downloadOnlyOverWifi.set(false)
            downloadPreferences.splitTallImages.set(!progressJourney)
            downloadPreferences.saveChaptersAsCBZ.set(saveAsCbz)
            storage.baseStorageDirectory.set(directory.toURI().toString())
            val storageManager = Injekt.get<tachiyomi.domain.storage.service.StorageManager>()
            withTimeout(10_000) {
                while (storageManager.getDownloadsDirectory()?.uri?.path != File(directory, "downloads").path) {
                    delay(50)
                }
            }
            withConnection("v80") { source ->
                val entry = source.repository.entries(source.id).first { it.kind == LanraragiEntry.Kind.ARCHIVE }
                val manga = source.materialize(entry)
                Injekt.get<SyncChaptersWithSource>().fetchAndSync(manga, source, ChapterFetchOrigin.OPEN)
                val chapter = chapters.getChapterByMangaId(manga.id).single()
                downloads.downloadChapters(manga, listOf(chapter))
                withTimeout(45_000) {
                    while (!downloads.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.title,
                            source.id,
                            skipCache = true,
                        )
                    ) {
                        val queued = downloads.queueState.value.firstOrNull { it.chapter.id == chapter.id }
                        queued?.pages?.firstNotNullOfOrNull {
                            (it.status as? eu.kanade.tachiyomi.source.model.Page.State.Error)?.error
                        }?.let { throw AssertionError("Fixture page download failed", it) }
                        check(queued?.status != eu.kanade.tachiyomi.data.download.model.Download.State.ERROR) {
                            "Fixture download failed: pages=${queued?.pages?.map { it.status }}; " +
                                "pageUris=${queued?.pages?.map { it.uri }}; " +
                                "files=${directory.walkTopDown().filter {
                                    it.isFile
                                }.map { it.relativeTo(directory) }.toList()}"
                        }
                        delay(250)
                    }
                }
                assertEquals(0, source.api.archive(entry.id).progress)
                control("v80", offline = true)
                val loader =
                    DownloadPageLoader(ReaderChapter(chapter), manga, source, downloads, Injekt.get<DownloadProvider>())
                try {
                    val pages = loader.getPages()
                    assertEquals(3, pages.size)
                    val colors = pages.map { page ->
                        loader.loadPage(page)
                        checkNotNull(page.stream).invoke().use { stream ->
                            BitmapFactory.decodeStream(stream).let { image ->
                                image.getPixel(0, 0).also { image.recycle() }
                            }
                        }
                    }
                    assertEquals(3, colors.distinct().size)
                    // Force the same Coil fetcher used by the grid to reopen the downloaded entry,
                    // without network or memory-cache hits; repeated concurrent decodes check stream cleanup.
                    repeat(2) {
                        val previews = withTimeout(20_000) {
                            coroutineScope {
                                pages.map { page ->
                                    async {
                                        val request = ImageRequest.Builder(context)
                                            .data(LanraragiPreviewImage(manga, chapter, page, downloaded = true))
                                            .memoryCachePolicy(CachePolicy.DISABLED)
                                            .diskCachePolicy(CachePolicy.DISABLED)
                                            .allowHardware(false)
                                            .size(64, 96)
                                            .build()
                                        val result = context.imageLoader.execute(request)
                                        assertTrue("Offline preview decoding failed: $result", result is SuccessResult)
                                        (result as SuccessResult).image.asDrawable(
                                            context.resources,
                                        ).toBitmap().let { image ->
                                            image.getPixel(0, 0).also { image.recycle() }
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                        assertEquals(colors, previews)
                    }
                } finally {
                    loader.recycle()
                }
                if (progressJourney) {
                    verifyOfflineProgressJourney(source, manga, chapter, entry.id, directory)
                } else {
                    control("v80")
                    captureLibrary(source)
                }
            }
        } finally {
            if (hadWifiOnly) {
                downloadPreferences.downloadOnlyOverWifi.set(oldWifiOnly)
            } else {
                downloadPreferences.downloadOnlyOverWifi.delete()
            }
            if (hadSplitTall) {
                downloadPreferences.splitTallImages.set(oldSplitTall)
            } else {
                downloadPreferences.splitTallImages.delete()
            }
            if (hadSaveAsCbz) {
                downloadPreferences.saveChaptersAsCBZ.set(oldSaveAsCbz)
            } else {
                downloadPreferences.saveChaptersAsCBZ.delete()
            }
            if (hadStorage) storage.baseStorageDirectory.set(oldStorage) else storage.baseStorageDirectory.delete()
            // Instrumentation can terminate before asynchronous preference restoration reaches disk.
            check(androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
            assertEquals(oldWifiOnly, downloadPreferences.downloadOnlyOverWifi.get())
            assertEquals(oldSplitTall, downloadPreferences.splitTallImages.get())
            assertEquals(oldSaveAsCbz, downloadPreferences.saveChaptersAsCBZ.get())
            check(directory.parentFile == context.getExternalFilesDir(null))
            check(directory.deleteRecursively())
            if (progressJourney) {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("offline_cleanup", "owned-profile-download-root-removed;preferences-restored")
                    },
                )
            }
        }
    }

    private suspend fun verifyOfflineProgressJourney(
        source: LanraragiSource,
        manga: Manga,
        chapter: Chapter,
        archiveId: String,
        directory: File,
    ) {
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val old = preference.get()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val shared = Injekt.get<SharedAppPreferences>()
        val connections = Injekt.get<ConnectionPreferences>()
        val history = Injekt.get<HistoryRepository>()
        val deletion = context.getSharedPreferences("chapters_to_delete", android.content.Context.MODE_PRIVATE)
        check(deletion.all.isEmpty()) { "Existing deletion intentions must remain untouched" }
        val originalBytes = directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).path to MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
        }
        suspend fun await(phase: String, condition: suspend () -> Boolean) {
            instrumentation.sendStatus(2, Bundle().apply { putString("offline_journey", phase) })
            withTimeout(30_000) { while (!condition()) delay(50) }
        }
        suspend fun status(expected: ConnectionProgressSyncStatus) {
            withTimeout(10_000) {
                source.observeProgressSyncStatus(listOf(chapter.url)).first {
                    it[chapter.url] == setOf(expected)
                }
            }
        }
        suspend fun page(activity: ReaderActivity, index: Int) = await("rendered-page-$index") {
            withContext(Dispatchers.Main) {
                val state = activity.viewModel.state.value
                state.manga?.id == manga.id && state.manga.source == source.id &&
                    state.currentChapter?.chapter?.id == chapter.id &&
                    state.currentChapter?.pageLoader is DownloadPageLoader &&
                    state.currentPage == index + 1 && state.visiblePageStart == index + 1 &&
                    state.visiblePageEnd == index + 1 &&
                    state.currentChapter?.pages?.get(index)?.status == Page.State.Ready &&
                    readerViews(activity.window.decorView).filterIsInstance<SubsamplingScaleImageView>().any {
                        it.isReady
                    } &&
                    activity.hasWindowFocus()
            }
        }
        suspend fun exit(activity: ReaderActivity) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("reader-destroyed") { withContext(Dispatchers.Main) { activity.isDestroyed } }
        }
        suspend fun saved() = await("sql-pending-page-1-history") {
            val state = source.repository.readState(source.id, archiveId)
            state?.pageIndex == 1 && state.totalPages == 3 && state.pending && !state.localUnread &&
                chapters.getChapterById(chapter.id)?.let { !it.read && it.lastPageRead == 1L } == true &&
                history.getHistoryByMangaId(manga.id).any { it.chapterId == chapter.id && it.readAt != null }
        }
        try {
            replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
            replace(connections.activeConnectionId, source.id)
            replace(shared.basePreferences().incognitoMode, false)
            replace(shared.basePreferences().downloadedOnly, false)
            replace(shared.downloadPreferences().removeAfterReadSlots, -1)
            replace(shared.downloadPreferences().removeAfterMarkedAsRead, false)
            replace(shared.downloadPreferences().autoDownloadWhileReading, 0)
            replace(shared.downloadPreferences().cacheCurrentChapterWhileReading, false)
            val reader = shared.readerPreferences()
            replace(reader.showNavigationOverlayNewUser, false)
            replace(reader.showNavigationOverlayOnStart, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            replace(reader.dualPageSplitPaged, false)
            replace(reader.dualPageSplitWebtoon, false)
            val marker = Injekt.get<SetReadStatus>()
            assertEquals(
                SetReadStatus.Result.Completed(mapOf(chapter.id to ConnectionReadStatus.PENDING)),
                marker.await(true, chapters.getChapterById(chapter.id)!!),
            )
            assertTrue(chapters.getChapterById(chapter.id)!!.read)
            assertEquals(2, source.repository.readState(source.id, archiveId)!!.pageIndex)
            status(ConnectionProgressSyncStatus.PENDING)
            source.reload()
            assertTrue(source.repository.readState(source.id, archiveId)!!.pending)
            assertEquals(
                SetReadStatus.Result.Completed(mapOf(chapter.id to ConnectionReadStatus.LOCAL_ONLY)),
                marker.await(false, chapters.getChapterById(chapter.id)!!),
            )
            assertFalse(chapters.getChapterById(chapter.id)!!.read)
            assertTrue(source.repository.readState(source.id, archiveId)!!.localUnread)
            status(ConnectionProgressSyncStatus.LOCAL_ONLY)
            assertTrue(history.getHistoryByMangaId(manga.id).none { it.readAt != null })
            source.reload()
            assertTrue(source.repository.readState(source.id, archiveId)!!.localUnread)
            repeat(2) { opening ->
                FixtureActivityLauncher.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = source.id),
                ).use { scenario ->
                    lateinit var activity: ReaderActivity
                    scenario.onActivity { activity = it }
                    page(activity, if (opening == 0) 0 else 1)
                    if (opening == 0) {
                        withContext(Dispatchers.Main) {
                            val state = activity.viewModel.state.value
                            state.viewer!!.moveToPage(state.currentChapter!!.pages!![1])
                        }
                        page(activity, 1)
                    }
                    exit(activity)
                }
                saved()
                status(ConnectionProgressSyncStatus.PENDING)
                source.reload()
            }
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { TachiyomiTheme { Navigator(HomeScreen) } } }
                withTimeout(10_000) { HomeScreen.openTab(HomeScreen.Tab.History) }
                val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
                var opened: ReaderActivity? = null
                try {
                    await("history-row-click") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            instrumentation.uiAutomation.clearCache()
                        }
                        var target = historyNodes(instrumentation.uiAutomation.rootInActiveWindow)
                            .firstOrNull { it.isVisibleToUser && it.text?.toString() == manga.title }
                        while (target != null && !target.isClickable) target = target.parent
                        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    }
                    opened = checkNotNull(monitor.waitForActivityWithTimeout(30_000) as? ReaderActivity)
                    page(opened, 1)
                    assertEquals(source.id, connections.activeConnectionId.get())
                    exit(opened)
                } finally {
                    opened?.let { activity ->
                        withContext(Dispatchers.Main) { if (!activity.isDestroyed) activity.finish() }
                    }
                    instrumentation.removeMonitor(monitor)
                }
            }
            saved()
            status(ConnectionProgressSyncStatus.PENDING)
            assertTrue(deletion.all.isEmpty())
            for ((path, bytes) in originalBytes) {
                val current = MessageDigest.getInstance("SHA-256").digest(File(directory, path).readBytes()).toList()
                assertEquals(bytes, current)
            }
            control("v80")
            assertEquals(0, source.api.archive(archiveId).progress)
            source.syncConnectionHistory()
            await("remote-progress-confirmed") { source.repository.readState(source.id, archiveId)?.pending == false }
            assertEquals(2, source.api.archive(archiveId).progress)
            assertFalse(source.api.archive(archiveId).isNew)
            assertEquals(1, source.repository.readState(source.id, archiveId)!!.pageIndex)
            withTimeout(10_000) {
                source.observeProgressSyncStatus(listOf(chapter.url)).first { it[chapter.url].isNullOrEmpty() }
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "offline_journey",
                        "mark-read-unread;reopen;history;bytes-retained;remote-page-2-confirmed",
                    )
                },
            )
        } finally {
            restores.asReversed().forEach { it() }
            instrumentation.sendStatus(2, Bundle().apply { putString("offline_journey", "preferences-restored") })
        }
    }

    private fun readerViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (child in view.children) yieldAll(readerViews(child))
    }

    private fun historyNodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        if (root == null) return@buildList
        add(root)
        for (index in 0 until root.childCount) addAll(historyNodes(root.getChild(index)))
    }

    private suspend fun captureLibrary(source: LanraragiSource) {
        val preferences: ConnectionPreferences = Injekt.get()
        val base: BasePreferences = Injekt.get()
        val shown = base.shownOnboardingFlow.get()
        val previousActive = preferences.activeConnectionId.get()
        preferences.activeConnectionId.set(source.id)
        base.shownOnboardingFlow.set(true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                delay(2500)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "lanraragi-test-library.png").outputStream().use { output ->
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                screenshot.recycle()
                instrumentation.uiAutomation.executeShellCommand(
                    "screencap -p /data/local/tmp/lanraragi-test-library.png",
                ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
            }
        } finally {
            preferences.activeConnectionId.set(previousActive)
            base.shownOnboardingFlow.set(shown)
        }
    }
}
