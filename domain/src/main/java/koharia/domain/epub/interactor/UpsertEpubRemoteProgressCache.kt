package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubRemoteProgressCache
import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository

class UpsertEpubRemoteProgressCache(
    private val repository: EpubRemoteProgressCacheRepository,
) {
    suspend fun await(cache: EpubRemoteProgressCache) = repository.upsert(cache)
}
