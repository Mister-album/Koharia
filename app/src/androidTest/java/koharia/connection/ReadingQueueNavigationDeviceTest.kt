package koharia.connection

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.sourcePreferences
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import koharia.kavita.KavitaAccount
import koharia.kavita.KavitaChapter
import koharia.kavita.KavitaChapterRef
import koharia.kavita.KavitaLibrary
import koharia.kavita.KavitaListItem
import koharia.kavita.KavitaSeries
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.BookMetadataDto
import koharia.komga.api.dto.MediaDto
import koharia.komga.download.KomgaChapterMemo
import koharia.source.kavita.KavitaPreferences
import koharia.source.kavita.KavitaSource
import koharia.source.komga.KomgaSource
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReadingQueueNavigationDeviceTest {
    @Test fun komgaPager() = verify("komga", ReadingMode.LEFT_TO_RIGHT, false)

    @Test fun komgaWebtoon() = verify("komga", ReadingMode.WEBTOON, true)

    @Test fun kavitaPager() = verify("kavita", ReadingMode.LEFT_TO_RIGHT, true)

    @Test fun kavitaWebtoon() = verify("kavita", ReadingMode.WEBTOON, false)

    private fun verify(providerId: String, mode: ReadingMode, eInk: Boolean): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val profiles = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val previous = connections.activeConnectionId.get()
        val hadPrevious = connections.activeConnectionId.isSet()
        val restores = mutableListOf<() -> Unit>()
        fun <T> replace(preference: Preference<T>, value: T) {
            val present = preference.isSet()
            val old = preference.get()
            restores += { if (present) preference.set(old) else preference.delete() }
            preference.set(value)
        }
        val shared = Injekt.get<SharedAppPreferences>()
        val reader = shared.readerPreferences()
        val mangas = Injekt.get<MangaRepository>()
        val chapters = Injekt.get<ChapterRepository>()
        val downloads = Injekt.get<DownloadManager>()
        val provider = Injekt.get<DownloadProvider>()
        val cache = Injekt.get<DownloadCache>()
        val profile = profiles.add(providerId, "Queue fixture ${UUID.randomUUID()}")
        val stored = mutableListOf<Manga>()
        var source: HttpSource? = null
        val books = (1..3).map { i ->
            BookDto(
                id = "$i",
                seriesId = if (i < 3) "10" else "20",
                seriesTitle = "Series ${if (i < 3) 10 else 20}",
                name = "Book $i",
                number = i.toFloat(),
                fileLastModified = "2026-01-01T00:00:00",
                media = MediaDto("READY", "application/zip", 2),
                metadata = BookMetadataDto("Book $i"),
            )
        }
        val server = QueueServer(books)
        try {
            replace(shared.basePreferences().incognitoMode, true)
            replace(reader.skipRead, false)
            replace(reader.skipFiltered, false)
            replace(reader.persistReaderSettingsChanges, false)
            replace(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            replace(reader.alwaysShowChapterTransition, true)
            replace(Injekt.get<EInkPreferences>().enabled, eInk)
            if (providerId == "komga") {
                check(sourcePreferences("source_${profile.id}").edit().putString("Address", server.address).commit())
            } else {
                KavitaPreferences(
                    profile.id,
                ).save(server.address, "fixture-key", KavitaAccount(1, "fixture", "fixture"))
            }
            source = withTimeout(10_000) {
                while (Injekt.get<SourceManager>().get(profile.id) !is HttpSource) delay(25)
                Injekt.get<SourceManager>().get(profile.id) as HttpSource
            }
            (source as? KavitaSource)?.reload()
            check(
                !(source as ConnectionDownloadStorageAdapter).usesSharedDownloadStorage &&
                    provider.findOwnedSourceDirs(source).isEmpty(),
            )
            val kavita = source as? KavitaSource
            suspend fun cached(group: String, payload: String) {
                val session = checkNotNull(kavita).session()
                Injekt.get<KavitaRepository>().putCache(
                    profile.id,
                    session.accountKey,
                    group,
                    KavitaCacheEntry("data", payload, System.currentTimeMillis()),
                )
            }
            if (kavita != null) cached("libraries", Json.encodeToString(listOf(KavitaLibrary(30, "Owned library"))))
            for (seriesId in listOf(10L, 20L)) {
                val url = kavita?.session()?.identity?.series(seriesId) ?: "${server.address}/api/v1/series/$seriesId"
                stored += mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = profile.id,
                            url = url,
                            title = "Series $seriesId",
                            initialized = true,
                            viewerFlags = mode.flagValue.toLong(),
                        ),
                    ),
                ).single()
                if (kavita != null) {
                    cached(
                        "series/$seriesId",
                        Json.encodeToString(
                            KavitaSeries(seriesId, name = "Series $seriesId", libraryId = 30),
                        ),
                    )
                }
            }
            val units = books.map { book ->
                val manga = stored.first { it.title == book.seriesTitle }
                val url =
                    kavita?.session()?.identity?.chapter(
                        KavitaChapterRef(30, book.seriesId.toLong(), 40, book.id.toLong(), 2),
                    )
                        ?: "${server.address}/api/v1/books/${book.id}"
                if (kavita != null) {
                    cached(
                        "chapter/${book.id}",
                        Json.encodeToString(
                            KavitaChapter(book.id.toLong(), volumeId = 40, title = book.name, pages = 2),
                        ),
                    )
                }
                chapters.addAll(
                    listOf(
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = url,
                            name = book.name,
                            chapterNumber = book.number.toDouble(),
                            memo = if (kavita == null) {
                                KomgaChapterMemo.mergeInto(
                                    existing = kotlinx.serialization.json.JsonObject(emptyMap()),
                                    baseUrl = server.address,
                                    book = book,
                                )
                            } else {
                                kotlinx.serialization.json.JsonObject(emptyMap())
                            },
                        ),
                    ),
                ).single()
            }
            val bytes = archive()
            units.forEach { unit ->
                val manga = stored.first { it.id == unit.mangaId }
                val directory = provider.getMangaDir(manga.title, source).getOrThrow()
                val name = provider.getChapterDirName(unit.name, unit.scanlator, unit.url) + ".cbz"
                check(directory.findFile(name) == null)
                checkNotNull(directory.createFile(name)).openOutputStream().use { it.write(bytes) }
                cache.addChapter(name, directory, manga)
            }
            val order = listOf(1, 0, 2)
            val queue = if (source is KomgaSource) {
                source.organizationRepository().createQueue(
                    KomgaOrganization("owned", "Fixture list"),
                    order.map {
                        books[it]
                    },
                )
            } else {
                checkNotNull(kavita).createReadingQueue(
                    "Fixture list",
                    order.mapIndexed { index, i ->
                        KavitaListItem(
                            id = i.toLong() + 1,
                            order = index,
                            chapterId = i.toLong() + 1,
                            seriesId = books[i].seriesId.toLong(),
                            volumeId = 40,
                            libraryId = 30,
                            title = books[i].name,
                            seriesFormat = 2,
                        )
                    },
                )
            }
            fun current(): ReaderActivity? {
                var found: ReaderActivity? = null
                instrumentation.runOnMainSync {
                    found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ReaderActivity>().firstOrNull()
                }
                return found
            }
            suspend fun awaitBook(index: Int): ReaderActivity {
                return withTimeout(20_000) {
                    var found: ReaderActivity? = null
                    while (found == null) {
                        current()?.let { activity ->
                            instrumentation.runOnMainSync {
                                if (activity.viewModel.state.value.viewer != null &&
                                    activity.viewModel.state.value.viewerChapters?.currChapter?.chapter?.id ==
                                    units[index].id &&
                                    activity.readingQueue.position != null
                                ) {
                                    found = activity
                                }
                            }
                        }
                        if (found == null) delay(50)
                    }
                    checkNotNull(found)
                }
            }
            suspend fun turnUntil(from: ReaderActivity, to: Int, forward: Boolean): ReaderActivity {
                repeat(16) {
                    if (current() !== from) return awaitBook(to)
                    instrumentation.runOnMainSync {
                        from.viewModel.state.value.viewer?.handleKeyEvent(
                            KeyEvent(
                                KeyEvent.ACTION_UP,
                                if (forward) KeyEvent.KEYCODE_PAGE_DOWN else KeyEvent.KEYCODE_PAGE_UP,
                            ),
                        )
                    }
                    delay(300)
                }
                error("Boundary did not navigate $providerId $mode to=$to forward=$forward")
            }
            val first = units[1]
            FixtureActivityLauncher.launch<ReaderActivity>(
                ConnectionReadingQueueController.attach(
                    ReaderActivity.newIntent(context, first.mangaId, first.id, profile.id),
                    profile.id,
                    queue,
                    first.url,
                ),
            ).use {
                var active = awaitBook(1)
                delay(700)
                assertEquals(active, current()) // Initial layout must not leave the current book.
                active = turnUntil(active, 0, true) // Series order is 1 -> 2; queue is 2 -> 1 -> 3.
                active = turnUntil(active, 1, false)
                active = turnUntil(active, 0, true)
                active = turnUntil(active, 2, true) // Cross-series target keeps its canonical manga.
                repeat(10) {
                    instrumentation.runOnMainSync {
                        active.viewModel.state.value.viewer?.handleKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN),
                        )
                    }
                    delay(100)
                }
                assertEquals(active, current())
                instrumentation.runOnMainSync {
                    assertTrue(active.viewModel.state.value.menuVisible)
                    active.finish()
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putString("queue_verified", "$providerId $mode eink=$eInk order=2,1,2,1,3 terminal=3")
                    },
                )
            }
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ReaderActivity>().forEach { it.finish() }
            }
            delay(400)
            source?.let { active -> stored.forEach { downloads.deleteManga(it, active, removeQueued = false) } }
            mangas.deleteMangaBySourceId(profile.id)
            profiles.remove(profile.id).getOrThrow()
            if (hadPrevious) connections.activeConnectionId.set(previous) else connections.activeConnectionId.delete()
            restores.asReversed().forEach { it() }
            server.close()
        }
    }

    private fun archive(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            repeat(2) { index ->
                val bitmap = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(if (index == 0) Color.WHITE else Color.LTGRAY)
                    zip.putNextEntry(ZipEntry("$index.png"))
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    zip.closeEntry()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        output.toByteArray()
    }

    private class QueueServer(private val books: List<BookDto>) : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        val address = "http://127.0.0.1:${socket.localPort}"
        init {
            workers.execute {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    workers.execute {
                        client.use {
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine().orEmpty().split(' ')
                            while (!reader.readLine().isNullOrEmpty()) { /* Consume fixture headers. */ }
                            val path = request.getOrElse(1) { "" }.substringBefore('?')
                            val order = listOf("2", "1", "3")
                            val id = path.substringBeforeLast('/').substringAfterLast('/')
                            val index = order.indexOf(id)
                            val target = if (path.endsWith("/next")) {
                                order.getOrNull(index + 1)
                            } else if (path.endsWith("/previous")) {
                                order.getOrNull(index - 1)
                            } else {
                                null
                            }
                            val book = books.firstOrNull { it.id == target }
                            val body = book?.let { Json.encodeToString(it) }.orEmpty().toByteArray()
                            val status = if (book != null) "200 OK" else "404 Not Found"
                            it.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                )
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            }
        }
        override fun close() {
            socket.close()
            workers.shutdownNow()
        }
    }
}
