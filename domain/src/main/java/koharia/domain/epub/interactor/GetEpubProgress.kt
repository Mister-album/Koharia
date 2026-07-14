package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository
import kotlinx.coroutines.flow.Flow

class GetEpubProgress(
    private val repository: EpubProgressRepository,
) {

    suspend fun await(chapterId: Long): EpubProgress? {
        return repository.getProgress(chapterId)
    }

    suspend fun awaitByMangaId(mangaId: Long): List<EpubProgress> {
        return repository.getProgressesByMangaId(mangaId)
    }

    fun subscribeByMangaId(mangaId: Long): Flow<List<EpubProgress>> {
        return repository.subscribeProgressesByMangaId(mangaId)
    }
}
