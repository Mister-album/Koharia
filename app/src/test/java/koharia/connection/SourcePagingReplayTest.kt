package koharia.connection

import androidx.paging.PagingSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.data.source.BaseSourcePagingSource
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException

class SourcePagingReplayTest {
    @Test
    fun `reverse loading preserves duplicate ownership and retries after local commit failure`() = runTest {
        val source = mockk<CatalogueSource> { every { id } returns 42L }
        var fail = true
        val repository = mockk<MangaRepository> {
            coEvery { insertNetworkManga(any()) } answers {
                if (fail) {
                    fail = false
                    throw IOException("local commit failed")
                }
                firstArg<List<Manga>>()
            }
        }
        val pager = object : BaseSourcePagingSource(source, NetworkToLocalManga(repository)) {
            override suspend fun requestNextPage(currentPage: Int) = MangasPage(
                (if (currentPage == 1) listOf("a", "a", "b") else listOf("b", "c")).map {
                    SManga.create().apply {
                        url = it
                        title = it
                    }
                },
                currentPage == 1,
            )
        }
        suspend fun load(page: Long) = pager.load(PagingSource.LoadParams.Refresh(page, 25, false))
        assertTrue(load(1) is PagingSource.LoadResult.Error)
        val first = load(1) as PagingSource.LoadResult.Page
        assertEquals(listOf("a", "b"), first.data.map { it.url })
        val second = load(2) as PagingSource.LoadResult.Page
        assertEquals(listOf("c"), second.data.map { it.url })
        assertEquals(1L, second.prevKey)
        assertEquals(first.data, (load(1) as PagingSource.LoadResult.Page).data)
        assertEquals(second.data, (load(2) as PagingSource.LoadResult.Page).data)
    }

    @Test
    fun `cancelled source loading propagates cancellation`() = runTest {
        val pager = object : BaseSourcePagingSource(mockk(), mockk()) {
            override suspend fun requestNextPage(currentPage: Int): MangasPage = throw CancellationException()
        }
        assertTrue(
            runCatching { pager.load(PagingSource.LoadParams.Refresh(1L, 25, false)) }
                .exceptionOrNull() is CancellationException,
        )
    }
}
