package koharia.suwayomi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
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
import koharia.domain.suwayomi.SuwayomiOperation
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
class SuwayomiOfflineHistoryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun offlineExitReopenAndHistoryPreserveOwnedPendingProgress(): Unit = runBlocking(Dispatchers.IO) {
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
        val repository = Injekt.get<SuwayomiRepository>()
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
        val profile = profiles.add("suwayomi", "Offline reader fixture")
        var source: SuwayomiSource? = null
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
                replace(shared.downloadPreferences().removeAfterReadSlots, -1)
                replace(shared.downloadPreferences().removeExcludeCategories, emptySet())
                val reader = shared.readerPreferences()
                replace(reader.showNavigationOverlayNewUser, false)
                replace(reader.showNavigationOverlayOnStart, false)
                replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
                replace(reader.defaultReadingMode, ReadingMode.LEFT_TO_RIGHT.flagValue)
                replace(reader.dualPageSplitPaged, false)
                replace(reader.dualPageSplitWebtoon, false)
                SuwayomiPreferences(profile.id).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
                await("source-registered") { Injekt.get<SourceManager>().get(profile.id) is SuwayomiSource }
                val active = (Injekt.get<SourceManager>().get(profile.id) as SuwayomiSource).also { it.reload() }
                source = active
                val account = active.session().identity.account
                val stored = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = active.session().identity.mangaUrl(2),
                            title = "Offline book",
                        ),
                    ),
                ).single()
                manga = stored
                val units = chapters.addAll(
                    (3L..3L).map { id ->
                        Chapter.create().copy(
                            mangaId = stored.id,
                            url = active.session().identity.chapterUrl(2, id.toInt()),
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
                assertEquals(1, downloads.getDownloadCount(stored))
                suspend fun saved(page: Int): SuwayomiOperation {
                    await("durable-page-$page") {
                        repository.operation(profile.id, account, "3")?.let {
                            Json.decodeFromString<SuwayomiReadState>(it.payload).page == page && it.pending
                        } == true && chapters.getChapterById(chapter.id)?.lastPageRead == page.toLong()
                    }
                    val operation = checkNotNull(repository.operation(profile.id, account, "3"))
                    val record = Json.decodeFromString<SuwayomiReadState>(operation.payload)
                    assertEquals(2, record.mangaId)
                    assertEquals(3, record.chapterId)
                    assertEquals(4, record.count)
                    assertFalse(record.read)
                    assertFalse(chapters.getChapterById(chapter.id)!!.read)
                    withTimeout(15_000) {
                        active.observeProgressSyncStatus(listOf(chapter.url)).first {
                            it[chapter.url] == setOf(ConnectionProgressSyncStatus.PENDING)
                        }
                    }
                    status("saved-page-$page", operation)
                    return operation
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
                localHistory(Json.decodeFromString<SuwayomiReadState>(first.payload).readAt)
                assertBytes(files.values, bytes)
                active.reload()
                assertEquals(account, active.session().identity.account)
                assertEquals(first, repository.operation(profile.id, account, "3"))
                FixtureActivityLauncher.launch<ReaderActivity>(
                    ReaderActivity.newIntent(context, stored.id, chapter.id, sourceId = profile.id),
                ).use { scenario ->
                    lateinit var activity: ReaderActivity
                    scenario.onActivity { activity = it }
                    awaitPage(activity, 1)
                    saved(1)
                    assertBytes(files.values, bytes)
                    move(activity, 2)
                    saved(2)
                    assertTrue(pendingDeletion.all.isEmpty())
                    exit(activity)
                }
                val second = saved(2)
                assertTrue(second.revision > first.revision)
                localHistory(Json.decodeFromString<SuwayomiReadState>(second.payload).readAt)
                assertBytes(files.values, bytes)
                FixtureActivityLauncher.launch(androidx.activity.ComponentActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            eu.kanade.presentation.theme.TachiyomiTheme {
                                cafe.adriel.voyager.navigator.Navigator(eu.kanade.tachiyomi.ui.home.HomeScreen)
                            }
                        }
                    }
                    withTimeout(10_000) {
                        eu.kanade.tachiyomi.ui.home.HomeScreen.openTab(
                            eu.kanade.tachiyomi.ui.home.HomeScreen.Tab.History,
                        )
                    }
                    val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
                    var opened: ReaderActivity? = null
                    try {
                        clickHistory(stored.title)
                        val historyReader = checkNotNull(monitor.waitForActivityWithTimeout(30_000) as? ReaderActivity)
                        opened = historyReader
                        awaitPage(historyReader, 2)
                        withContext(Dispatchers.Main) {
                            val state = historyReader.viewModel.state.value
                            assertEquals(stored.id, state.manga!!.id)
                            assertEquals(profile.id, state.manga.source)
                            assertEquals(chapter.id, state.currentChapter!!.chapter.id)
                        }
                        assertEquals(profile.id, connections.activeConnectionId.get())
                        saved(2)
                        exit(historyReader)
                    } finally {
                        opened?.let { activity ->
                            withContext(Dispatchers.Main) { if (!activity.isDestroyed) activity.finish() }
                        }
                        instrumentation.removeMonitor(monitor)
                    }
                }
                assertBytes(files.values, bytes)
                assertTrue(pendingDeletion.all.isEmpty())
                assertEquals(1, downloads.getDownloadCount(stored))
                assertTrue(server.requests.get() > 0)
                assertTrue(server.pageFetches.get() > 0)
                assertEquals(0, server.invalidRequests.get())
                assertEquals(0, server.mutations.get())
                status(
                    "actual-home-history-resumed-owner-page-2;download-bytes-preserved;" +
                        "page-fetches=${server.pageFetches.get()};business-writes=0;all-http=503",
                    saved(2),
                )
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
                    Injekt.get<ConnectionPreferences>().flush()
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
                state.currentChapter?.pages?.getOrNull(page)?.status ==
                eu.kanade.tachiyomi.source.model.Page.State.Ready &&
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

    private fun status(phase: String, operation: SuwayomiOperation) {
        val record = Json.decodeFromString<SuwayomiReadState>(operation.payload)
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "offline_progress",
                    "$phase;page=${record.page};revision=${operation.revision};pending=${operation.pending}",
                )
            },
        )
    }

    private suspend fun clickHistory(title: String) = withTimeout(15_000) {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            node.refresh()
            if (node.isVisibleToUser && node.text?.toString() == title) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
            delay(100)
        }
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
        val mutations = AtomicInteger()
        val pageFetches = AtomicInteger()
        val invalidRequests = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    socket.soTimeout = 5_000
                    runCatching {
                        socket.use {
                            val input = it.getInputStream().buffered()
                            fun line(): String = buildString {
                                while (true) {
                                    val value = input.read()
                                    if (value == -1 || value == 10) break
                                    if (value != 13) append(value.toChar())
                                }
                            }
                            line()
                            val headers = generateSequence { line().takeIf(String::isNotEmpty) }
                                .associate { value ->
                                    value.substringBefore(':').lowercase() to
                                        value.substringAfter(':').trim()
                                }
                            val length = headers["content-length"]?.toInt() ?: 0
                            require(length in 0..65_536)
                            val body = ByteArray(length).also { data -> java.io.DataInputStream(input).readFully(data) }
                            if (body.isNotEmpty()) {
                                val request = org.json.JSONObject(body.decodeToString())
                                val query = request.getString("query").trimStart()
                                if (query.startsWith("mutation")) {
                                    if (query.substringAfter('{').startsWith("fetchChapterPages(input:")) {
                                        check(request.getJSONObject("variables").getInt("id") == 3)
                                        pageFetches.incrementAndGet()
                                    } else {
                                        mutations.incrementAndGet()
                                    }
                                } else {
                                    check(query.startsWith("query"))
                                }
                            }
                            requests.incrementAndGet()
                            it.getOutputStream().write(
                                "HTTP/1.1 503 Fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                            )
                        }
                    }.onFailure { invalidRequests.incrementAndGet() }
                }
            }
        }

        override fun close() {
            listener.close()
            executor.shutdownNow()
        }
    }
}
