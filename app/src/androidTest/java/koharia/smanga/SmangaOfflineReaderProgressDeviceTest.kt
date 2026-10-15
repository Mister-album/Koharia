package koharia.smanga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.connection.ConnectionChapterMetadata
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionProgressSyncStatus
import koharia.connection.SharedAppPreferences
import koharia.domain.smanga.SmangaReadState
import koharia.domain.smanga.SmangaRepository
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class SmangaOfflineReaderProgressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun offlineExitReopenAndReadDeletionKeepDurableProgressAndHistory(): Unit = runBlocking(Dispatchers.IO) {
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val pendingDeletion = context.getSharedPreferences("chapters_to_delete", Context.MODE_PRIVATE)
        check(pendingDeletion.all.isEmpty()) { "Existing deletion intentions must remain untouched" }
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty()) { "Existing downloads must remain untouched" }
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val history = Injekt.get<HistoryRepository>()
        val repository = Injekt.get<SmangaRepository>()
        val provider = Injekt.get<DownloadProvider>()
        val cache = Injekt.get<DownloadCache>()
        val shared = Injekt.get<SharedAppPreferences>()
        val root = File(context.cacheDir, "offline-reader-${UUID.randomUUID()}").canonicalFile
        check(root.mkdirs())
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val old = preference.get()
            val wasSet = preference.isSet()
            restores += { if (wasSet) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val profile = profiles.add("smanga", "Offline reader fixture")
        var source: SmangaSource? = null
        var manga: Manga? = null
        UnavailableServer().use { server ->
            try {
                replace(Injekt.get<BasePreferences>().shownOnboardingFlow, true)
                replace(shared.basePreferences().incognitoMode, false)
                replace(shared.basePreferences().downloadedOnly, false)
                replace(connections.activeConnectionId, profile.id)
                replace(Injekt.get<StoragePreferences>().baseStorageDirectory, root.toURI().toString())
                replace(shared.downloadPreferences().autoDownloadWhileReading, 0)
                replace(shared.downloadPreferences().cacheCurrentChapterWhileReading, false)
                replace(shared.downloadPreferences().removeAfterReadSlots, 0)
                replace(shared.downloadPreferences().removeExcludeCategories, emptySet())
                val reader = shared.readerPreferences()
                replace(reader.showNavigationOverlayNewUser, false)
                replace(reader.showNavigationOverlayOnStart, false)
                replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
                replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
                replace(reader.dualPageSplitPaged, false)
                replace(reader.dualPageSplitWebtoon, false)
                SmangaPreferences(profile.id).save(server.address, "fixture", "fixture-password", 145)
                await("source-registered") { Injekt.get<SourceManager>().get(profile.id) is SmangaSource }
                val active = (Injekt.get<SourceManager>().get(profile.id) as SmangaSource).also { it.reload() }
                source = active
                val account = active.session().accountKey
                val stored = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = active.session().mangaUrl(2),
                            title = "Offline book",
                        ),
                    ),
                ).single()
                manga = stored
                val units = chapters.addAll(
                    (3L..4L).map { id ->
                        Chapter.create().copy(
                            mangaId = stored.id,
                            url = active.session().chapterUrl(SmangaChapter(id, 2, 1, "Chapter $id", pageCount = 4)),
                            name = "Chapter $id",
                            chapterNumber = (id - 2).toDouble(),
                            sourceOrder = id - 3,
                            memo = ConnectionChapterMetadata.withPagesCount(Chapter.create().memo, 4),
                        )
                    },
                )
                val chapter = units.first()
                val directory = provider.getMangaDir(stored.title, active).getOrThrow()
                check(File(directory.uri.path!!).canonicalPath.startsWith(root.path + File.separator))
                val bytes = archive()
                val files = units.associate { unit ->
                    val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                    val file = checkNotNull(directory.createFile(name))
                    file.openOutputStream().use { it.write(bytes) }
                    cache.addChapter(name, directory, stored)
                    unit.id to file
                }
                val target = files.getValue(chapter.id)
                assertEquals(2, downloads.getDownloadCount(stored))
                suspend fun saved(page: Int): SmangaReadState {
                    await("durable-page-$page") {
                        repository.readState(profile.id, account, 3)?.let {
                            it.pageIndex == page && it.pending
                        } == true &&
                            chapters.getChapterById(chapter.id)?.lastPageRead == page.toLong()
                    }
                    val record = checkNotNull(repository.readState(profile.id, account, 3))
                    assertEquals(2L, record.mangaId)
                    assertEquals(4, record.totalPages)
                    assertEquals(page == 3, record.completed)
                    assertEquals(page == 3, chapters.getChapterById(chapter.id)!!.read)
                    assertTrue(repository.pendingHistoryEvents(profile.id, account).any { it.chapterId == 3L })
                    withTimeout(15_000) {
                        active.observeProgressSyncStatus(listOf(chapter.url)).first {
                            it[chapter.url] == setOf(ConnectionProgressSyncStatus.PENDING)
                        }
                    }
                    status("saved-page-$page", record)
                    return record
                }
                suspend fun localHistory(minimumReadAt: Long) = await("local-history") {
                    history.getHistoryByMangaId(stored.id).any {
                        it.chapterId == chapter.id && (it.readAt?.time ?: 0L) >= minimumReadAt
                    }
                }
                FixtureActivityLauncher.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, stored.id, chapter.id, sourceId = profile.id),
                ).use { scenario ->
                    lateinit var activity: ReaderActivity
                    scenario.onActivity { activity = it }
                    awaitPage(activity, 0)
                    move(activity, 1)
                    saved(1)
                    assertTrue(pendingDeletion.all.isEmpty())
                    exit(activity)
                }
                val first = saved(1)
                localHistory(first.readAt)
                val firstHistoryIds = repository.pendingHistoryEvents(profile.id, account).map { it.id }.toSet()
                assertBytes(files.values, bytes)
                active.reload()
                assertEquals(account, active.session().accountKey)
                assertEquals(first, repository.readState(profile.id, account, 3))
                FixtureActivityLauncher.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, stored.id, chapter.id, sourceId = profile.id),
                ).use { scenario ->
                    lateinit var activity: ReaderActivity
                    scenario.onActivity { activity = it }
                    awaitPage(activity, 1)
                    saved(1)
                    assertBytes(files.values, bytes)
                    move(activity, 3)
                    await("deletion-enqueued") { pendingDeletion.contains(stored.id.toString()) }
                    val complete = saved(3)
                    assertTrue(complete.readAt >= first.readAt)
                    assertTrue(complete.revision > first.revision)
                    assertTrue(
                        repository.pendingHistoryEvents(profile.id, account).any {
                            it.chapterId == 3L && it.id !in firstHistoryIds && it.readAt >= first.readAt
                        },
                    )
                    assertBytes(files.values, bytes)
                    assertEquals(setOf(stored.id.toString()), pendingDeletion.all.keys)
                    status("intent-persisted-before-deletion", complete)
                    exit(activity)
                }
                await("owned-download-deleted") { !target.exists() }
                val last = saved(3)
                assertTrue(last.readAt > first.readAt)
                localHistory(last.readAt)
                assertTrue(pendingDeletion.all.isEmpty())
                assertEquals(1, downloads.getDownloadCount(stored))
                assertBytes(listOf(files.getValue(units.last().id)), bytes)
                assertFalse(chapters.getChapterById(units.last().id)!!.read)
                assertEquals(null, repository.readState(profile.id, account, 4))
                assertTrue(server.requests.get() > 0)
                status("new-history-and-completion-pending-after-delete", last)
            } finally {
                try {
                    manga?.let { owned ->
                        check(pendingDeletion.all.keys.all { it == owned.id.toString() })
                        check(pendingDeletion.edit().remove(owned.id.toString()).commit())
                        source?.let { downloads.deleteManga(owned, it, removeQueued = false) }
                    }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.getMangaBySourceId(profile.id).forEach { mangas.deleteMangaById(it.id) }
                } finally {
                    restores.asReversed().forEach { it() }
                    check(root.parentFile == context.cacheDir.canonicalFile)
                    check(root.deleteRecursively())
                }
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("offline_cleanup", "owned-data-removed;preferences-restored")
                    },
                )
            }
        }
    }

    private suspend fun awaitPage(activity: ReaderActivity, page: Int) = await("visible-page-$page") {
        withContext(Dispatchers.Main) {
            val state = activity.viewModel.state.value
            state.currentChapter?.pageLoader is DownloadPageLoader && state.currentPage == page + 1 &&
                state.visiblePageStart == page + 1 && state.visiblePageEnd == page + 1 && activity.hasWindowFocus()
        }
    }

    private suspend fun move(activity: ReaderActivity, page: Int) {
        withContext(Dispatchers.Main) {
            val state = activity.viewModel.state.value
            state.viewer!!.moveToPage(state.currentChapter!!.pages!![page])
        }
        awaitPage(activity, page)
    }

    private suspend fun exit(activity: ReaderActivity) {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("reader-destroyed") { withContext(Dispatchers.Main) { activity.isDestroyed } }
    }

    private suspend fun await(phase: String, ready: suspend () -> Boolean) {
        instrumentation.sendStatus(2, Bundle().apply { putString("offline_wait", phase) })
        withTimeout(30_000) { while (!ready()) delay(50) }
    }

    private fun assertBytes(files: Collection<UniFile>, expected: ByteArray) {
        files.forEach { assertTrue(it.openInputStream().use { input -> input.readBytes() }.contentEquals(expected)) }
    }

    private fun status(phase: String, state: SmangaReadState) {
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "offline_progress",
                    "$phase;page=${state.pageIndex};revision=${state.revision};pending=${state.pending}",
                )
            },
        )
    }

    private fun archive(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            for (page in 0..3) {
                val image = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
                image.eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)[page])
                zip.putNextEntry(ZipEntry("$page.png"))
                check(image.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
                image.recycle()
            }
        }
        output.toByteArray()
    }

    private class UnavailableServer : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val requests = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            requests.incrementAndGet()
                            it.getOutputStream().write(
                                "HTTP/1.1 503 Fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            executor.shutdownNow()
        }
    }
}
