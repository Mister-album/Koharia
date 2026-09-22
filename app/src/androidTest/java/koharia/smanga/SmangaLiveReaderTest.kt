package koharia.smanga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.smanga.SmangaRepository
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in dedicated-server test. Only chapters with no prior progress/history may be modified. */
@RunWith(AndroidJUnit4::class)
class SmangaLiveReaderTest {
    @Test
    fun nativeComicPdfDownloadsAndProgress(): Unit = runBlocking(Dispatchers.IO) {
        val fixture = SmangaLiveFixture()
        val context = fixture.context
        val api = fixture.createApi("live-reader-discovery")
        val remote = OwnedRemoteState(fixture.config)
        val manager: ConnectionProfileManager = Injekt.get()
        val preferences: ConnectionPreferences = Injekt.get()
        val base: BasePreferences = Injekt.get()
        val storage: StoragePreferences = Injekt.get()
        val downloadPreferences: DownloadPreferences = Injekt.get()
        val downloads: DownloadManager = Injekt.get()
        val mangas: MangaRepository = Injekt.get()
        val reader = Injekt.get<SharedAppPreferences>().readerPreferences()
        assertTrue("An idle fixture download queue is required", downloads.queueState.value.isEmpty())
        assertTrue(
            "An empty fixture pending-deletion queue is required",
            context.getSharedPreferences("chapters_to_delete", Context.MODE_PRIVATE).all.isEmpty(),
        )
        val preferenceRestores = mutableListOf<() -> Unit>()
        fun <T> overridePreference(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            preferenceRestores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val previousActive = preferences.activeConnectionId.get()
        val previousIncognito = base.incognitoMode.get()
        val previousOnboarding = base.shownOnboardingFlow.get()
        val previousWifi = downloadPreferences.downloadOnlyOverWifi.get()
        val previousStorage = storage.baseStorageDirectory.get()
        val hadStorage = storage.baseStorageDirectory.isSet()
        val oldNewUserOverlay = reader.showNavigationOverlayNewUser.get()
        val oldStartupOverlay = reader.showNavigationOverlayOnStart.get()
        val directory = File(context.getExternalFilesDir(null), "smanga-live-download-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val profile = manager.add(SmangaConnectionProvider.ID, "smanga live validation")
        var source: SmangaSource? = null
        try {
            val account = api.validate()
            SmangaPreferences(profile.id).save(
                fixture.config.address,
                fixture.config.username,
                fixture.config.password,
                account.id,
            )
            source = withTimeout(15_000) {
                var current: SmangaSource?
                do {
                    current = Injekt.get<SourceManager>().get(profile.id) as? SmangaSource
                    if (current == null) delay(100)
                } while (current == null)
                current
            }.also { it.reload() }
            val active = source
            base.incognitoMode.set(false)
            base.shownOnboardingFlow.set(true)
            downloadPreferences.downloadOnlyOverWifi.set(false)
            storage.baseStorageDirectory.set(directory.toURI().toString())
            reader.showNavigationOverlayNewUser.set(false)
            reader.showNavigationOverlayOnStart.set(false)
            overridePreference(base.downloadedOnly, false)
            overridePreference(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            overridePreference(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
            overridePreference(reader.dualPageSplitPaged, false)
            overridePreference(reader.dualPageSplitWebtoon, false)
            preferences.activeConnectionId.set(profile.id)
            val library = active.session().catalog.media().first()
            val shelf = active.session().catalog.page(library.id, "", "mangaName asc", 1)
            assertTrue(shelf.data.isNotEmpty())
            val candidates = shelf.data.flatMap { entry -> api.chapters(entry.id).map { entry to it } }
                .filter { it.second.latest == null }
            val comic = candidates.first { it.second.format in setOf("zip", "rar", "7z", "directory", "folder") }
            val pdf = candidates.first { it.second.format == "pdf" }
            val selected = listOf(comic, pdf)
            selected.forEach { (_, chapter) ->
                check(api.chapters(chapter.mangaId).single { it.id == chapter.id }.latest == null)
                remote.claim(chapter.id)
            }

            ActivityScenario.launch(MainActivity::class.java).use {
                delay(5000)
                capture("library")
            }
            val downloaded = mutableListOf<Triple<Manga, Chapter, Int>>()
            for ((entry, descriptor) in selected) {
                downloaded += exerciseChapter(active, entry, descriptor, api, remote)
            }
            // Closing only this test connection proves completed downloads need no server calls.
            active.session().api.close()
            for (download in downloaded) assertOfflineDownload(active, download)
        } finally {
            source?.close()
            api.close()
            withContext(NonCancellable) {
                val cleanupErrors = mutableListOf<Throwable>()
                suspend fun attempt(block: suspend () -> Unit) {
                    try {
                        block()
                    } catch (error: Exception) {
                        cleanupErrors += error
                    }
                }
                preferenceRestores.asReversed().forEach { restore -> attempt { restore() } }
                attempt {
                    downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
                    source?.let { current ->
                        mangas.getMangaBySourceId(profile.id).forEach { downloads.deleteManga(it, current) }
                    }
                }
                attempt { manager.remove(profile.id).getOrThrow() }
                attempt { mangas.deleteMangaBySourceId(profile.id) }
                attempt {
                    preferences.activeConnectionId.set(previousActive)
                    base.incognitoMode.set(previousIncognito)
                    base.shownOnboardingFlow.set(previousOnboarding)
                    downloadPreferences.downloadOnlyOverWifi.set(previousWifi)
                    reader.showNavigationOverlayNewUser.set(oldNewUserOverlay)
                    reader.showNavigationOverlayOnStart.set(oldStartupOverlay)
                    if (hadStorage) {
                        storage.baseStorageDirectory.set(previousStorage)
                    } else {
                        storage.baseStorageDirectory.delete()
                    }
                }
                attempt {
                    check(directory.canonicalFile.parentFile == context.getExternalFilesDir(null)!!.canonicalFile)
                    directory.deleteRecursively()
                }
                attempt { remote.cleanup() }
                assertTrue("All fixture cleanup steps must succeed", cleanupErrors.isEmpty())
            }
        }
    }

    private suspend fun exerciseChapter(
        active: SmangaSource,
        entry: SmangaManga,
        descriptor: SmangaChapter,
        api: SmangaApi,
        remote: OwnedRemoteState,
    ): Triple<Manga, Chapter, Int> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chapters: ChapterRepository = Injekt.get()
        val downloads: DownloadManager = Injekt.get()
        val manga = active.materialize(active.toManga(entry))
        val remoteChapters = active.getChapterList(manga.toSManga())
        Injekt.get<SyncChaptersWithSource>().await(remoteChapters, manga, active)
        val chapter = chapters.getChapterByMangaId(manga.id).single {
            active.session().chapterId(it.url) == descriptor.id
        }
        var pageCount = 0
        ActivityScenario.launch<ReaderActivity>(
            ReaderActivity.newIntent(context, manga.id, chapter.id, sourceId = active.id, pageIndex = 0),
        ).use { scenario ->
            awaitPage(scenario, 0)
            scenario.onActivity { pageCount = it.viewModel.state.value.currentChapter!!.pages!!.size }
            assertTrue("Representative chapter needs multiple pages", pageCount > 1)
            capture("${descriptor.format}-first")
            val target = (pageCount / 2).coerceAtMost(pageCount - 1)
            moveTo(scenario, target)
            awaitProgress(active, api, descriptor, "middle") { it.pageIndex == target && it.totalPages == pageCount }
            capture("${descriptor.format}-middle")
            moveTo(scenario, pageCount - 1)
            awaitProgress(active, api, descriptor, "completion") { it.completed && it.totalPages == pageCount }
            capture("${descriptor.format}-last")
        }
        assertTrue(remote.hasHistory(descriptor.id))
        active.setChapterReadStatus(chapter.url, false)
        awaitProgress(active, api, descriptor, "mark unread") { !it.completed && it.pageIndex == -1 }
        active.setChapterReadStatus(chapter.url, true)
        awaitProgress(active, api, descriptor, "mark read") { it.completed }
        println("smanga live progress passed: format=${descriptor.format}, pages=$pageCount")

        if (descriptor.format == "pdf") {
            val cached = active.findCompletePdfFile(chapter.url)
            assertNotNull(cached)
            val loader = PdfPageLoader(context, checkNotNull(cached))
            try {
                assertEquals(pageCount, loader.progressPageCount)
            } finally {
                loader.recycle()
            }
            assertFalse(
                downloads.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    active.id,
                    skipCache = true,
                ),
            )
        }
        val downloadStarted = SystemClock.elapsedRealtime()
        downloads.downloadChapters(manga, listOf(chapter))
        withTimeout(600_000) {
            while (!downloads.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.title,
                    active.id,
                    skipCache = true,
                )
            ) {
                delay(1000)
            }
        }
        val downloadMillis = SystemClock.elapsedRealtime() - downloadStarted
        println(
            "smanga live reader: format=${descriptor.format}, chapter=${descriptor.id}, " +
                "pages=$pageCount, downloadMillis=$downloadMillis",
        )
        return Triple(manga, chapter, pageCount)
    }

    private suspend fun assertOfflineDownload(active: SmangaSource, download: Triple<Manga, Chapter, Int>) {
        val (manga, chapter, pageCount) = download
        val downloads: DownloadManager = Injekt.get()
        val loader = DownloadPageLoader(
            ReaderChapter(chapter),
            manga,
            active,
            downloads,
            Injekt.get<DownloadProvider>(),
        )
        try {
            val pages = loader.getPages()
            assertEquals(pageCount, pages.size)
            if (active.isPdfChapter(chapter.url)) {
                assertEquals(pageCount, loader.progressPageCount)
                loader.setActivePage(pages.first())
                loader.loadPage(pages.first())
                assertTrue(checkNotNull(pages.first().bitmap?.invoke()).width > 0)
            } else {
                pages.first().stream!!.invoke().use {
                    val bitmap = checkNotNull(BitmapFactory.decodeStream(it))
                    assertTrue(bitmap.width > 0)
                    bitmap.recycle()
                }
            }
        } finally {
            loader.recycle()
        }
    }

    private suspend fun moveTo(scenario: ActivityScenario<ReaderActivity>, index: Int) {
        scenario.onActivity { activity ->
            val state = activity.viewModel.state.value
            state.viewer!!.moveToPage(state.currentChapter!!.pages!![index])
        }
        awaitPage(scenario, index)
    }

    private suspend fun awaitPage(scenario: ActivityScenario<ReaderActivity>, index: Int) {
        withTimeout(150_000) {
            var ready = false
            while (!ready) {
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    val page = state.currentChapter?.pages?.getOrNull(index)
                    ready = page?.status == Page.State.Ready && state.currentPage == index + 1
                }
                if (!ready) delay(250)
            }
        }
        delay(1500)
    }

