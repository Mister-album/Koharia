package koharia.connection

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaSourceCommitDeviceTest {
    @Test
    fun metadataAndViewerFlagsRollBackWhenOwnerChangesAfterWrite(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            var checks = 0
            val result = runCatching {
                repository.updateFromSource(manga, MangaUpdate(manga.id, title = "Stale", viewerFlags = 77)) {
                    if (++checks == 2) throw CancellationException("Owned fixture session retired")
                }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(2, checks)
            assertEquals(manga, repository.getMangaById(manga.id))
            assertTrue(
                repository.updateFromSource(manga, MangaUpdate(manga.id, title = "Current", viewerFlags = 88)) {
                },
            )
            val current = repository.getMangaById(manga.id)
            assertEquals("Current", current.title)
            assertEquals(88L, current.viewerFlags)
            assertEquals(manga.source, current.source)
            assertEquals(manga.url, current.url)
        }
    }

    @Test
    fun missingAndReassignedRowsCannotReceiveOldSourceMetadata(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            assertTrue(repository.update(MangaUpdate(manga.id, source = manga.source + 1)))
            val before = repository.getMangaById(manga.id)
            assertTrue(
                runCatching {
                    repository.updateFromSource(manga, MangaUpdate(manga.id, title = "Stale")) {}
                }.isFailure,
            )
            assertEquals(before, repository.getMangaById(manga.id))
            repository.deleteMangaById(manga.id)
            assertFalse(repository.updateFromSource(manga, MangaUpdate(manga.id, title = "Stale")) {})
            assertEquals(null, repository.getMangaByIdOrNull(manga.id))
        }
    }

    @Test
    fun capturedInsertRollbackPreservesAnExistingRowAndDoesNotCreateNewRows(): Unit = runBlocking(Dispatchers.IO) {
        fixture { manga, repository ->
            var checks = 0
            val result = runCatching {
                repository.insertNetworkManga(
                    listOf(manga.copy(id = -1, title = "Stale", initialized = true), manga.copy(id = -1, url = "new")),
                ) {
                    if (++checks == 2) throw CancellationException("Owned fixture session retired")
                }
            }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(listOf(manga), repository.getMangaBySourceId(manga.source))
        }
    }

    private suspend fun fixture(block: suspend (Manga, MangaRepository) -> Unit) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.koharia.dev.devicefixture")
        val repository = Injekt.get<MangaRepository>()
        val source = System.nanoTime()
        val manga = repository.insertNetworkManga(
            listOf(Manga.create().copy(source = source, url = "owned-$source", title = "Owned metadata fixture")),
        ).single()
        try {
            block(manga, repository)
        } finally {
            repository.deleteMangaById(manga.id)
            repository.deleteMangaBySourceId(source)
        }
    }
}
