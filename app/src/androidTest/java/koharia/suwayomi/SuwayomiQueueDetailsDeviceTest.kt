package koharia.suwayomi

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionShelfEntryObserver
import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiRepository
import koharia.source.suwayomi.SuwayomiPreferences
import koharia.source.suwayomi.SuwayomiSource
import koharia.suwayomi.ui.SuwayomiDownloadsScreen
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SuwayomiQueueDetailsDeviceTest {
    @Test
    fun serverQueueOpensUnmaterializedSeriesAndReusesItsCanonicalOwner(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val profiles = Injekt.get<ConnectionProfileManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val mangas = Injekt.get<MangaRepository>()
        val repository = Injekt.get<SuwayomiRepository>()
        val profile = profiles.add("suwayomi", "Queue details ${UUID.randomUUID()}")
        val remote = SuwayomiManga(11, "Queued canonical series", downloadCount = 99)
        val chapter = SuwayomiChapter(12, mangaId = 11, name = "Queued unit", pageCount = 1)
        val status = SuwayomiDownloadStatus(
            queue = listOf(
                SuwayomiDownloadItem(chapter = SuwayomiDownloadChapter(12, 11, chapter.name), manga = remote),
            ),
        )
        val server = QueueServer(Json.encodeToString(status))
        val downloads = Injekt.get<DownloadManager>()
        var activeSource: SuwayomiSource? = null
        var storedManga: Manga? = null
        try {
            SuwayomiPreferences(profile.id).save(server.address, "", SuwayomiAuthMode.NONE, "", "")
            val source = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is SuwayomiSource) delay(25)
                (Injekt.get<SourceManager>().get(profile.id) as SuwayomiSource).also { it.reload() }
            }
            val session = source.session()
            activeSource = source
            val provider = Injekt.get<DownloadProvider>()
            val cache = Injekt.get<DownloadCache>()
            val chapters = Injekt.get<ChapterRepository>()
            check(provider.findOwnedSourceDirs(source).isEmpty())
            repository.putCache(
                profile.id,
                session.identity.account,
                "manga",
                SuwayomiCacheEntry("11", Json.encodeToString(remote), 1),
            )
            repository.putCache(
                profile.id,
                session.identity.account,
                "chapters",
                SuwayomiCacheEntry("11", Json.encodeToString(listOf(chapter)), 1),
            )
            session.downloadSnapshot.value = status
            check(mangas.getMangaBySourceId(profile.id).isEmpty())
            var navigator: Navigator? = null
            FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(SuwayomiDownloadsScreen(profile.id)) { host ->
                                navigator = host
                                CurrentScreen()
                            }
                        }
                    }
                }
                var firstId: Long? = null
                repeat(2) { attempt ->
                    val browse = context.stringResource(MR.strings.browse)
                    val button = withTimeout(10_000) {
                        var found: AccessibilityNodeInfo? = null
                        while (found == null) {
                            found = find(instrumentation.uiAutomation.rootInActiveWindow, browse)
                            if (found == null) delay(50)
                        }
                        found
                    }
                    var clickable: AccessibilityNodeInfo? = button
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                    withTimeout(15_000) {
                        while (navigator?.lastItem !is MangaScreen) delay(50)
                    }
                    val row = checkNotNull(mangas.getMangaByUrlAndSourceId(session.identity.mangaUrl(11), profile.id)) {
                        "Queue details navigated without materializing its canonical series"
                    }
                    val screen = navigator!!.lastItem as MangaScreen
                    storedManga = row
                    check(screen.mangaId == row.id && screen.sourceId == profile.id && screen.mangaUrl == row.url)
                    withTimeout(15_000) {
                        while (find(instrumentation.uiAutomation.rootInActiveWindow, remote.title) == null) delay(50)
                    }
                    check(mangas.getMangaBySourceId(profile.id).single().id == row.id)
                    check(firstId == null || firstId == row.id)
                    firstId = row.id
                    check(Injekt.get<DownloadManager>().getDownloadCount(row) == 0)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("queue_details", "attempt=$attempt;canonical=true;localDownloads=0")
                        },
                    )
                    scenario.onActivity { check(navigator!!.pop()) }
                }
                val manga = checkNotNull(storedManga)
                val unit = withTimeout(15_000) {
                    var found: Chapter? = null
                    while (found == null) {
                        found = chapters.getChapterByMangaId(manga.id)
                            .singleOrNull { it.url == session.identity.chapterUrl(11, 12) }
                        if (found == null) delay(50)
                    }
                    found
                }
                val other = chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = session.identity.chapterUrl(11, 13),
                            name = "Other owned unit",
                        ),
                    ),
                ).single()
                val directory = provider.getMangaDir(manga.title, source).getOrThrow()
                val bytes = archive()
                suspend fun add(chapter: Chapter) {
                    val name = provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + ".cbz"
                    check(directory.findFile(name) == null)
                    checkNotNull(directory.createFile(name)).openOutputStream().use { it.write(bytes) }
                    cache.addChapter(name, directory, manga)
                }
                suspend fun remove(chapter: Chapter) {
                    val name = provider.getChapterDirName(chapter.name, chapter.scanlator, chapter.url) + ".cbz"
                    val file = checkNotNull(directory.findFile(name))
                    check(file.openInputStream().use { it.readBytes() }.contentEquals(bytes))
                    check(file.delete())
                    cache.removeChapter(chapter, manga)
                }
                val complete = context.stringResource(MR.strings.shelf_download_complete, 1L)
                suspend fun awaitDownload(expected: Boolean, stage: String) {
                    var actual = emptyList<String>()
                    try {
                        withTimeout(15_000) {
                            while (true) {
                                actual = labels(instrumentation.uiAutomation.rootInActiveWindow)
                                if (chapter.name in actual && (complete in actual) == expected) break
                                delay(50)
                            }
                        }
                    } catch (error: Exception) {
                        throw AssertionError("$stage expected=$expected actual=$actual", error)
                    }
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply {
                            putString("queue_local_download", "$stage;complete=$expected;serverDownloads=99")
                        },
                    )
                }
                awaitDownload(false, "none")
                add(other)
                check(downloads.getDownloadCount(manga) == 1)
                val observed = withTimeout(15_000) {
                    ConnectionShelfEntryObserver(profile.id, mangas, chapters, downloads)
                        .observe(flowOf(setOf(manga.url)))
                        .first { it[manga.url]?.downloadedChapterUrls == setOf(other.url) }
                        .getValue(manga.url)
                }
                check(source.queueDownloadState(status.queue.single(), observed).count == 0L)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("queue_nonmatching_unit", "observedLocalDownloads=1;queueDownloaded=0")
                    },
                )
                awaitDownload(false, "different-unit-downloaded")
                remove(other)
                add(unit)
                check(downloads.getDownloadCount(manga) == 1)
                awaitDownload(true, "queued-unit-downloaded")
                remove(unit)
                awaitDownload(false, "removed")
                check(downloads.getDownloadCount(manga) == 0)
                check(server.writes.get() == 0) { "Unexpected business mutations: ${server.mutationFields}" }
                check(server.queueReads.get() > 0)
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString(
                            "queue_protocol",
                            "queueReads=${server.queueReads.get()};writes=0;pageFetchMutations=${server.pageFetches.get()}",
                        )
                    },
                )
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    activeSource?.let { source ->
                        storedManga?.let { downloads.deleteManga(it, source, removeQueued = false) }
                    }
                    profiles.remove(profile.id).getOrThrow()
                    mangas.deleteMangaBySourceId(profile.id)
                    check(mangas.getMangaBySourceId(profile.id).isEmpty())
                    instrumentation.sendStatus(0, Bundle().apply { putString("queue_cleanup", "complete") })
                } finally {
                    server.close()
                    if (hadSelected) selected.set(oldSelected) else selected.delete()
                }
            }
        }
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && node.text?.toString() == text) return node
        repeat(node.childCount) { find(node.getChild(it), text)?.let { return it } }
        return null
    }

    private fun labels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        return buildList {
            if (node.isVisibleToUser) {
                node.text?.toString()?.let(::add)
                node.contentDescription?.toString()?.let(::add)
            }
            repeat(node.childCount) { addAll(labels(node.getChild(it))) }
        }
    }

    private fun archive(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.CYAN)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("001.png").apply { time = 1577836800000L })
                zip.write(png)
                zip.closeEntry()
            }
        }.toByteArray()
    }

    private class QueueServer(private val status: String) : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val writes = AtomicInteger()
        val pageFetches = AtomicInteger()
        val mutationFields = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val queueReads = AtomicInteger()
        val address get() = "http://127.0.0.1:${listener.localPort}"
        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = runCatching { listener.accept() }.getOrNull() ?: break
                    runCatching {
                        socket.use {
                            it.soTimeout = 5000
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().split(' ')
                            var length = 0
                            while (true) {
                                val header = reader.readLine().orEmpty()
                                if (header.isEmpty()) break
                                if (header.startsWith("Content-Length:", true)) {
                                    length =
                                        header.substringAfter(':').trim().toInt()
                                }
                            }
                            val chars = CharArray(length)
                            var offset = 0
                            while (offset < length) {
                                val count = reader.read(chars, offset, length - offset)
                                check(count > 0)
                                offset += count
                            }
                            val query = if (length > 0) {
                                Json.parseToJsonElement(
                                    String(chars),
                                ).jsonObject["query"]?.jsonPrimitive?.content.orEmpty()
                            } else {
                                ""
                            }
                            if (query.trimStart().startsWith("mutation")) {
                                val field = query.substringAfter('{').substringBefore('(').substringBefore('{').trim()
                                if (field == "fetchChapterPages") {
                                    pageFetches.incrementAndGet()
                                } else {
                                    mutationFields.add(field)
                                    writes.incrementAndGet()
                                }
                            }
                            val queueQuery = request[0] == "POST" && query.trimStart().startsWith("query") &&
                                "downloadStatus" in query
                            val response = if (queueQuery) {
                                queueReads.incrementAndGet()
                                """{"data":{"downloadStatus":$status}}"""
                            } else {
                                "{}"
                            }
                            val bytes = response.toByteArray()
                            val code = if (queueQuery) "200 OK" else "503 Fixture"
                            it.getOutputStream().write(
                                (
                                    "HTTP/1.1 $code\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray() + bytes,
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
