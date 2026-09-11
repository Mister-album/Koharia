package koharia.lanraragi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.domain.lanraragi.LanraragiEntry
import koharia.lanraragi.ui.LanraragiPreviewImage
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

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
                sync.await(source.getChapterList(standalone.toSManga()), standalone, source)
                sync.await(source.getChapterList(tank.toSManga()), tank, source)
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
                koharia.connection.ConnectionRestoreState.duringRestore {
                    source.prepareReadingStateRestore(listOf(chapter.url))
                    source.recordLocalPageProgress(chapter.url, 2, 3, System.currentTimeMillis())
                    assertTrue(source.repository.readStates(source.id).isEmpty())
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
    fun downloadedPagesRemainReadableWhenServerIsUnavailable() = runBlocking(Dispatchers.IO) {
        assertTrue("Fixture requires an empty download queue", downloads.queueState.value.isEmpty())
        val storage: StoragePreferences = Injekt.get()
        val downloadPreferences: tachiyomi.domain.download.service.DownloadPreferences = Injekt.get()
        val oldWifiOnly = downloadPreferences.downloadOnlyOverWifi.get()
        val oldSplitTall = downloadPreferences.splitTallImages.get()
        downloadPreferences.downloadOnlyOverWifi.set(false)
        downloadPreferences.splitTallImages.set(true)
        val hadStorage = storage.baseStorageDirectory.isSet()
        val oldStorage = storage.baseStorageDirectory.get()
        val directory = File(context.getExternalFilesDir(null), "lanraragi-download-fixture-${System.nanoTime()}")
        directory.mkdirs()
        storage.baseStorageDirectory.set(directory.toURI().toString())
        try {
            withConnection("v80") { source ->
                val entry = source.repository.entries(source.id).first { it.kind == LanraragiEntry.Kind.ARCHIVE }
                val manga = source.materialize(entry)
                Injekt.get<SyncChaptersWithSource>().await(source.getChapterList(manga.toSManga()), manga, source)
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
                    // Force the same Coil fetcher used by the grid to reopen the downloaded CBZ,
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
                control("v80")
                captureLibrary(source)
            }
        } finally {
            downloadPreferences.downloadOnlyOverWifi.set(oldWifiOnly)
            downloadPreferences.splitTallImages.set(oldSplitTall)
            if (hadStorage) storage.baseStorageDirectory.set(oldStorage) else storage.baseStorageDirectory.delete()
            check(directory.parentFile == context.getExternalFilesDir(null))
            directory.deleteRecursively()
        }
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
