package tachiyomi.domain.history.interactor

import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class RemoveHistory(
    private val repository: HistoryRepository,
) {

    suspend fun awaitAll(sourceId: Long? = null): Boolean {
        return repository.deleteAllHistory(sourceId)
    }

    suspend fun await(history: HistoryWithRelations) {
        repository.resetHistory(history.id)
    }

    suspend fun await(mangaId: Long) {
        repository.resetHistoryByMangaId(mangaId)
    }
}
