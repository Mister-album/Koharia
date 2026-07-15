package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubBookmark
import koharia.domain.epub.repository.EpubBookmarkRepository

class GetEpubBookmarks(
    private val repository: EpubBookmarkRepository,
) {

    suspend fun await(chapterId: Long): List<EpubBookmark> {
        return repository.getBookmarksByChapterId(chapterId)
    }

    suspend fun awaitByMangaId(mangaId: Long): List<EpubBookmark> {
        return repository.getBookmarksByMangaId(mangaId)
    }
}
