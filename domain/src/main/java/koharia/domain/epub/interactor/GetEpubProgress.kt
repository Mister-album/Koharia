package koharia.domain.epub.interactor

import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class GetEpubProgress(
    private val repository: EpubProgressRepository,
) {

    suspend fun await(chapterId: Long): EpubProgress? {
        return repository.getProgress(chapterId)
    }

    suspend fun await(chapterIds: Collection<Long>): Map<Long, EpubProgress> {
        if (chapterIds.isEmpty()) return emptyMap()
        return try {
            buildList {
                chapterIds.distinct().chunked(QUERY_CHUNK_SIZE).forEach { chunk ->
                    addAll(repository.getProgressesByChapterIds(chunk))
                }
            }.associateBy { it.chapterId }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyMap()
        }
    }

    suspend fun awaitByMangaId(mangaId: Long): List<EpubProgress> {
        return repository.getProgressesByMangaId(mangaId)
    }

    fun subscribeByMangaId(mangaId: Long): Flow<List<EpubProgress>> {
        return repository.subscribeProgressesByMangaId(mangaId)
    }

    private companion object {
        const val QUERY_CHUNK_SIZE = 500
    }
}
