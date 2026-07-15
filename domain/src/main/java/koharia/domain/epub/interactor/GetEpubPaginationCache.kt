package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubPaginationCache
import koharia.domain.epub.repository.EpubPaginationCacheRepository

class GetEpubPaginationCache(
    private val repository: EpubPaginationCacheRepository,
) {

    suspend fun await(
        chapterId: Long,
        publicationKey: String,
        layoutKey: String,
    ): EpubPaginationCache? = repository.getCache(chapterId, publicationKey, layoutKey)
}
