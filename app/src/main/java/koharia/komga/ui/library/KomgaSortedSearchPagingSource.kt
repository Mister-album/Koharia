package koharia.komga.ui.library

import androidx.paging.PagingState
import koharia.domain.manga.model.toDomainManga
import koharia.komga.domain.repository.KomgaSortedSearchSession
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.repository.SourcePagingSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class KomgaSortedSearchPagingSource(
    private val sourceId: Long,
    private val session: KomgaSortedSearchSession,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
) : SourcePagingSource() {
    override fun getRefreshKey(state: PagingState<Long, Manga>): Long? = null

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> = try {
        withIOContext {
            val page = params.key ?: 1L
            val result = session.load(page.toInt())
            val manga = networkToLocalManga(result.mangas.map { it.toDomainManga(sourceId) })
            LoadResult.Page(manga, null, if (result.hasNextPage) page + 1 else null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(e)
    }
}
