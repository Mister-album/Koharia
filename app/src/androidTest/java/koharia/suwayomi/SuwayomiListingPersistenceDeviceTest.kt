package koharia.suwayomi

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import koharia.connection.ConnectionPreferences
import koharia.domain.suwayomi.SuwayomiCacheEntry
import koharia.domain.suwayomi.SuwayomiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiListingPersistenceDeviceTest {
    @Test
    fun droppedPagesAndLegacyEmptyPagesRemainOfflineAcrossCatalogRecreation(): Unit = fixture { id, repository ->
        var requests = 0
        var online = true
        val api = object : SuwayomiService {
            override suspend fun categories(): List<SuwayomiCategory> = error("Unexpected category request")
            override suspend fun libraryPage(after: String?): SuwayomiNodes<SuwayomiManga> = error("Unexpected library")
            override suspend fun manga(id: Int): SuwayomiManga = error("Unexpected detail")
            override suspend fun chapters(mangaId: Int): List<SuwayomiChapter> = error("Unexpected chapters")
            override suspend fun chapter(id: Int): SuwayomiChapter = error("Unexpected chapter")
            override suspend fun pages(chapterId: Int): SuwayomiPages = error("Unexpected pages")
            override suspend fun updateChapter(id: Int, pageIndex: Int?, read: Boolean?): SuwayomiChapter =
                error("Unexpected write")

            override suspend fun discoverSourceManga(
                sourceId: Long,
                page: Int,
                query: String?,
                type: SuwayomiSourceMangaType,
                filters: List<SuwayomiFilterChange>,
            ): SuwayomiSourcePage {
                requests++
                check(online) { "Cached page unexpectedly fetched" }
                return SuwayomiSourcePage(listOf(SuwayomiManga(page, "Fixture $page")), page < 300)
            }
        }
        fun catalog(account: String = "fixture") = SuwayomiCatalog(
            SuwayomiIdentity(id, account),
            repository,
            api,
            Injekt.get<Json>(),
            {},
        )
        suspend fun page(catalog: SuwayomiCatalog, page: Int) = catalog.sourceListing(
            12,
            page,
            "",
            SuwayomiSourceMangaType.POPULAR,
            emptyList(),
            catalog.listingRevision(),
        )
        val first = catalog()
        for (page in 1..300) assertEquals(page, page(first, page).mangas.single().id)
        assertEquals(300, requests)
        online = false
        val restored = catalog()
        for (page in 300 downTo 1) assertEquals(page, page(restored, page).mangas.single().id)
        assertEquals(300, requests)
        assertTrue(runCatching { page(catalog("other"), 1) }.isFailure)
        val legacy = """[{"key":{"sourceId":12,"page":1,"query":"","type":"POPULAR","filters":"[]"},
            "value":{"mangas":[],"hasNextPage":false}}]"""
        repository.putCache(id, "legacy", "discovery", SuwayomiCacheEntry("pages", legacy, 1))
        assertTrue(page(catalog("legacy"), 1).mangas.isEmpty())
        assertNull(repository.cache(id, "legacy", "discovery", "pages"))
        assertTrue(page(catalog("legacy"), 1).mangas.isEmpty())
        assertEquals(301, requests)
    }

    @Test
    fun failedReplaceAndLegacyImportRollbackWithoutLosingPages(): Unit = fixture { id, repository ->
        val driver = Injekt.get<SqlDriver>()
        val name = "listing_fixture_$id"
        val old = SuwayomiCacheEntry("1", "old", 1)
        repository.putCache(id, "fixture", "discovery/fail", old)
        repository.putCache(id, "fixture", "discovery", SuwayomiCacheEntry("pages", "legacy", 1))
        driver.execute(
            null,
            "CREATE TEMP TRIGGER $name BEFORE INSERT ON suwayomi_cache " +
                "WHEN NEW.connection_id = $id AND NEW.payload = 'fail' BEGIN " +
                "SELECT RAISE(ABORT, 'fixture interruption'); END",
            0,
        ).await()
        try {
            assertTrue(
                runCatching {
                    repository.replaceCacheGroup(
                        id,
                        "fixture",
                        "discovery/fail",
                        listOf(SuwayomiCacheEntry("2", "fail", 2)),
                    )
                }.isFailure,
            )
            assertEquals(old, repository.cache(id, "fixture", "discovery/fail", "1"))
            assertTrue(
                runCatching {
                    repository.migrateDiscoveryCache(
                        id,
                        "fixture",
                        linkedMapOf(
                            "discovery/good" to listOf(old),
                            "discovery/fail" to listOf(SuwayomiCacheEntry("2", "fail", 2)),
                        ),
                    )
                }.isFailure,
            )
            assertNull(repository.cache(id, "fixture", "discovery/good", "1"))
            assertEquals("legacy", repository.cache(id, "fixture", "discovery", "pages")?.payload)
        } finally {
            driver.execute(null, "DROP TRIGGER IF EXISTS $name", 0).await()
        }
        repository.migrateDiscoveryCache(id, "fixture", mapOf("discovery/fail" to listOf(old.copy(payload = "older"))))
        assertEquals(old, repository.cache(id, "fixture", "discovery/fail", "1"))
        assertNull(repository.cache(id, "fixture", "discovery", "pages"))
        repository.putCache(id, "other", "discovery/fail", old)
        repository.putCache(id, "fixture", "manga", old)
        repository.invalidateDiscoveryCache(id, "fixture")
        assertNull(repository.cache(id, "fixture", "discovery/fail", "1"))
        assertEquals(old, repository.cache(id, "other", "discovery/fail", "1"))
        assertEquals(old, repository.cache(id, "fixture", "manga", "1"))
    }

    private fun fixture(block: suspend (Long, SuwayomiRepository) -> Unit): Unit = runBlocking(Dispatchers.IO) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val repository = Injekt.get<SuwayomiRepository>()
        try {
            block(id, repository)
        } finally {
            repository.removeConnection(id)
        }
    }
}
