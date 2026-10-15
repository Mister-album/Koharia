package koharia.epub.progress

import com.sun.net.httpserver.HttpServer
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.network.HttpException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.connection.MutableTestPreferenceStore
import koharia.connection.SharedAppPreferences
import koharia.domain.epub.interactor.GetEpubRemoteProgressCache
import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository
import koharia.epub.settings.EpubReaderPreferences
import koharia.source.komga.KomgaProgressAccount
import koharia.source.komga.KomgaProgressAccountInterceptor
import koharia.source.komga.KomgaProgressOwnerChanged
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager
import java.net.InetSocketAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class KomgaEpubSessionTest {
    @Test
    fun `intent changed during HTTP response cannot update timestamp evidence or return a locator`() = runBlocking {
        Fixture().use { fixture ->
            val cache = mockk<EpubRemoteProgressCacheRepository>()
            val intents = mockk<koharia.domain.komga.KomgaReadStatusRepository>()
            val revision = AtomicReference<koharia.domain.komga.KomgaReadStatusIntent?>(null)
            coEvery { intents.get(any()) } coAnswers { revision.get() }
            val ordering = koharia.komga.testProgressOrdering(intents)
            val binding = captureKomgaEpubProgressSession(
                fixture.original,
                chapter(fixture),
                fixture.service,
                coordinator(fixture, cache),
                ordering,
            )
            fixture.body =
                """{"locator":{"href":"chapter.xhtml","type":"application/xhtml+xml"},"modified":"2026-01-01T00:00:00Z"}"""
            fixture.beforeReply = {
                revision.set(
                    koharia.domain.komga.KomgaReadStatusIntent(
                        koharia.komga.progress.KomgaProgressOrdering.key(
                            42,
                            fixture.account.get().key,
                            fixture.bookUrl,
                        ),
                        1,
                        false,
                        100,
                        null,
                        koharia.domain.komga.KomgaReadStatusPhase.PENDING,
                    ),
                )
            }
            assertThrows(koharia.komga.progress.KomgaProgressSuperseded::class.java) {
                runBlocking { binding.adapter.reviewEpubProgress(fixture.bookUrl) }
            }
            assertEquals(1, fixture.requests.get())
        }
    }

    @Test
    fun `reader binding rejects new explicit intent before another network request`() = runBlocking {
        Fixture().use { fixture ->
            val cache = mockk<EpubRemoteProgressCacheRepository>()
            val intents = mockk<koharia.domain.komga.KomgaReadStatusRepository>()
            var revision: koharia.domain.komga.KomgaReadStatusIntent? = null
            coEvery { intents.get(any()) } coAnswers { revision }
            val ordering = koharia.komga.testProgressOrdering(intents)
            val binding = captureKomgaEpubProgressSession(
                fixture.original,
                chapter(fixture),
                fixture.service,
                coordinator(fixture, cache),
                ordering,
            )
            assertNull(binding.adapter.reviewEpubProgress(fixture.bookUrl))
            revision = koharia.domain.komga.KomgaReadStatusIntent(
                koharia.komga.progress.KomgaProgressOrdering.key(42, fixture.account.get().key, fixture.bookUrl),
                1,
                false,
                100,
                null,
                koharia.domain.komga.KomgaReadStatusPhase.PENDING,
            )
            assertThrows(koharia.komga.progress.KomgaProgressSuperseded::class.java) {
                runBlocking { binding.adapter.pullEpubProgress(fixture.bookUrl) }
            }
            assertThrows(koharia.komga.progress.KomgaProgressSuperseded::class.java) {
                runBlocking { binding.adapter.getCachedEpubProgress(1) }
            }
            assertEquals(1, fixture.requests.get())
            coVerify(exactly = 0) { cache.getByChapterId(any()) }
        }
    }

    @Test
    fun `reader binding never adopts new credentials between progress operations`() = runBlocking {
        Fixture().use { fixture ->
            val repository = mockk<EpubRemoteProgressCacheRepository>()
            val intents = mockk<koharia.domain.komga.KomgaReadStatusRepository>()
            coEvery { intents.get(any()) } returns null
            val ordering = koharia.komga.testProgressOrdering(intents)
            val binding = captureKomgaEpubProgressSession(
                fixture.original,
                Chapter.create().copy(id = 1, url = fixture.bookUrl),
                fixture.service,
                coordinator(fixture, repository),
                ordering,
            )
            runBlocking { assertNull(binding.adapter.pullEpubProgress(fixture.bookUrl)) }
            fixture.account.set(fixture.account("second"))
            assertThrows(KomgaProgressOwnerChanged::class.java) { runBlocking { binding.checkCurrent() } }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { binding.adapter.pullEpubProgress(fixture.bookUrl) }
            }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { binding.adapter.getCachedEpubProgress(1) }
            }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { binding.adapter.pushEpubProgress(fixture.bookUrl, mockk(), emptyList(), Date()) }
            }
            assertEquals(1, fixture.requests.get())
            coVerify(exactly = 0) { repository.getByChapterId(any()) }
            val reopened = captureKomgaEpubProgressSession(
                fixture.original,
                Chapter.create().copy(id = 1, url = fixture.bookUrl),
                fixture.service,
                coordinator(fixture, repository),
                ordering,
            )
            runBlocking { reopened.adapter.pullEpubProgress(fixture.bookUrl) }
            assertEquals(Credentials.basic("second", "fixture"), fixture.auth.get())
            assertEquals(2, fixture.requests.get())
        }
    }

    @Test
    fun `EPUB requests use captured authentication and stale accounts cannot deliver responses`() {
        Fixture().use { fixture ->
            runBlocking { assertNull(fixture.service.pullProgression(42, fixture.bookUrl).progression) }
            assertEquals(Credentials.basic("first", "fixture"), fixture.auth.get())
            fixture.beforeReply = { fixture.account.set(fixture.account("second")) }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { fixture.service.pullProgression(42, fixture.bookUrl) }
            }
            assertEquals(2, fixture.requests.get())
        }
    }

    @Test
    fun `same-credential source replacement invalidates an existing request session before sending`() {
        Fixture().use { fixture ->
            val session = fixture.service.captureSession(42)
            fixture.active.set(mockk())
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { fixture.service.pullProgression(session, fixture.bookUrl) }
            }
            assertEquals(0, fixture.requests.get())
        }
    }

    @Test
    fun `source removal during a request is not an empty successful progress response`() {
        Fixture().use { fixture ->
            fixture.beforeReply = { fixture.active.set(null) }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { fixture.service.pullProgression(42, fixture.bookUrl) }
            }
            assertThrows(KomgaProgressOwnerChanged::class.java) { fixture.service.captureSession(42) }
        }
    }

    @Test
    fun `foreign resource and redirect destinations cannot receive bound credentials`() {
        Fixture().use { fixture ->
            Fixture().use { foreign ->
                assertThrows(KomgaProgressOwnerChanged::class.java) {
                    runBlocking { fixture.service.pullProgression(42, foreign.bookUrl) }
                }
                fixture.redirect = foreign.bookUrl + "/progression"
                assertThrows(KomgaProgressOwnerChanged::class.java) {
                    runBlocking { fixture.service.pullProgression(42, fixture.bookUrl) }
                }
                assertEquals(0, foreign.requests.get())
                assertEquals(1, fixture.requests.get())
            }
        }
    }

    @Test
    fun `error bodies cannot leak into progress exceptions`() {
        Fixture().use { fixture ->
            fixture.status = 403
            fixture.body = "fixture-private-error-canary"
            val error = assertThrows(HttpException::class.java) {
                runBlocking { fixture.service.pullProgression(42, fixture.bookUrl) }
            }
            assertEquals(403, error.code)
            assertFalse(error.message.orEmpty().contains("fixture-private-error-canary"))
        }
    }

    @Test
    fun `learned timestamp offsets are isolated and never inherit legacy unowned evidence`() {
        val store = MutableTestPreferenceStore(
            mutableMapOf(
                "epub_reader_komga_server_timestamp_offset_minutes_42" to 120L,
            ),
        )
        val preferences = EpubReaderPreferences(store)
        assertEquals(0L, preferences.remoteServerTimestampOffsetMinutes(42, "first").get())
        preferences.remoteServerTimestampOffsetMinutes(42, "first").set(60)
        assertEquals(0L, preferences.remoteServerTimestampOffsetMinutes(42, "second").get())
        assertEquals(0L, preferences.remoteServerTimestampOffsetMinutes(43, "first").get())
        assertEquals(60L, EpubReaderPreferences(store).remoteServerTimestampOffsetMinutes(42, "first").get())
    }

    @Test
    fun `coordinator never falls back to cache or persists after owner rejection`() {
        Fixture().use { fixture ->
            val repository = mockk<EpubRemoteProgressCacheRepository>()
            val cached = EpubRemoteProgressCache(1, 2, fixture.bookUrl, null, null, null, null, Date(), null)
            coEvery { repository.getByChapterId(1) } returns cached
            val coordinator = coordinator(fixture, repository)
            fixture.beforeReply = { fixture.active.set(null) }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking {
                    coordinator.syncManga(2, fixture.original, listOf(chapter(fixture)), force = true)
                }
            }
            coVerify(exactly = 0) { repository.upsertForSession(any(), any()) }
        }
    }

    @Test
    fun `warm cache cannot be returned when its session expires during database read`() {
        Fixture().use { fixture ->
            val repository = mockk<EpubRemoteProgressCacheRepository>()
            coEvery { repository.getByChapterId(1) } answers {
                fixture.active.set(null)
                EpubRemoteProgressCache(1, 2, fixture.bookUrl, null, null, null, null, Date(), null)
            }
            val coordinator = coordinator(fixture, repository)
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { coordinator.syncManga(2, fixture.original, listOf(chapter(fixture))) }
            }
            assertEquals(0, fixture.requests.get())
            coVerify(exactly = 0) { repository.upsertForSession(any(), any()) }
        }
    }

    @Test
    fun `obsolete source adapter cannot adopt the replacement source session or cached progress`() {
        Fixture().use { fixture ->
            val repository = mockk<EpubRemoteProgressCacheRepository>()
            val coordinator = coordinator(fixture, repository)
            fixture.active.set(mockk())
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                fixture.service.captureSession(fixture.original)
            }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { coordinator.get(1, fixture.original) }
            }
            assertThrows(KomgaProgressOwnerChanged::class.java) {
                runBlocking { coordinator.syncManga(2, fixture.original, listOf(chapter(fixture))) }
            }
            coVerify(exactly = 0) { repository.getByChapterId(any()) }
            coVerify(exactly = 0) { repository.getByMangaId(any()) }
            assertEquals(0, fixture.requests.get())
        }
    }

    @Test
    fun `ordinary server errors retain cached progress for the same session`() {
        Fixture().use { fixture ->
            val repository = mockk<EpubRemoteProgressCacheRepository>()
            val cached = EpubRemoteProgressCache(1, 2, fixture.bookUrl, null, null, null, null, Date(0), null)
            coEvery { repository.getByChapterId(1) } returns cached
            fixture.status = 503
            val coordinator = coordinator(fixture, repository)
            runBlocking {
                assertEquals(
                    listOf(cached),
                    coordinator.syncManga(2, fixture.original, listOf(chapter(fixture)), force = true),
                )
            }
            coVerify(exactly = 0) { repository.upsertForSession(any(), any()) }
            coVerify(exactly = 0) { repository.getByMangaId(any()) }
        }
    }

    private fun chapter(fixture: Fixture) = Chapter.create().copy(id = 1, mangaId = 2, url = fixture.bookUrl)

    private fun coordinator(fixture: Fixture, repository: EpubRemoteProgressCacheRepository):
        KomgaEpubRemoteProgressCoordinator {
        val shared = mockk<SharedAppPreferences>()
        every { shared.basePreferences() } returns mockk {
            every { incognitoMode } returns MutableTestPreferenceStore().getBoolean("incognito")
        }
        coEvery { fixture.remoteProgress.captureChapter(any(), any(), any()) } coAnswers {
            val key = firstArg<koharia.domain.komga.KomgaReadStatusKey>()
            thirdArg<suspend () -> Unit>()()
            koharia.domain.komga.KomgaRemoteProgressSnapshot(
                key.connectionId,
                key.ownerKey,
                mapOf(
                    1L to koharia.domain.komga.KomgaRemoteChapterSnapshot(
                        chapter(fixture),
                        null,
                        repository.getByChapterId(1),
                        null,
                        key.bookId,
                    ),
                ),
                emptyMap(),
                emptyMap(),
                attempts = mapOf(key.bookId to null),
            )
        }
        coEvery { fixture.remoteProgress.isCurrent(any(), any(), any(), any()) } coAnswers {
            arg<suspend () -> Unit>(3)()
            true
        }
        return KomgaEpubRemoteProgressCoordinator(
            fixture.service,
            GetEpubRemoteProgressCache(repository),
            fixture.remoteProgress,
            shared,
        )
    }

    private class Fixture : AutoCloseable {
        val remoteProgress = mockk<koharia.domain.komga.KomgaRemoteProgressRepository>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val root = "http://127.0.0.1:${server.address.port}"
        val bookUrl = "$root/api/v1/books/book"
        val requests = AtomicInteger()
        val auth = AtomicReference<String>()
        val account = AtomicReference(account("first"))
        val original = mockk<KomgaSource>()
        val active = AtomicReference<KomgaSource?>(original)

        @Volatile var beforeReply: () -> Unit = {}

        @Volatile var status = 200

        @Volatile var body = "\"\""

        @Volatile var redirect: String? = null
        private val client = OkHttpClient.Builder()
            .addNetworkInterceptor(KomgaProgressAccountInterceptor { account.get().key }).build()
        val service: KomgaEpubProgressSyncService

        init {
            val sources = mockk<SourceManager>()
            every { sources.get(42) } answers { active.get() }
            every { original.id } returns 42
            every { original.progressAccount() } answers { account.get() }
            every { original.client } returns client
            service = KomgaEpubProgressSyncService(mockk<BasePreferences>(), sources, mockk<SharedAppPreferences>())
            server.createContext("/") { exchange ->
                requests.incrementAndGet()
                auth.set(exchange.requestHeaders.getFirst("Authorization"))
                beforeReply()
                redirect?.let { exchange.responseHeaders.add("Location", it) }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (redirect == null) status else 302, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            server.start()
        }

        fun account(user: String) = KomgaProgressAccount(42, root, "", "Credentials", user, "fixture", "")
        override fun close() {
            server.stop(0)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
