package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubPaginationCache

interface EpubPaginationCacheRepository {

    suspend fun getCache(
        chapterId: Long,
        publicationKey: String,
        layoutKey: String,
    ): EpubPaginationCache?

    suspend fun upsertCache(cache: EpubPaginationCache)

    suspend fun trimCaches(chapterId: Long, keepCount: Long)
}