    private suspend fun awaitProgress(
        source: SmangaSource,
        api: SmangaApi,
        chapter: SmangaChapter,
        stage: String,
        matches: (SmangaProgress) -> Boolean,
    ) {
        var latest: SmangaProgress? = null
        val completed = withTimeoutOrNull(30_000) {
            do {
                latest = api.chapters(chapter.mangaId).first { it.id == chapter.id }.latest
                if (latest?.let(matches) == true) return@withTimeoutOrNull true
                delay(500)
            } while (true)
        }
        val local = Injekt.get<SmangaRepository>().readState(source.id, source.session().accountKey, chapter.id)
        assertEquals("$stage: remote=$latest, local=$local, deviceTime=${System.currentTimeMillis()}", true, completed)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "smanga-live-evidence")
        directory.mkdirs()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private class OwnedRemoteState(private val config: SmangaLiveFixture.Config) {
        private val owned = mutableSetOf<Long>()
        private val client = OkHttpClient.Builder().followRedirects(false).callTimeout(20, TimeUnit.SECONDS).build()
        private var token: String? = null

        fun claim(chapterId: Long) {
            check(!hasHistory(chapterId)) { "Refusing to modify a chapter with existing history" }
            owned += chapterId
        }

        fun hasHistory(chapterId: Long): Boolean = request("chapter-is-read/$chapterId")["data"]
            ?.jsonPrimitive?.content == "true"

        fun cleanup() {
            var failures = 0
            owned.forEach { chapterId ->
                for (path in listOf("latest/$chapterId", "history/$chapterId")) {
                    if (runCatching { request(path, delete = true) }.isFailure) failures++
                }
            }
            token = null
            check(failures == 0) { "Failed to remove $failures fixture reading records" }
        }

        private fun request(path: String, delete: Boolean = false): JsonObject {
            val base = SmangaApi.normalizeBase(config.address)
            if (token == null) {
                val body = buildJsonObject {
                    put("userName", config.username)
                    put("passWord", config.password)
                }
                val request = Request.Builder().url(base.resolve("login")!!)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use {
                    check(it.isSuccessful) { "Fixture authentication failed" }
                    token = Json.parseToJsonElement(it.body.string()).jsonObject["data"]
                        ?.jsonObject?.get("token")?.jsonPrimitive?.content
                    check(token != null) { "Missing fixture session" }
                }
            }
            val request = Request.Builder().url(base.resolve(path)!!).header("token", checkNotNull(token))
                .apply { if (delete) delete() }.build()
            return client.newCall(request).execute().use {
                check(it.isSuccessful) { "Fixture state operation failed: HTTP ${it.code}" }
                val value = Json.parseToJsonElement(it.body.string()).jsonObject
                check(value["code"]?.jsonPrimitive?.content == "200") { "Fixture state operation rejected" }
                value
            }
        }
    }
}
