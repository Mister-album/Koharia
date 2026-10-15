package eu.kanade.tachiyomi.data.track.komga

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.source.komga.KomgaProgressAccount
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.util.concurrent.CopyOnWriteArrayList

class KomgaOwnerRoutingTest {
    @Test
    fun `history and SSE refetch after materializing chapters instead of adopting the discovery response`() = runTest {
        for (route in listOf("history", "SSE")) {
            Fixture().use { fixture ->
                val manga = Manga.create().copy(id = 7, source = 2, url = "$BASE/api/v1/series/one")
                val chapter = tachiyomi.domain.chapter.model.Chapter.create().copy(
                    id = 8,
                    mangaId = manga.id,
                    url = "$BASE/api/v1/books/one",
                    read = true,
                )
                val before = koharia.domain.komga.KomgaRemoteProgressSnapshot(
                    2,
                    fixture.accounts.getValue(2L).key,
                    emptyMap(),
                    emptyMap(),
                    emptyMap(),
                    attempts = emptyMap(),
                )
                val after = before.copy(
                    chapters = mapOf(
                        chapter.id to koharia.domain.komga.KomgaRemoteChapterSnapshot(chapter, null, null, null, "one"),
                    ),
                )
                val events = mutableListOf<String>()
                var materialized = false
                coEvery { fixture.chapters.await(manga.id) } answers
                    { if (materialized) listOf(chapter) else emptyList() }
                coEvery { fixture.mangas.getMangaBySourceId(2L) } returns listOf(manga)
                coEvery { fixture.mangas.getMangaByUrlAndSourceId(manga.url, 2L) } returns manga
                coEvery { fixture.second.getChapterList(any()) } returns emptyList()
                coEvery { fixture.syncChapters.await(any(), manga, false, any()) } answers {
                    events += "materialize"
                    materialized = true
                    listOf(chapter)
                }
                coEvery { fixture.remoteProgress.capture(any(), any(), any(), any()) } answers {
                    events += "snapshot"
                    if (materialized) after else before
                }
                coEvery { fixture.remoteProgress.captureBook(any(), any(), any()) } answers {
                    events += "snapshot"
                    if (materialized) after else before
                }
                fixture.onRequest = {
                    events += "network"
                    val book = """{"id":"one","seriesId":"one",
                        "readProgress":{"page":4,"completed":${!materialized}}}"""
                    fixture.responseBody = if (route == "SSE") book else """{"content":[$book]}"""
                }
                coEvery { fixture.remoteProgress.apply(any(), any(), any()) } answers {
                    events += "commit"
                    setOf(chapter.id)
                }
                if (route == "history") {
                    fixture.service.syncHistoryFromServer(2L)
                } else {
                    fixture.service.syncBookProgress(2L, chapter.url)
                }
                assertEquals(
                    listOf("snapshot", "network", "materialize", "snapshot", "network", "commit"),
                    events,
                    route,
                )
                coVerify(exactly = 1) {
                    fixture.remoteProgress.apply(after, match { it.single().read == false }, any())
                }
            }
        }
    }

