package koharia.domain.epub.interactor

import koharia.domain.epub.repository.EpubRemoteProgressCacheRepository

class GetEpubRemoteProgressCache(
    private val repository: EpubRemoteProgressCacheRepository,
) {
    suspend fun await(chapterId: Long) = repository.getByChapterId(chapterId)
    suspend fun awaitByMangaId(mangaId: Long) = repository.getByMangaId(mangaId)
}
