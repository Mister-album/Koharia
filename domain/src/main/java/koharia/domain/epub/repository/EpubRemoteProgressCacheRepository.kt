package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubRemoteProgressCache

interface EpubRemoteProgressCacheRepository {
    suspend fun getByChapterId(chapterId: Long): EpubRemoteProgressCache?
    suspend fun getByMangaId(mangaId: Long): List<EpubRemoteProgressCache>
    suspend fun upsert(cache: EpubRemoteProgressCache)
}
