package koharia.suwayomi

import koharia.domain.suwayomi.SuwayomiCacheEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SuwayomiDirectoryCacheTest {
    private val repository = MemorySuwayomiRepository()
    private val json = Json { ignoreUnknownKeys = true }
    private val identity = SuwayomiIdentity(4, "a".repeat(64))
    private fun catalog(api: SuwayomiService, scope: SuwayomiIdentity = identity, check: () -> Unit = {}) =
        SuwayomiCatalog(scope, repository, api, json, check)

    private open class Service : FakeSuwayomiService() {
        var sourceRequests = 0
        var extensionRequests = 0
        var filterRequests = 0
        var preferenceRequests = 0
        var pageRequests = 0
        var fail = false
        var empty = false
        override suspend fun sources(): List<SuwayomiSourceInfo> {
            sourceRequests++
            if (fail) throw IOException()
            return if (empty) emptyList() else listOf(SuwayomiSourceInfo(12, "Fixture"))
        }
        override suspend fun extensions(refresh: Boolean): List<SuwayomiExtension> {
            extensionRequests++
            if (fail) throw IOException()
            return emptyList()
        }
        override suspend fun source(id: Long): SuwayomiSourceInfo = error("Directory already contains the source")
        override suspend fun sourceFilters(id: Long): List<SuwayomiSourceFilter> {
            filterRequests++
            return emptyList()
        }
        override suspend fun sourcePreferences(id: Long): List<SuwayomiSourcePreference> {
            preferenceRequests++
            return listOf(SuwayomiEditTextPreference(currentValue = "fixture-private-value"))
        }
        override suspend fun updateSourcePreference(sourceId: Long, position: Int, change: SuwayomiPreferenceChange) =
            listOf(SuwayomiEditTextPreference(currentValue = "confirmed"))
        override suspend fun setSourceMeta(id: Long, key: String, value: String) = SuwayomiSourceMeta(key, value)
        override suspend fun discoverSourceManga(
            sourceId: Long,
            page: Int,
            query: String?,
            type: SuwayomiSourceMangaType,
            filters: List<SuwayomiFilterChange>,
        ): SuwayomiSourcePage {
            pageRequests++
            if (fail) throw IOException()
            return SuwayomiSourcePage(if (empty) emptyList() else listOf(SuwayomiManga(page, "Fixture")), page < 3)
        }
    }

    @Test
    fun `empty inventories survive new models and process restart without network refresh`() = runTest {
        val api = Service().apply { empty = true }
        val first = catalog(api)
        assertTrue(first.sources().isEmpty())
        assertTrue(first.extensions().isEmpty())
        repeat(3) {
            assertTrue(catalog(api).sources().isEmpty())
            assertTrue(catalog(api).extensions().isEmpty())
        }
        assertEquals(1, api.sourceRequests)
        assertEquals(1, api.extensionRequests)
        assertTrue(first.sourceInventory.state.value.loaded)
        assertFalse(first.sourceInventory.state.value.loading)
    }

    @Test
    fun `source results publish while extensions are blocked and remain after their failure`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = object : Service() {
            override suspend fun extensions(refresh: Boolean): List<SuwayomiExtension> {
                release.await()
                throw IOException()
            }
        }
        val catalog = catalog(api)
        val extension = async { runCatching { catalog.extensions() } }
        runCurrent()
        assertTrue(catalog.extensionInventory.state.value.loading)
        assertEquals(1, catalog.sources().size)
        assertTrue(catalog.sourceInventory.state.value.loaded)
        assertFalse(catalog.extensionInventory.state.value.loaded)
        release.complete(Unit)
        assertTrue(extension.await().isFailure)
        assertEquals(1, catalog.sourceInventory.state.value.value?.size)
    }

    @Test
    fun `concurrent cold consumers deduplicate the source request`() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = object : Service() {
            override suspend fun sources(): List<SuwayomiSourceInfo> {
                release.await()
                return super.sources()
            }
        }
        val catalog = catalog(api)
        val requests = List(5) { async { catalog.sources() } }
        runCurrent()
        release.complete(Unit)
        requests.forEach { assertEquals(1, it.await().size) }
        assertEquals(1, api.sourceRequests)
    }

    @Test
    fun `refresh failure retains displayed and persisted inventories`() = runTest {
        val api = Service()
        val catalog = catalog(api)
        val old = catalog.sources()
        api.fail = true
        assertTrue(runCatching { catalog.sources(true) }.isFailure)
        assertEquals(old, catalog.sourceInventory.state.value.value)
        assertEquals(old, catalog(api).sources())
        assertEquals(2, api.sourceRequests)
    }

    @Test
    fun `invalidated snapshots retain data on failure and retry after restart`() = runTest {
        val api = Service()
        val first = catalog(api)
        val old = first.sources()
        first.invalidateSourceConfiguration()
        api.fail = true
        val second = catalog(api)
        assertTrue(runCatching { second.sources() }.isFailure)
        assertEquals(old, second.sourceInventory.state.value.value)
        api.fail = false
        assertEquals(old, second.sources())
        assertEquals(3, api.sourceRequests)
    }

    @Test
    fun `corrupt snapshots bootstrap and connection account isolation includes directories`() = runTest {
        val api = Service()
        repository.putCache(
            identity.connectionId,
            identity.account,
            "sources",
            SuwayomiCacheEntry("snapshot", "invalid", 1),
        )
        catalog(api).sources()
        catalog(api, identity.copy(connectionId = 5)).sources()
        catalog(api, identity.copy(account = "b".repeat(64))).sources()
        assertEquals(3, api.sourceRequests)
        repository.removeConnection(4)
        catalog(api, identity.copy(connectionId = 5)).sources()
        assertEquals(3, api.sourceRequests)
        catalog(api).sources()
        assertEquals(4, api.sourceRequests)
    }

    @Test
    fun `late directory responses cannot replace another session snapshot`() = runTest {
        val old = catalog(Service()).sources()
        var active = true
        val api = object : Service() {
            override suspend fun sources(): List<SuwayomiSourceInfo> {
                active = false
                return emptyList()
            }
        }
        val stale = catalog(api, check = { if (!active) throw CancellationException() })
        assertTrue(runCatching { stale.sources(true) }.exceptionOrNull() is CancellationException)
        assertEquals(old, catalog(Service()).sources())
    }

    @Test
    fun `source information reuses directory and pin writes reach shared persisted snapshot`() = runTest {
        val api = Service()
        val first = catalog(api)
        assertEquals(12L, first.source(12).id)
        first.setSourcePinned(12, true)
        assertTrue(first.sourceInventory.state.value.value!!.single().isPinned)
        assertTrue(catalog(api).source(12).isPinned)
        assertEquals(1, api.sourceRequests)
    }

    @Test
    fun `extension store refresh does not issue a redundant extension query`() = runTest {
        val api = Service()
        val catalog = catalog(api)
        catalog.sources()
        catalog.extensions(fetchStores = true)
        catalog.extensions()
        catalog.sources()
        assertEquals(1, api.extensionRequests)
        assertEquals(2, api.sourceRequests)
    }

    @Test
    fun `categories reuse persisted shelf without another category request`() = runTest {
        var requests = 0
        val api = object : Service() {
            override suspend fun categories(): List<SuwayomiCategory> {
                requests++
                return super.categories()
            }
        }
        val shelf = catalog(api).shelf()
        assertEquals(shelf.categories, catalog(api).categories())
        assertEquals(1, requests)
    }

    @Test
    fun `source preferences and filters are session cached and sensitive values never persist`() = runTest {
        val api = Service()
        val catalog = catalog(api)
        repeat(3) {
            catalog.sourcePreferences(12)
            catalog.sourceFilters(12)
        }
        assertEquals(1, api.preferenceRequests)
        assertEquals(1, api.filterRequests)
        assertNull(repository.cache(identity.connectionId, identity.account, "preferences", "12"))
        catalog.updateSourcePreference(12, 0, SuwayomiPreferenceChange(editTextState = "draft"))
        assertEquals("confirmed", (catalog.sourcePreferences(12).single() as SuwayomiEditTextPreference).currentValue)
        assertEquals(1, api.preferenceRequests)
        catalog.sourceFilters(12)
        assertEquals(2, api.filterRequests)
        catalog(api).sourcePreferences(12)
        assertEquals(2, api.preferenceRequests)
    }

    @Test
    fun `listing cache includes every remote parameter and successful empty pages survive restart`() = runTest {
        val api = Service().apply { empty = true }
        val first = catalog(api)
        suspend fun page(
            catalog: SuwayomiCatalog,
            source: Long = 12,
            page: Int = 1,
            query: String = "",
            type: SuwayomiSourceMangaType = SuwayomiSourceMangaType.POPULAR,
            filters: List<SuwayomiFilterChange> = emptyList(),
        ) =
            catalog.sourceListing(source, page, query, type, filters, catalog.listingRevision())
        assertTrue(page(first).mangas.isEmpty())
        page(catalog(api))
        assertEquals(1, api.pageRequests)
        page(first, source = 13)
        page(first, page = 2)
        page(first, query = "query")
        page(first, type = SuwayomiSourceMangaType.LATEST)
        page(first, filters = listOf(SuwayomiFilterChange(0, textState = "a")))
        page(catalog(api, identity.copy(account = "b".repeat(64))))
        assertEquals(7, api.pageRequests)
    }

    @Test
    fun `failed listing refresh keeps all old pages and successful refresh removes old append pages`() = runTest {
        val api = Service()
        val catalog = catalog(api)
        val revision = catalog.listingRevision()
        val old = catalog.sourceListing(12, 1, "", SuwayomiSourceMangaType.POPULAR, emptyList(), revision)
        catalog.sourceListing(12, 2, "", SuwayomiSourceMangaType.POPULAR, emptyList(), revision)
        api.fail = true
        assertTrue(
            runCatching {
                catalog.refreshSourceListing(12, "", SuwayomiSourceMangaType.POPULAR, emptyList())
            }.isFailure,
        )
        assertEquals(old, catalog.sourceListing(12, 1, "", SuwayomiSourceMangaType.POPULAR, emptyList(), revision))
        api.fail = false
        catalog.refreshSourceListing(12, "", SuwayomiSourceMangaType.POPULAR, emptyList())
        assertNotEquals(revision, catalog.listingRevision())
        assertTrue(
            runCatching {
                catalog.sourceListing(12, 2, "", SuwayomiSourceMangaType.POPULAR, emptyList(), revision)
            }.exceptionOrNull() is SuwayomiListingChangedException,
        )
        catalog.sourceListing(12, 2, "", SuwayomiSourceMangaType.POPULAR, emptyList(), catalog.listingRevision())
        assertEquals(5, api.pageRequests)
    }

    @Test
    fun `persisted listing cache evicts old pages instead of growing without limit`() = runTest {
        val api = Service()
        val catalog = catalog(api)
        repeat(30) {
            catalog.sourceListing(
                12,
                it + 1,
                "",
                SuwayomiSourceMangaType.POPULAR,
                emptyList(),
                catalog.listingRevision(),
            )
        }
        val restored = catalog(api)
        restored.sourceListing(12, 30, "", SuwayomiSourceMangaType.POPULAR, emptyList(), restored.listingRevision())
        assertEquals(30, api.pageRequests)
        restored.sourceListing(12, 1, "", SuwayomiSourceMangaType.POPULAR, emptyList(), restored.listingRevision())
        assertEquals(31, api.pageRequests)
    }

    @Test
    fun `failed pin writes retain the shared snapshot`() = runTest {
        val api = object : Service() {
            override suspend fun setSourceMeta(
                id: Long,
                key: String,
                value: String,
            ): SuwayomiSourceMeta = throw IOException()
        }
        val catalog = catalog(api)
        val old = catalog.sources()
        assertTrue(runCatching { catalog.setSourcePinned(12, true) }.isFailure)
        assertEquals(old, catalog.sourceInventory.state.value.value)
        assertFalse(catalog(api).source(12).isPinned)
    }

    @Test
    fun `details are prepared only after both detail and chapter snapshots exist`() = runTest {
        val catalog = catalog(Service())
        assertFalse(catalog.hasPreparedManga(3))
        catalog.manga(3, refresh = true)
        assertFalse(catalog.hasPreparedManga(3))
        catalog.chapters(3)
        assertTrue(catalog.hasPreparedManga(3))
    }

    @Test
    fun `invalidating a shelf retains the last successful payload for display after refresh failure`() = runTest {
        val api = object : Service() {
            var failShelf = false
            override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> {
                if (failShelf) throw IOException()
                return super.libraryPage(after)
            }
        }
        val first = catalog(api)
        val old = first.shelf()
        first.invalidateShelf()
        assertNull(first.cachedShelf())
        assertEquals(old, first.memoryShelf)
        api.failShelf = true
        val restored = catalog(api)
        assertTrue(runCatching { restored.shelf() }.isFailure)
        assertEquals(old, restored.memoryShelf)
        api.failShelf = false
        assertEquals(old, restored.shelf())
    }

    @Test
    fun `image resource keys separate connection accounts even for the same relative icon`() {
        val client = okhttp3.OkHttpClient()
        SuwayomiApi(
            client,
            json,
            "http://localhost:4567",
            SuwayomiAuthMode.NONE,
            "",
            "",
            imageScope = "4/account-one",
        ).use { first ->
            SuwayomiApi(
                client,
                json,
                "http://localhost:4567",
                SuwayomiAuthMode.NONE,
                "",
                "",
                imageScope = "4/account-two",
            ).use { second ->
                assertNotEquals(first.resourceCacheKey("/icon.png"), second.resourceCacheKey("/icon.png"))
                assertEquals(first.resourceCacheKey("/icon.png"), first.resourceCacheKey("/icon.png"))
            }
        }
    }

    @Test
    fun `migration drafts cannot be reused after switching connection or account`() {
        val draft = SuwayomiMigrationDrafts.create(identity, 12, "Fixture")
        try {
            assertEquals(draft, SuwayomiMigrationDrafts.get(draft.id, identity))
            assertNull(SuwayomiMigrationDrafts.get(draft.id, identity.copy(connectionId = 5)))
            assertNull(SuwayomiMigrationDrafts.get(draft.id, identity.copy(account = "b".repeat(64))))
        } finally {
            SuwayomiMigrationDrafts.release(draft.id)
        }
    }
}
