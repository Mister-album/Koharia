package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubRemoteProgressCache
import kotlinx.coroutines.flow.Flow

interface EpubRemoteProgressCacheRepository {
    suspend fun getByChapterId(chapterId: Long): EpubRemoteProgressCache?
    suspend fun getByMangaId(mangaId: Long): List<EpubRemoteProgressCache>
    fun subscribeByMangaId(mangaId: Long): Flow<List<EpubRemoteProgressCache>>
    suspend fun upsert(cache: EpubRemoteProgressCache)
}
