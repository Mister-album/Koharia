package koharia.kavita

import io.mockk.coEvery
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.domain.kavita.KavitaCacheEntry
import koharia.domain.kavita.KavitaRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KavitaShelfCacheProgressTest {
    @Test
    fun `shelf progress never fetches volumes for missing stale or malformed caches`() = runTest {
        val api = mockk<KavitaApiClient> { every { json } returns Json }
        val repository = mockk<KavitaRepository>()
        val catalog = KavitaCatalog(42, "account", repository, api, checkSession = {})
        for (entry in listOf(
            null,
            KavitaCacheEntry("data", "[]", 1, stale = true),
            KavitaCacheEntry("data", "invalid", 1),
        )) {
            coEvery { repository.cache(42, "account", "volumes/7", "data") } returns entry
            assertNull(catalog.cachedSeriesBookProgress(7))
        }
        verify { api.json }
        confirmVerified(api)
    }

    @Test
    fun `warm cached volumes supply chapter counts without network access`() = runTest {
        val api = mockk<KavitaApiClient> { every { json } returns Json }
        val volumes = listOf(KavitaVolume(id = 1, chapters = listOf(KavitaChapter(id = 2, pages = 10, pagesRead = 10))))
        val repository = mockk<KavitaRepository> {
            coEvery { cache(42, "account", "volumes/7", "data") } returns
                KavitaCacheEntry("data", Json.encodeToString(volumes), 1)
        }
        val catalog = KavitaCatalog(42, "account", repository, api, checkSession = {})
        assertEquals(KavitaSeriesBookProgress(1, 1), catalog.cachedSeriesBookProgress(7))
        verify { api.json }
        confirmVerified(api)
    }
}
