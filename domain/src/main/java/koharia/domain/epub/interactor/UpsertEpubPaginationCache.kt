package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubPaginationCache
import koharia.domain.epub.repository.EpubPaginationCacheRepository

class UpsertEpubPaginationCache(
    private val repository: EpubPaginationCacheRepository,
) {

    suspend fun await(cache: EpubPaginationCache) {
        repository.upsertCache(cache)
        repository.trimCaches(cache.chapterId, MAX_CACHES_PER_BOOK)
    }

    private companion object {
        const val MAX_CACHES_PER_BOOK = 3L
    }
}
