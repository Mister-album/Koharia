package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository

class GetEpubProgress(
    private val repository: EpubProgressRepository,
) {

    suspend fun await(chapterId: Long): EpubProgress? {
        return repository.getProgress(chapterId)
    }
}
