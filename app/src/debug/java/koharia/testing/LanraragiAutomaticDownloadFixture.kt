package koharia.testing

import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.annotation.Keep
import androidx.work.WorkInfo
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.domain.lanraragi.LanraragiEntry
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.function.Consumer

object LanraragiAutomaticDownloadFixture {
    @Keep
    @JvmStatic
    fun run(context: Context, status: Consumer<String>): Unit = runBlocking(Dispatchers.IO) {
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty()) { "Existing download queue must remain untouched" }
        val notifications = context.getSystemService(NotificationManager::class.java)
        check(notifications.areNotificationsEnabled()) { "Fixture notification permission required" }
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val wm = WorkManager.getInstance(context)
        val shared = Injekt.get<SharedAppPreferences>()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val present = preference.isSet()
            val previous = preference.get()
            restores += { if (present) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        val root = File(context.getExternalFilesDir(null), "automatic-download-${UUID.randomUUID()}").canonicalFile
        check(root.parentFile == context.getExternalFilesDir(null)!!.canonicalFile && !root.exists() && root.mkdirs())
        val profile = profiles.add("lanraragi", "Automatic download ${UUID.randomUUID()}")
        val version = "v80_test_automatic_${UUID.randomUUID()}"
        var workName: String? = null
        var source: LanraragiSource? = null
        fun availability(offline: Boolean) {
            val request = URI("http://127.0.0.1:38709/_fixture/control?version=$version&offline=$offline")
                .toURL().openConnection() as java.net.HttpURLConnection
            try {
                request.connectTimeout = 5_000
                request.readTimeout = 5_000
                assertEquals(200, request.responseCode)
                request.inputStream.use { it.readBytes() }
            } finally {
                request.disconnect()
            }
        }
        try {
            replace(connections.activeConnectionId, ConnectionPreferences.LOCAL_CONNECTION_ID)
            replace(shared.basePreferences().downloadedOnly, false)
            replace(shared.basePreferences().incognitoMode, false)
            val preferences = shared.downloadPreferences()
            replace(preferences.downloadNewChapters, true)
            replace(preferences.downloadNewChapterCategories, emptySet())
            replace(preferences.downloadNewChapterCategoriesExclude, emptySet())
            replace(preferences.downloadNewUnreadChaptersOnly, false)
            replace(preferences.downloadOnlyOverWifi, false)
            replace(preferences.splitTallImages, false)
            replace(preferences.saveChaptersAsCBZ, true)
            replace(Injekt.get<StoragePreferences>().baseStorageDirectory, root.toURI().toString())
            withTimeout(10_000) {
                while (Injekt.get<StorageManager>().getDownloadsDirectory()?.uri?.path !=
                    File(root, "downloads").path
                ) {
                    delay(50)
                }
            }
            profiles.commitConfiguration(profile.id) { draft ->
                LanraragiPreferences(profile.id, draft).save("http://127.0.0.1:38709/$version/lrr/", "fixture-key")
            }
            val active = Injekt.get<SourceManager>().get(profile.id) as LanraragiSource
            source = active
            active.refreshLibrary().getOrThrow()
            val entry = active.repository.entries(active.id).first { it.kind == LanraragiEntry.Kind.ARCHIVE }
            val manga = active.materialize(entry)
            mangas.update(MangaUpdate(manga.id, favorite = true))
            assertTrue(chapters.getChapterByMangaId(manga.id).isEmpty())
            val name = LibraryUpdateJob.workName(active.id, active.captureChapterOwner().key)
            workName = name
            assertTrue(wm.getWorkInfosForUniqueWork(name).get().isEmpty())
            assertTrue(LibraryUpdateJob.request(active, ChapterFetchOrigin.EXPLICIT_REFRESH))
            withTimeout(30_000) {
                while (true) {
                    val work = wm.getWorkInfosForUniqueWork(name).get()
                    if (work.isNotEmpty() && work.all { it.state.isFinished }) {
                        assertEquals(1, work.size)
                        assertTrue(work.all { it.state == WorkInfo.State.SUCCEEDED })
                        break
                    }
                    delay(50)
                }
            }
            val chapter = chapters.getChapterByMangaId(manga.id).single()
            assertTrue(notifications.activeNotifications.any { it.id == manga.id.hashCode() })
            withTimeout(45_000) {
                while (!downloads.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.title,
                        active.id,
                        skipCache = true,
                    )
                ) {
                    val queued = downloads.queueState.value.firstOrNull { it.chapter.id == chapter.id }
                    check(queued?.status != eu.kanade.tachiyomi.data.download.model.Download.State.ERROR) {
                        "Automatic download failed: images=${queued?.downloadedImages};" +
                            "pages=${queued?.pages?.map { it.status }};uris=${queued?.pages?.map { it.uri }};" +
                            "files=${root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root) }.toList()}"
                    }
                    delay(100)
                }
            }
            withTimeout(30_000) {
                while (downloads.queueState.value.any { it.source.id == profile.id }) {
                    val queued = downloads.queueState.value.filter { it.source.id == profile.id }
                    check(queued.none { it.status == eu.kanade.tachiyomi.data.download.model.Download.State.ERROR }) {
                        "Automatic download did not complete: ${queued.map { it.status }}"
                    }
                    delay(100)
                }
            }
            assertEquals(0, active.api.archive(entry.id).progress)
            availability(offline = true)
            val loader =
                DownloadPageLoader(ReaderChapter(chapter), manga, active, downloads, Injekt.get<DownloadProvider>())
            try {
                val pages = loader.getPages()
                assertEquals(3, pages.size)
                val colors = pages.map { page ->
                    loader.loadPage(page)
                    checkNotNull(page.stream).invoke().use { stream ->
                        checkNotNull(BitmapFactory.decodeStream(stream)).let { image ->
                            image.getPixel(0, 0).also { image.recycle() }
                        }
                    }
                }
                assertEquals(3, colors.distinct().size)
            } finally {
                loader.recycle()
            }
            assertTrue(downloads.queueState.value.none { it.source.id == profile.id })
            status.accept("worker-succeeded;new-chapter-notification;three-offline-pages")
        } finally {
            workName?.let { name ->
                wm.cancelUniqueWork(name).result.get()
                withTimeout(10_000) {
                    while (wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished }) delay(25)
                }
            }
            downloads.cancelQueuedDownloads(downloads.queueState.value.filter { it.source.id == profile.id })
            source?.let { owner ->
                mangas.getMangaBySourceId(profile.id).forEach {
                    notifications.cancel(it.id.hashCode())
                    downloads.deleteManga(it, owner)
                }
            }
            profiles.remove(profile.id).getOrThrow()
            mangas.deleteMangaBySourceId(profile.id)
            restores.asReversed().forEach { it() }
            connections.flush()
            check(root.parentFile == context.getExternalFilesDir(null)!!.canonicalFile && root.deleteRecursively())
            status.accept("owned-work-profile-download-files-removed;preferences-restored")
        }
    }
    private fun assertTrue(value: Boolean) = check(value)

    private fun assertEquals(expected: Any?, actual: Any?) = check(expected == actual) {
        "Expected $expected, actual $actual"
    }
}
