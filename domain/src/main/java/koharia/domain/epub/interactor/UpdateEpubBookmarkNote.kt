package koharia.domain.epub.interactor

import koharia.domain.epub.repository.EpubBookmarkRepository

class UpdateEpubBookmarkNote(
    private val repository: EpubBookmarkRepository,
) {

    suspend fun await(id: Long, note: String?) {
        repository.updateBookmarkNote(id, note)
    }
}
