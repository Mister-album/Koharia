package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubBookmark
import koharia.domain.epub.repository.EpubBookmarkRepository

class AddEpubBookmark(
    private val repository: EpubBookmarkRepository,
) {

    suspend fun await(bookmark: EpubBookmark) {
        repository.addBookmark(bookmark)
    }
}
