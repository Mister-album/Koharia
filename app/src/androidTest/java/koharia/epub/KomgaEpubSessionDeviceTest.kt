package koharia.epub

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.interactor.GetEpubRemoteProgressCache
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.epub.repository.EpubProgressRepository
import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository
import koharia.domain.komga.KomgaReadStatusChapter
import koharia.domain.komga.KomgaReadStatusRepository
import koharia.domain.komga.KomgaRemoteProgressChange
import koharia.domain.komga.KomgaRemoteProgressRepository
import koharia.domain.komga.KomgaRemoteProgressSnapshot
import koharia.epub.progress.KomgaEpubProgressSyncService
import koharia.epub.progress.KomgaEpubRemoteProgressCoordinator
import koharia.komga.progress.KomgaProgressOrdering
import koharia.komga.progress.KomgaProgressSuperseded
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class KomgaEpubSessionDeviceTest {
    @Test
    fun manualTrackerKeepsOneAccountThroughPutAndBothGets(): Unit = runBlocking(Dispatchers.IO) {
        for (replaceAfter in 0..3) {
            withCoordinator(collectionPath = "/api/v1/readlists/fixture") {
                val api = eu.kanade.tachiyomi.data.track.komga.KomgaApi(6, source.client)
                api.javaClass.getDeclaredField("sourceManager\$delegate").apply {
                    isAccessible = true
                    set(api, lazyOf(sources))
                }
                server.beforeReply = {
                    server.body = if (server.lastRequest.get().contains("/read-progress/tachiyomi")) {
                        """{"booksCount":2,"booksReadCount":1,"booksUnreadCount":1,
                            "booksInProgressCount":0,"lastReadContinuousIndex":1}"""
                    } else {
                        """{"id":"fixture","name":"Fixture","bookIds":[],
                            "createdDate":"","lastModifiedDate":"","filtered":false}"""
                    }
                    if (server.requests.get() == replaceAfter) {
                        check(
                            sourcePreferences(
                                "source_${source.id}",
                            ).edit().putString("Password", "replacement").commit(),
                        )
                    }
                }
                val result = runCatching {
                    api.updateProgress(
                        eu.kanade.tachiyomi.data.database.models.Track.create(6).apply {
                            manga_id = manga.id
                            tracking_url = manga.url
                            last_chapter_read = 1.0
                        },
                    )
                }
                if (replaceAfter == 0) {
                    assertEquals(1.0, result.getOrThrow().last_chapter_read, 0.0)
                    assertEquals(3, server.requests.get())
                } else {
                    assertTrue(result.exceptionOrNull() is KomgaProgressOwnerChanged)
                    assertEquals(replaceAfter, server.requests.get())
                }
                assertTrue(server.fixtureAuthorized.get())
                assertEquals(1, JSONObject(server.lastPush.get()).getInt("lastBookRead"))
            }
        }
    }

    @Test
    fun explicitReadStatusUsesCapturedAccountForRealPatchAndDelete(): Unit = runBlocking(Dispatchers.IO) {
        withCoordinator {
            val api = eu.kanade.tachiyomi.data.track.komga.KomgaApi(6, source.client)
            val session = koharia.source.komga.captureKomgaProgressSession(source, sources)
            api.setBookReadStatus(session, chapter.url, true, session.guard::requireCurrent)
            assertEquals("PATCH /api/v1/books/fixture/read-progress HTTP/1.1", server.lastRequest.get())
            assertEquals(true, JSONObject(server.lastBody.get()).getBoolean("completed"))
            assertTrue(server.fixtureAuthorized.get())
            api.setBookReadStatus(session, chapter.url, false, session.guard::requireCurrent)
            assertEquals("DELETE /api/v1/books/fixture/read-progress HTTP/1.1", server.lastRequest.get())
            assertEquals("", server.lastBody.get())
            server.beforeReply = {
                check(sourcePreferences("source_${source.id}").edit().putString("Password", "replacement").commit())
            }
            val failed = runCatching {
                api.setBookReadStatus(session, chapter.url, true, session.guard::requireCurrent)
            }
            assertTrue(failed.exceptionOrNull() is KomgaProgressOwnerChanged)
            assertEquals(3, server.requests.get())
            assertTrue(server.fixtureAuthorized.get())
            assertTrue(
                runCatching {
                    api.setBookReadStatus(session, chapter.url, false, session.guard::requireCurrent)
                }.exceptionOrNull() is KomgaProgressOwnerChanged,
            )
            assertEquals(3, server.requests.get())
        }
    }

    @Test
    fun offlinePageEventSuppressesWarmCacheAndRejectsConcurrentEpubResponse(): Unit = runBlocking(Dispatchers.IO) {
        withCoordinator {
            val pages = Injekt.get<koharia.domain.komga.KomgaPageEventRepository>()
            val previous = cached("old", Date())
            cache.upsert(previous)
            val offline = checkNotNull(pages.record(key, 0, 2, 10, 100, null) {})
            assertNull(coordinator.get(chapter.id, source))
            assertNull(coordinator.refreshChapter(manga.id, chapter, source))
            assertEquals(0, server.requests.get())
            val attempt = checkNotNull(pages.begin(offline, "opening") {})
            pages.settle(attempt, koharia.domain.komga.KomgaReadStatusPhase.SYNCED, "confirmed") {}
            server.body = """{"locator":{"href":"old.xhtml","type":"application/xhtml+xml"},
                "modified":"2026-01-01T00:00:00Z"}"""
            server.beforeReply = {
                runBlocking { pages.record(key, 0, 3, 10, 101, "confirmed") {} }
            }
            val response = runCatching { coordinator.refreshChapter(manga.id, chapter, source) }
            assertTrue(response.exceptionOrNull() is KomgaProgressSuperseded)
            assertEquals(1, server.requests.get())
            assertEquals(previous, cache.getByChapterId(chapter.id))
            assertEquals(3L, pages.get(key)?.page)
        }
    }

    @Test
    fun coordinatorCommitsFreshProgressButRejectsConcurrentReadingAndLateCacheWrites(): Unit = runBlocking(
        Dispatchers.IO,
    ) {
        withCoordinator {
            server.body = """{"locator":{"href":"fresh.xhtml","type":"application/xhtml+xml",
                "locations":{"totalProgression":0.25}},"modified":"2026-01-01T00:00:00Z"}"""
            val saved = coordinator.refreshChapter(manga.id, chapter, source)!!
            assertTrue(saved.locatorJson!!.contains("fresh.xhtml"))
            assertEquals(saved, cache.getByChapterId(chapter.id))

            server.beforeReply = { runBlocking { epub.upsertProgress(localProgress) } }
            val failed = runCatching { coordinator.refreshChapter(manga.id, chapter, source) }
            assertTrue(failed.exceptionOrNull() is KomgaProgressSuperseded)
            assertEquals(saved, cache.getByChapterId(chapter.id))
            assertEquals(localProgress, epub.getProgress(chapter.id))

            server.beforeReply = {}
            val newer = saved.copy(locatorJson = "newer-cache", checkedAt = Date(saved.checkedAt.time + 1))
            val interleaved = object : KomgaRemoteProgressRepository by remote {
                override suspend fun apply(
                    snapshot: KomgaRemoteProgressSnapshot,
                    changes: List<KomgaRemoteProgressChange>,
                    checkCurrent: suspend () -> Unit,
                ): Set<Long> {
                    if (changes.any { it.epubCache != null }) cache.upsert(newer)
                    return remote.apply(snapshot, changes, checkCurrent)
                }
            }
            assertNull(coordinator(interleaved).refreshChapter(manga.id, chapter, source))
            assertEquals(newer, cache.getByChapterId(chapter.id))
        }
    }

    @Test
    fun warmCacheAndFailedRequestsNeverReturnAnObsoleteSnapshot(): Unit = runBlocking(Dispatchers.IO) {
        withCoordinator {
            val previous = cached("old", Date())
            cache.upsert(previous)
            val newer = previous.copy(locatorJson = "newer", checkedAt = Date(previous.checkedAt.time + 1))
            val interleaved = object : KomgaRemoteProgressRepository by remote {
                override suspend fun isCurrent(
                    snapshot: KomgaRemoteProgressSnapshot,
                    chapterId: Long,
                    bookId: String,
                    checkCurrent: suspend () -> Unit,
                ): Boolean {
                    cache.upsert(newer)
                    return remote.isCurrent(snapshot, chapterId, bookId, checkCurrent)
                }
            }
            assertNull(coordinator(interleaved).syncManga(manga.id, source, listOf(chapter)).firstOrNull())
            assertEquals(0, server.requests.get())
            assertEquals(newer, cache.getByChapterId(chapter.id))

            cache.upsert(previous)
            server.status = 503
            server.beforeReply = { runBlocking { cache.upsert(newer) } }
            assertNull(coordinator.refreshChapter(manga.id, chapter, source))
            assertEquals(newer, cache.getByChapterId(chapter.id))
            assertEquals(1, server.requests.get())
        }
    }

    @Test
    fun pendingIntentHidesCachedProgressAndLateUnreadRejectsServerClockEvidence(): Unit = runBlocking(Dispatchers.IO) {
        withCoordinator {
            val previous = cached("old", Date())
            cache.upsert(previous)
            intents.record(key, true, 100, null, listOf(KomgaReadStatusChapter(chapter.id, chapter.url))) {}
            assertNull(coordinator.get(chapter.id, source))
            assertNull(coordinator.refreshChapter(manga.id, chapter, source))
            assertEquals(0, server.requests.get())
            intents.removeConnection(manga.source)

            server.body = """{"locator":{"href":"old.xhtml","type":"application/xhtml+xml"},
                "modified":"2026-01-01T01:00:00Z"}"""
            server.date = "Thu, 01 Jan 2026 00:00:00 GMT"
            shared.epubReaderPreferences().correctRemoteServerTimestamps.set(true)
            server.beforeReply = {
                runBlocking {
                    intents.record(key, false, 200, null, listOf(KomgaReadStatusChapter(chapter.id, chapter.url))) {}
                }
            }
            val failed = runCatching { coordinator.refreshChapter(manga.id, chapter, source) }
            assertTrue(failed.exceptionOrNull() is KomgaProgressSuperseded)
            assertNull(cache.getByChapterId(chapter.id))
            val offset = shared.epubReaderPreferences().remoteServerTimestampOffsetMinutes(manga.source, key.ownerKey)
            assertEquals(0L, offset.get())
        }
    }

    @Test
    fun protocolAndClockEvidenceStayWithTheCapturedOwner(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val sourceName = "source_$id"
        val settingsName = "epub-session-fixture-$id"
        val sourcePrefs = sourcePreferences(sourceName)
        check(sourcePrefs.all.isEmpty())
        val store = AndroidPreferenceStore(context, context.getSharedPreferences(settingsName, 0))
        val shared = SharedAppPreferences(context.applicationContext as Application, store)
        shared.epubReaderPreferences().correctRemoteServerTimestamps.set(true)
        val base = BasePreferences(context, store)
        base.installationId.set("epub-session-fixture")
        try {
            Server().use { server ->
                check(
                    sourcePrefs.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "first").putString("Password", "fixture").commit(),
                )
                val active = AtomicReference<KomgaSource?>(KomgaSource(id))
                val sources = object : SourceManager by Injekt.get<SourceManager>() {
                    override fun get(sourceKey: Long) = active.get().takeIf { sourceKey == id }
                }
                val service = KomgaEpubProgressSyncService(base, sources, shared)
                val ownerA = active.get()!!.progressAccount().key
                val book = "${server.root}/api/v1/books/book"
                val baseTime = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
                val locatorJson = """
                    {"href":"chapter.xhtml","type":"application/xhtml+xml",
                     "locations":{"totalProgression":0.25,"position":1}}
                """.trimIndent()
                server.body = """{"locator":$locatorJson,"modified":"2026-01-01T01:00:00Z"}"""
                server.date = "Thu, 01 Jan 2026 00:00:00 GMT"
                assertEquals(baseTime, service.pullProgression(id, book).progression!!.modifiedAt.time)
                assertEquals(60L, shared.epubReaderPreferences().remoteServerTimestampOffsetMinutes(id, ownerA).get())

                val locator = requireNotNull(Locator.fromJSON(JSONObject(locatorJson)))
                service.pushProgression(id, book, locator, emptyList(), Date(baseTime))
                assertTrue(server.lastPush.get().contains("\"device\""))
                assertTrue(server.lastPush.get().contains("chapter.xhtml"))
                check(sourcePrefs.edit().putString("Username", "second").commit())
                active.set(KomgaSource(id))
                val ownerB = active.get()!!.progressAccount().key
                server.date = null
                assertEquals(baseTime + 3_600_000, service.pullProgression(id, book).progression!!.modifiedAt.time)
                assertEquals(0L, shared.epubReaderPreferences().remoteServerTimestampOffsetMinutes(id, ownerB).get())
                assertEquals(60L, shared.epubReaderPreferences().remoteServerTimestampOffsetMinutes(id, ownerA).get())

                server.beforeReply = { active.set(KomgaSource(id)) }
                val failure = runCatching { service.pullProgression(id, book) }.exceptionOrNull()
                assertTrue(failure is KomgaProgressOwnerChanged)
                assertEquals(0L, shared.epubReaderPreferences().remoteServerTimestampOffsetMinutes(id, ownerB).get())
            }
        } finally {
            context.deleteSharedPreferences(sourceName)
            context.deleteSharedPreferences(settingsName)
        }
    }

    @Test
    fun invalidatedSessionRollsBackRemoteCacheCommit(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val cache = Injekt.get<EpubRemoteProgressCacheRepository>()
        val id = System.nanoTime()
        val manga = mangaRepository.insertNetworkManga(
            listOf(
                Manga.create().copy(
                    source = id,
                    url = "/epub-session-$id",
                    title = "EPUB session fixture",
                ),
            ),
        ).single()
        try {
            val chapter = chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = "https://komga.test/api/v1/books/book",
                        name = "Fixture",
                    ),
                ),
            ).single()
            val old =
                EpubRemoteProgressCache(chapter.id, manga.id, chapter.url, null, null, null, null, Date(100), null)
            cache.upsert(old)
            var checks = 0
            val failed = runCatching {
                cache.upsertForSession(old.copy(locatorJson = "obsolete", checkedAt = Date(200))) {
                    if (++checks == 2) throw KomgaProgressOwnerChanged()
                }
            }
            assertTrue(failed.exceptionOrNull() is KomgaProgressOwnerChanged)
            assertEquals(old, cache.getByChapterId(chapter.id))
        } finally {
            mangaRepository.deleteMangaById(manga.id)
        }
    }

    private class CoordinatorFixture(
        val source: KomgaSource,
        val manga: Manga,
        val chapter: Chapter,
        val server: Server,
        val shared: SharedAppPreferences,
        val service: KomgaEpubProgressSyncService,
        val sources: SourceManager,
    ) {
        val remote = Injekt.get<KomgaRemoteProgressRepository>()
        val cache = Injekt.get<EpubRemoteProgressCacheRepository>()
        val epub = Injekt.get<EpubProgressRepository>()
        val intents = Injekt.get<KomgaReadStatusRepository>()
        val key = KomgaProgressOrdering.key(source.id, source.progressAccount().key, chapter.url)
        val coordinator get() = coordinator(remote)
        val localProgress get() = EpubProgress(chapter.id, manga.id, chapter.url, "local", 0.8, 8, Date(1000), null)
        fun coordinator(repository: KomgaRemoteProgressRepository) = KomgaEpubRemoteProgressCoordinator(
            service,
            GetEpubRemoteProgressCache(cache),
            repository,
            shared,
        )
        fun cached(locator: String, checkedAt: Date) = EpubRemoteProgressCache(
            chapter.id, manga.id, chapter.url, locator, null, null, null, checkedAt, null,
        )
    }

    private suspend fun withCoordinator(
        collectionPath: String = "/api/v1/series/fixture",
        block: suspend CoordinatorFixture.() -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val sourceName = "source_$id"
        val settingsName = "epub-coordinator-fixture-$id"
        val sourcePrefs = sourcePreferences(sourceName)
        check(sourcePrefs.all.isEmpty())
        val store = AndroidPreferenceStore(context, context.getSharedPreferences(settingsName, 0))
        val shared = SharedAppPreferences(context.applicationContext as Application, store)
        val mangas = Injekt.get<MangaRepository>()
        try {
            Server().use { server ->
                check(
                    sourcePrefs.edit().putString("Address", server.root).putString("AuthMode", "Credentials")
                        .putString("Username", "fixture").putString("Password", "fixture").commit(),
                )
                val source = KomgaSource(id)
                val sources = object : SourceManager by Injekt.get<SourceManager>() {
                    override fun get(sourceKey: Long) = source.takeIf { sourceKey == id }
                }
                val manga = mangas.insertNetworkManga(
                    listOf(
                        Manga.create().copy(
                            source = id,
                            url = "${server.root}$collectionPath",
                            title = "Coordinator fixture",
                        ),
                    ),
                ).single()
                try {
                    val chapter = Injekt.get<ChapterRepository>().addAll(
                        listOf(
                            Chapter.create().copy(
                                mangaId = manga.id,
                                url = "${server.root}/api/v1/books/fixture",
                                name = "Fixture",
                            ),
                        ),
                    ).single()
                    val service = KomgaEpubProgressSyncService(BasePreferences(context, store), sources, shared)
                    CoordinatorFixture(source, manga, chapter, server, shared, service, sources).block()
                } finally {
                    Injekt.get<KomgaReadStatusRepository>().removeConnection(id)
                    Injekt.get<koharia.domain.komga.KomgaPageEventRepository>().removeConnection(id)
                    mangas.deleteMangaById(manga.id)
                }
            }
        } finally {
            context.deleteSharedPreferences(sourceName)
            context.deleteSharedPreferences(settingsName)
        }
    }

    private class Server : AutoCloseable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val root = "http://127.0.0.1:${listener.localPort}"

        @Volatile var body = "\"\""

        @Volatile var status = 200
        val requests = AtomicInteger()

        @Volatile var date: String? = null

        @Volatile var beforeReply: () -> Unit = {}
        val lastPush = AtomicReference("")
        val lastRequest = AtomicReference("")
        val lastBody = AtomicReference("")
        val fixtureAuthorized = java.util.concurrent.atomic.AtomicBoolean()
        private val worker = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val byte = input.read()
                                if (byte == -1 || byte == 10) break
                                if (byte != 13) append(byte.toChar())
                            }
                        }
                        val request = line()
                        requests.incrementAndGet()
                        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        val bytes = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < bytes.size) {
                            val count = input.read(bytes, offset, bytes.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        val put = request.startsWith("PUT ")
                        lastRequest.set(request)
                        lastBody.set(bytes.decodeToString())
                        fixtureAuthorized.set(
                            headers["authorization"] == okhttp3.Credentials.basic("fixture", "fixture"),
                        )
                        if (put) lastPush.set(bytes.decodeToString())
                        beforeReply()
                        val response = (if (put) "{}" else body).toByteArray()
                        val dateHeader = date?.let { "Date: $it\r\n" }.orEmpty()
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Fixture\r\n${dateHeader}Content-Type: application/json\r\n" +
                                        "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(response)
                            flush()
                        }
                    }
                } catch (error: Exception) {
                    if (!listener.isClosed) throw error
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        override fun close() {
            listener.close()
            worker.join(5_000)
        }
    }
}