    @Test
    fun `background series refresh does not start while restore owns local state`() = runTest {
        Fixture().use { fixture ->
            koharia.connection.ConnectionRestoreState.duringRestore {
                fixture.service.syncFromServer(Manga.create().copy(id = 7, source = 2, url = "$BASE/api/v1/series/one"))
            }
            assertTrue(fixture.requests.isEmpty())
            coVerify(exactly = 0) { fixture.remoteProgress.capture(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `bound background requests bypass old response cache and reject replaced sources`() = runTest {
        Fixture().use { fixture ->
            fixture.api.getInProgressBookProgress(2L)
            val session = koharia.source.komga.captureKomgaProgressSession(fixture.second, fixture.sources)
            fixture.api.getInProgressBookProgress(session, includeCompleted = false)
            assertEquals(2, fixture.requests.size)
            fixture.onRequest = { every { fixture.sources.get(2L) } returns fixture.source(2L) }
            assertTrue(
                runCatching {
                    fixture.api.getInProgressBookProgress(session, includeCompleted = false)
                }.exceptionOrNull() is KomgaProgressOwnerChanged,
            )
            assertEquals(3, fixture.requests.size)
        }
        Fixture().use { fixture ->
            fixture.onRequest = { every { fixture.sources.get(2L) } returns fixture.source(2L) }
            assertTrue(
                runCatching {
                    fixture.api.getSeriesBookProgress("$BASE/api/v1/series/one", 2L)
                }.exceptionOrNull() is KomgaProgressOwnerChanged,
            )
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `series history and SSE commit the snapshot captured before their HTTP request`() = runTest {
        for (route in listOf("series", "history", "SSE")) {
            Fixture().use { fixture ->
                val manga = Manga.create().copy(id = 7, source = 2, url = "$BASE/api/v1/series/one")
                val chapter = tachiyomi.domain.chapter.model.Chapter.create().copy(
                    id = 8,
                    mangaId = manga.id,
                    url = "$BASE/api/v1/books/one",
                )
                val snapshot = koharia.domain.komga.KomgaRemoteProgressSnapshot(
                    2,
                    fixture.accounts.getValue(2L).key,
                    mapOf(8L to koharia.domain.komga.KomgaRemoteChapterSnapshot(chapter, null, null, null, "one")),
                    emptyMap(),
                    emptyMap(),
                    attempts = mapOf("one" to null),
                )
                val events = mutableListOf<String>()
                coEvery { fixture.chapters.await(manga.id) } returns listOf(chapter)
                coEvery { fixture.mangas.getMangaBySourceId(2L) } returns listOf(manga)
                coEvery { fixture.mangas.getMangaByUrlAndSourceId(manga.url, 2L) } returns manga
                coEvery { fixture.remoteProgress.capture(any(), any(), any(), any()) } answers {
                    events += "snapshot"
                    snapshot
                }
                coEvery { fixture.remoteProgress.captureBook(any(), any(), any()) } answers {
                    events += "snapshot"
                    snapshot
                }
                val book = """{"id":"one","seriesId":"one","readProgress":{"page":4,"completed":true}}"""
                fixture.responseBody = if (route == "SSE") book else """{"content":[$book]}"""
                fixture.onRequest = { events += "network" }
                coEvery { fixture.remoteProgress.apply(any(), any(), any()) } answers {
                    events += "commit"
                    setOf(chapter.id)
                }
                when (route) {
                    "series" -> fixture.service.syncFromServer(manga)
                    "history" -> fixture.service.syncHistoryFromServer(2L)
                    else -> fixture.service.syncBookProgress(2L, chapter.url)
                }
                assertEquals(listOf("snapshot", "network", "commit"), events, route)
                coVerify(exactly = 1) {
                    fixture.remoteProgress.apply(
                        snapshot,
                        match { changes ->
                            changes.single().chapterId == chapter.id && changes.single().read == true &&
                                changes.single().lastPageRead == 3L
                        },
                        any(),
                    )
                }
            }
        }
    }

    @Test
    fun `pending progress refuses a different account even when the book and baseline are identical`() = runTest {
        Fixture().use { fixture ->
            val owner = fixture.accounts.getValue(2L).key
            fixture.accounts[2L] = account(2L, "replacement")
            assertTrue(runCatching { fixture.retry(owner) }.exceptionOrNull() is KomgaProgressOwnerChanged)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `legacy work without an owner is held before any network request`() = runTest {
        Fixture().use { fixture ->
            assertTrue(runCatching { fixture.retry(null) }.exceptionOrNull() is KomgaProgressOwnerChanged)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `owned pending progress reads the baseline and writes only its captured account`() = runTest {
        Fixture().use { fixture ->
            fixture.retry(fixture.accounts.getValue(2L).key)
            assertEquals(listOf("GET", "PATCH"), fixture.requests.map { it.method })
            assertTrue(fixture.requests.all { it.header("Authorization") == Credentials.basic("second", "fixture") })
        }
    }

    @Test
    fun `account switch during baseline fetch rejects the late response and sends no write`() = runTest {
        Fixture().use { fixture ->
            val owner = fixture.accounts.getValue(2L).key
            fixture.onRequest = { fixture.accounts[2L] = account(2L, "replacement") }
            assertTrue(runCatching { fixture.retry(owner) }.exceptionOrNull() is KomgaProgressOwnerChanged)
            assertEquals(listOf("GET"), fixture.requests.map { it.method })
        }
    }

    @Test
    fun `progress cache is never reused after changing the same connection account`() = runTest {
        Fixture().use { fixture ->
            fixture.api.getInProgressBookProgress(2L)
            fixture.api.getInProgressBookProgress(2L)
            assertEquals(1, fixture.requests.size)
            fixture.accounts[2L] = account(2L, "replacement")
            fixture.api.getInProgressBookProgress(2L)
            assertEquals(2, fixture.requests.size)
            assertEquals(Credentials.basic("replacement", "fixture"), fixture.requests.last().header("Authorization"))
        }
    }

    @Test
    fun `same server series and book reads use explicit owner regardless of enumeration`() = runTest {
        Fixture().use { fixture ->
            for (reverse in listOf(false, true)) {
                every { fixture.sources.getOnlineSources() } returns
                    if (reverse) listOf(fixture.second, fixture.first) else listOf(fixture.first, fixture.second)
                fixture.api.getSeriesBookProgress("$BASE/api/v1/series/one", 2L)
                fixture.api.getBookProgress("$BASE/api/v1/books/one", 2L)
            }
            assertEquals(4, fixture.requests.size)
            assertTrue(fixture.requests.all { it.header("Authorization") == Credentials.basic("second", "fixture") })
        }
    }

    @Test
    fun `legacy tracker writes and subsequent reads use the manga owner`() = runTest {
        Fixture().use { fixture ->
            val url = "$BASE/api/v1/readlists/one"
            coEvery { fixture.mangas.getMangaByIdOrNull(7L) } returns
                Manga.create().copy(id = 7L, source = 2L, url = url)
            fixture.api.updateProgress(
                Track.create(6).apply {
                    manga_id = 7L
                    tracking_url = url
                    last_chapter_read = 1.0
                },
            )
            assertEquals(listOf("PUT", "GET", "GET"), fixture.requests.map { it.method })
            assertTrue(fixture.requests.all { it.header("Authorization") == Credentials.basic("second", "fixture") })
        }
    }

    @Test
    fun `manual tracker response never follows replacement credentials or Source`() = runTest {
        for (replaceAfter in listOf(1, 2, 3)) {
            Fixture().use { fixture ->
                val url = "$BASE/api/v1/readlists/one"
                coEvery { fixture.mangas.getMangaByIdOrNull(7L) } returns
                    Manga.create().copy(id = 7L, source = 2L, url = url)
                fixture.onRequest = {
                    if (fixture.requests.size == replaceAfter) {
                        every { fixture.sources.get(2L) } returns fixture.source(2L)
                    }
                }
                val failure = runCatching {
                    fixture.api.updateProgress(
                        Track.create(6).apply {
                            manga_id = 7L
                            tracking_url = url
                            last_chapter_read = 1.0
                        },
                    )
                }.exceptionOrNull()
                assertTrue(failure is KomgaProgressOwnerChanged)
                assertEquals(replaceAfter, fixture.requests.size)
                assertTrue(fixture.requests.all { it.tag(KomgaProgressAccount::class.java) == fixture.accounts[2L] })
            }
        }
    }

    @Test
    fun `manual aggregate cannot bypass unsettled page or explicit reading intents`() = runTest {
        Fixture().use { fixture ->
            val url = "$BASE/api/v1/readlists/one"
            coEvery { fixture.mangas.getMangaByIdOrNull(7L) } returns Manga.create().copy(id = 7, source = 2, url = url)
            val track = Track.create(6).apply {
                manga_id = 7
                tracking_url = url
            }
            fixture.unsettledPages = true
            assertTrue(
                runCatching { fixture.api.updateProgress(track) }.exceptionOrNull()
                    is koharia.connection.ConnectionReadStatusException,
            )
            fixture.unsettledPages = false
            fixture.recordUnread()
            assertTrue(
                runCatching { fixture.api.updateProgress(track) }.exceptionOrNull()
                    is koharia.connection.ConnectionReadStatusException,
            )
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `explicit read status preserves PATCH DELETE protocol and rejects obsolete owner responses`() = runTest {
        for (read in listOf(true, false)) {
            Fixture().use { fixture ->
                val session = koharia.source.komga.captureKomgaProgressSession(fixture.second, fixture.sources)
                fixture.api.setBookReadStatus(session, "$BASE/api/v1/books/one", read) {
                    session.guard.requireCurrent()
                }
                val request = fixture.requests.single()
                assertEquals(if (read) "PATCH" else "DELETE", request.method)
                assertEquals(fixture.accounts[2L], request.tag(KomgaProgressAccount::class.java))
                if (read) {
                    val payload = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    assertEquals("{\"completed\":true}", payload)
                }
                fixture.onRequest = { fixture.accounts[2L] = account(2, "replacement") }
                val failure = runCatching {
                    fixture.api.setBookReadStatus(session, "$BASE/api/v1/books/one", read) {
                        session.guard.requireCurrent()
                    }
                }.exceptionOrNull()
                assertTrue(failure is KomgaProgressOwnerChanged)
                assertEquals(2, fixture.requests.size)
                assertTrue(
                    fixture.requests.all {
                        it.header("Authorization") == Credentials.basic("second", "fixture")
                    },
                )
            }
        }
    }

    @Test
    fun `unowned or mismatched tracks never send a request`() = runTest {
        Fixture().use { fixture ->
            val track = Track.create(6).apply {
                manga_id = 7L
                tracking_url = "$BASE/api/v1/series/one"
            }
            coEvery { fixture.mangas.getMangaByIdOrNull(7L) } returns null
            assertOwnerRejected { fixture.api.updateProgress(track) }
            coEvery { fixture.mangas.getMangaByIdOrNull(7L) } returns
                Manga.create().copy(id = 7L, source = 2L, url = "$BASE/api/v1/series/other")
            assertOwnerRejected { fixture.api.updateProgress(track) }
            assertOwnerRejected {
                fixture.api.getTrackSearch("https://another.invalid/api/v1/series/one", 2L)
            }
            assertOwnerRejected { fixture.api.getSeriesBookProgress(track.tracking_url, 99L) }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `same account source replacement during page response rejects the old request`() = runTest {
        Fixture().use { fixture ->
            fixture.onRequest = { every { fixture.sources.get(2L) } returns fixture.source(2L) }
            val failure = runCatching { fixture.api.getBookProgress("$BASE/api/v1/books/one", 2L) }.exceptionOrNull()
            assertTrue(failure is KomgaProgressOwnerChanged)
            assertEquals(listOf("GET"), fixture.requests.map { it.method })
        }
    }

    @Test
    fun `durable revision change during comic response rejects the result before cache adoption`() = runTest {
        Fixture().use { fixture ->
            val intents = mockk<koharia.domain.komga.KomgaReadStatusRepository>()
            val key = koharia.domain.komga.KomgaReadStatusKey(2L, fixture.accounts.getValue(2L).key, "one")
            var revision = 0L
            coEvery { intents.get(key) } answers {
                if (revision == 0L) {
                    null
                } else {
                    mockk<koharia.domain.komga.KomgaReadStatusIntent> {
                        every { this@mockk.revision } returns revision
                    }
                }
            }
            val ordering = koharia.komga.testProgressOrdering(intents)
            val token = ordering.capture(key)
            val session = koharia.source.komga.captureKomgaProgressSession(fixture.second, fixture.sources)
            fixture.onRequest = { revision++ }
            val failure = runCatching {
                fixture.api.getBookProgress("$BASE/api/v1/books/one", session) { ordering.checkCurrent(token) }
            }.exceptionOrNull()
            assertTrue(failure is koharia.komga.progress.KomgaProgressSuperseded)
            assertEquals(listOf("GET"), fixture.requests.map { it.method })
        }
    }

    @Test
    fun `owned legacy work without revision is quarantined before any request`() = runTest {
        Fixture().use { fixture ->
            val error = runCatching { fixture.retry(fixture.accounts.getValue(2L).key, null) }.exceptionOrNull()
            assertTrue(error is koharia.komga.progress.KomgaProgressRevisionMissing)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `obsolete queued revision never fetches or pushes after an explicit unread`() = runTest {
        Fixture().use { fixture ->
            fixture.recordUnread()
            val error = runCatching { fixture.retry(fixture.accounts.getValue(2L).key) }.exceptionOrNull()
            assertTrue(error is koharia.komga.progress.KomgaProgressSuperseded)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `explicit action during queued baseline fetch prevents the old patch`() = runTest {
        Fixture().use { fixture ->
            fixture.onRequest = { fixture.recordUnread() }
            val error = runCatching { fixture.retry(fixture.accounts.getValue(2L).key) }.exceptionOrNull()
            assertTrue(error is koharia.komga.progress.KomgaProgressSuperseded)
            assertEquals(listOf("GET"), fixture.requests.map { it.method })
        }
    }

    @Test
    fun `queued page at an unsettled explicit revision cannot send`() = runTest {
        Fixture().use { fixture ->
            fixture.recordUnread()
            val error = runCatching { fixture.retry(fixture.accounts.getValue(2L).key, 1) }.exceptionOrNull()
            assertTrue(error is koharia.komga.progress.KomgaProgressOnHold)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    private class Fixture : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        val accounts = mutableMapOf(
            1L to account(1L, "first"),
            2L to account(2L, "second"),
        )
        var onRequest: (Request) -> Unit = {}
        var responseBody: String? = null
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            onRequest(request)
            val body = responseBody ?: when {
                request.method == "PUT" -> "{}"
                request.url.encodedPath.endsWith("/read-progress/tachiyomi") ->
                    """{"booksCount":2,"booksReadCount":1,"booksUnreadCount":1,"booksInProgressCount":0,"lastReadContinuousIndex":1}"""
                request.url.encodedPath.contains("/readlists/") ->
                    """{"id":"one","name":"Fixture","bookIds":[],"createdDate":"","lastModifiedDate":"","filtered":false}"""
                request.url.encodedPath.contains("/series/") || request.url.encodedPath.endsWith("/books") ->
                    """{"content":[]}"""
                else ->
                    """{"id":"one","seriesId":"one","media":{"pagesCount":10},
                    "readProgress":{"page":3,"completed":false}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val first = source(1L)
        val second = source(2L)
        val sources = mockk<SourceManager>().also { manager ->
            every { manager.get(any<Long>()) } answers {
                when (firstArg<Long>()) {
                    1L -> first
                    2L -> second
                    else -> null
                }
            }
        }
        val mangas = mockk<MangaRepository>()
        val api = KomgaApi(6L, client).apply {
            dependency("sourceManager", sources)
            dependency("mangaRepository", mangas)
            dependency("json", Json { ignoreUnknownKeys = true })
        }

        val remoteProgress = mockk<koharia.domain.komga.KomgaRemoteProgressRepository>()
        val chapters = mockk<tachiyomi.domain.chapter.interactor.GetChaptersByMangaId>()
        val syncChapters = mockk<eu.kanade.domain.chapter.interactor.SyncChaptersWithSource>()
        val service by lazy {
            val trackers = mockk<TrackerManager>()
            every { trackers.komga.api } returns api
            KomgaProgressSyncService(
                trackers, chapters, mangas, syncChapters, sources,
                mockk(), mockk(), mockk(), remoteProgress,
            )
        }

        private var intent: koharia.domain.komga.KomgaReadStatusIntent? = null
        private val intents = mockk<koharia.domain.komga.KomgaReadStatusRepository>().also { repository ->
            coEvery { repository.get(any()) } answers { intent }
            coEvery { repository.forOwner(any(), any()) } answers { listOfNotNull(intent) }
        }
        private val ordering = koharia.komga.testProgressOrdering(intents)
        var unsettledPages = false
        init {
            api.dependency("ordering", ordering)
            api.dependency(
                "pageEvents",
                mockk<koharia.domain.komga.KomgaPageEventRepository> {
                    coEvery { hasUnsettled(any(), any()) } answers { unsettledPages }
                },
            )
        }

        fun recordUnread() {
            intent = koharia.domain.komga.KomgaReadStatusIntent(
                koharia.domain.komga.KomgaReadStatusKey(2L, accounts.getValue(2L).key, "one"),
                1,
                false,
                100,
                null,
                koharia.domain.komga.KomgaReadStatusPhase.PENDING,
            )
        }

        suspend fun retry(owner: String?, revision: Long? = 0) = service.retryPageProgress(
            2L, "$BASE/api/v1/books/one", 4, 10, 2, false, null, -1, owner, revision, ordering,
        )

        fun source(sourceId: Long) = mockk<KomgaSource>().also { source ->
            every { source.id } returns sourceId
            every { source.baseUrl } returns BASE
            every { source.client } returns client
            every { source.currentHeaders() } answers { accounts.getValue(sourceId).headers }
            every { source.progressAccount() } answers { accounts.getValue(sourceId) }
            every { source.captureChapterOwner() } answers {
                val captured = accounts.getValue(sourceId)
                koharia.connection.ConnectionChapterOwner(captured.key) {
                    captured.requireCurrent(accounts.getValue(sourceId).key)
                }
            }
        }

        override fun close() {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    companion object {
        private const val BASE = "https://komga.invalid"

        private fun account(id: Long, user: String) =
            KomgaProgressAccount(id, BASE, "", "Credentials", user, "fixture", "")

        private suspend fun assertOwnerRejected(block: suspend () -> Unit) {
            assertTrue(runCatching { block() }.exceptionOrNull() is IllegalStateException)
        }

        private fun KomgaApi.dependency(name: String, value: Any) {
            KomgaApi::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                .set(this, lazyOf(value))
        }
    }
}
