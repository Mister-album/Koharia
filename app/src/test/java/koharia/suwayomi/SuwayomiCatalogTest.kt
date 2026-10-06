package koharia.suwayomi

import koharia.domain.suwayomi.SuwayomiOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SuwayomiCatalogTest {

    @org.junit.jupiter.api.Test
    fun `image caches isolate connections accounts and page manifests`() {
        val identity = SuwayomiIdentity(4, "a".repeat(64))
        val page = "http://localhost:4567/api/v1/manga/3/chapter/0/page/0"
        val url = identity.imageUrl(page, "first")
        org.junit.jupiter.api.Assertions.assertNotEquals(url, identity.copy(connectionId = 5).imageUrl(page, "first"))
        org.junit.jupiter.api.Assertions.assertNotEquals(
            url,
            identity.copy(account = "b".repeat(64)).imageUrl(page, "first"),
        )
        org.junit.jupiter.api.Assertions.assertNotEquals(url, identity.imageUrl(page, "reordered"))
        org.junit.jupiter.api.Assertions.assertEquals(url, identity.imageUrl(page, "first"))
    }
    private val repository = MemorySuwayomiRepository()
    private val json = Json { ignoreUnknownKeys = true }
    private val identity = SuwayomiIdentity(4, "a".repeat(64))
    private fun catalog(api: SuwayomiService, identity: SuwayomiIdentity = this.identity, check: () -> Unit = {}) =
        SuwayomiCatalog(identity, repository, api, json, check)

    @Test
    fun `warm startup never requests a shelf and successful empty shelves remain cached`() = runTest {
        val api = object : FakeSuwayomiService() {
            override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> {
                shelfRequests++
                return SuwayomiNodes()
            }
        }
        assertTrue(catalog(api).shelf().mangas.isEmpty())
        repeat(3) { assertTrue(catalog(api).shelf().mangas.isEmpty()) }
        assertEquals(1, api.shelfRequests)
        catalog(api).shelf(refresh = true)
        assertEquals(2, api.shelfRequests)
    }

    @Test
    fun `failed paginated refresh retains the whole previous snapshot`() = runTest {
        val api = FakeSuwayomiService()
        val old = catalog(api).shelf()
        val failing = object : FakeSuwayomiService() {
            override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> {
                if (after != null) throw SuwayomiException(SuwayomiException.Reason.SERVER)
                return SuwayomiNodes(listOf(SuwayomiManga(9, "Replacement")), SuwayomiPageInfo(true, "9"))
            }
        }
        try {
            catalog(failing).shelf(true)
        } catch (_: SuwayomiException) { }
        assertEquals(old, catalog(api).shelf())
        assertEquals(1, api.shelfRequests)
    }

    @Test
    fun `invalidated snapshot is refetched instead of failing on its empty payload`() = runTest {
        val api = FakeSuwayomiService()
        assertEquals(1, catalog(api).shelf().mangas.size)
        catalog(api).invalidateShelf()
        assertNull(catalog(api).cachedShelf())
        assertEquals(1, catalog(api).shelf().mangas.size)
        assertEquals(2, api.shelfRequests)
    }

    @Test
    fun `connection and account namespaces do not reuse snapshots`() = runTest {
        val api = FakeSuwayomiService()
        catalog(api).shelf()
        catalog(api, identity.copy(account = "b".repeat(64))).shelf()
        catalog(api, identity.copy(connectionId = 5)).shelf()
        assertEquals(3, api.shelfRequests)
        repository.removeConnection(4)
        assertEquals(1, catalog(api, identity.copy(connectionId = 5)).shelf().mangas.size)
        assertEquals(3, api.shelfRequests)
    }

    @Test
    fun `session change prevents a delayed response from replacing cache`() = runTest {
        var active = true
        val api = object : FakeSuwayomiService() {
            override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> {
                active = false
                return super.libraryPage(after)
            }
        }
        try {
            catalog(api, check = { if (!active) throw CancellationException() }).shelf()
        } catch (_: CancellationException) { }
        assertEquals(null, catalog(FakeSuwayomiService()).cachedShelf())
    }

    @Test
    fun `normal opening falls back to persisted pages when the server is unreachable`() = runTest {
        val previous = catalog(FakeSuwayomiService()).pages(11)
        val stored = repository.cache(identity.connectionId, identity.account, "pages", "11")
        val pending = SuwayomiOperation("11", "{}", revision = 7, pending = true)
        repository.putOperation(identity.connectionId, identity.account, pending)
        for (failure in listOf(ConnectException(), SocketTimeoutException(), UnknownHostException())) {
            val unavailable = object : FakeSuwayomiService() {
                override suspend fun pages(chapterId: Int): SuwayomiPages = throw failure
            }
            assertEquals(previous, catalog(unavailable).pagesForReading(11, online = true, forceNetwork = false))
        }
        assertEquals(stored, repository.cache(identity.connectionId, identity.account, "pages", "11"))
        assertEquals(listOf(pending), repository.operations(identity.connectionId, identity.account))
    }

    @Test
    fun `normal opening without device connectivity uses persisted pages without a request`() = runTest {
        val previous = catalog(FakeSuwayomiService()).pages(11)
        val unavailable = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int): SuwayomiPages = error("Offline opening must use the manifest")
        }

        assertEquals(previous, catalog(unavailable).pagesForReading(11, online = false, forceNetwork = false))
    }

    @Test
    fun `unreachable opening never reuses another connection account or chapter manifest`() = runTest {
        catalog(FakeSuwayomiService()).pages(11)
        val failure = ConnectException()
        val unavailable = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int): SuwayomiPages = throw failure
        }
        for (scope in listOf(identity.copy(connectionId = 5), identity.copy(account = "b".repeat(64)))) {
            assertSame(
                failure,
                runCatching { catalog(unavailable, scope).pagesForReading(11, true, false) }.exceptionOrNull(),
            )
        }
        assertSame(failure, runCatching { catalog(unavailable).pagesForReading(12, true, false) }.exceptionOrNull())
    }

    @Test
    fun `explicit page refresh and progress checks propagate connection failure despite cached pages`() = runTest {
        val previous = catalog(FakeSuwayomiService()).pages(11)
        val failure = ConnectException()
        val unavailable = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int): SuwayomiPages = throw failure
        }
        val catalog = catalog(unavailable)
        for (online in listOf(true, false)) {
            assertSame(failure, runCatching { catalog.pagesForReading(11, online, true) }.exceptionOrNull())
        }
        assertSame(failure, runCatching { catalog.pages(11, refresh = true) }.exceptionOrNull())
        assertEquals(previous, catalog.pages(11))
    }

    @Test
    fun `online opening revalidates reordered pages and replaces the persisted manifest`() = runTest {
        val api = FakeSuwayomiService()
        val previous = catalog(api).pages(11)
        val updated = previous.copy(
            pages = previous.pages.map { it.replace("/chapter/0/", "/chapter/7/") },
            chapter = previous.chapter.copy(sourceOrder = 7),
        )
        val reordered = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int) = updated
        }

        assertEquals(updated, catalog(reordered).pagesForReading(11, online = true, forceNetwork = false))
        assertNotEquals(previous.version, updated.version)
        assertEquals(updated, catalog(api).pages(11))
    }

    @Test
    fun `typed authentication and protocol failures never fall back to cached pages`() = runTest {
        val previous = catalog(FakeSuwayomiService()).pages(11)
        for (reason in SuwayomiException.Reason.entries) {
            val failure = SuwayomiException(reason)
            for (error in listOf(failure, IOException(failure))) {
                val api = object : FakeSuwayomiService() {
                    override suspend fun pages(chapterId: Int): SuwayomiPages = throw error
                }
                assertSame(error, runCatching { catalog(api).pagesForReading(11, true, false) }.exceptionOrNull())
            }
        }
        assertEquals(previous, catalog(FakeSuwayomiService()).pages(11))
    }

    @Test
    fun `cancelled requests never fall back to cached pages`() = runTest {
        catalog(FakeSuwayomiService()).pages(11)
        val cancellation = CancellationException()
        for (error in listOf(cancellation, IOException(cancellation))) {
            val api = object : FakeSuwayomiService() {
                override suspend fun pages(chapterId: Int): SuwayomiPages = throw error
            }
            assertSame(error, runCatching { catalog(api).pagesForReading(11, true, false) }.exceptionOrNull())
        }
        val api = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int): SuwayomiPages {
                currentCoroutineContext().cancel()
                throw IOException()
            }
        }
        val request = async { catalog(api).pagesForReading(11, true, false) }
        assertTrue(runCatching { request.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `session change prevents cached fallback after a delayed connection failure`() = runTest {
        val previous = catalog(FakeSuwayomiService()).pages(11)
        var active = true
        val api = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int): SuwayomiPages {
                active = false
                throw ConnectException()
            }
        }
        val stale = catalog(api, check = { if (!active) throw CancellationException() })
        assertTrue(runCatching { stale.pagesForReading(11, true, false) }.exceptionOrNull() is CancellationException)
        assertEquals(previous, catalog(FakeSuwayomiService()).pages(11))
    }

    @Test
    fun `session change prevents a delayed page response from replacing the manifest`() = runTest {
        val previous = catalog(FakeSuwayomiService()).pages(11)
        var active = true
        val api = object : FakeSuwayomiService() {
            override suspend fun pages(chapterId: Int): SuwayomiPages {
                active = false
                return previous.copy(pages = previous.pages.reversed())
            }
        }
        val stale = catalog(api, check = { if (!active) throw CancellationException() })
        assertTrue(runCatching { stale.pagesForReading(11, true, false) }.exceptionOrNull() is CancellationException)
        assertEquals(previous, catalog(FakeSuwayomiService()).pages(11))
    }

    @Test
    fun `stable chapter identity does not include order or title`() {
        val url = identity.chapterUrl(3, 11)
        assertEquals(3 to 11, identity.chapter(url))
        assertThrows(IllegalArgumentException::class.java) { identity.copy(connectionId = 9).chapter(url) }
        assertEquals(identity, SuwayomiIdentity.fromMangaUrl(identity.mangaUrl(3)))
        assertFalse(SuwayomiApi.supportsVersion("v2.4.2300"))
        assertTrue(SuwayomiApi.supportsVersion("v2.4.2366"))
        assertTrue(SuwayomiApi.supportsVersion("v2.5.1"))
    }
}
