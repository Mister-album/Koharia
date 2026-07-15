package koharia.domain.epub.interactor

import koharia.domain.epub.repository.EpubBookmarkRepository

class DeleteEpubBookmark(
    private val repository: EpubBookmarkRepository,
) {

    suspend fun await(id: Long) {
        repository.deleteBookmark(id)
    }
}
