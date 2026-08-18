package tachiyomi.domain.chapter.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository

class GetChaptersByMangaId(
    private val chapterRepository: ChapterRepository,
) {

    suspend fun await(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return try {
            chapterRepository.getChapterByMangaId(mangaId, applyScanlatorFilter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    suspend fun await(mangaIds: Collection<Long>): Map<Long, List<Chapter>> {
        if (mangaIds.isEmpty()) return emptyMap()
        return try {
            buildList {
                mangaIds.distinct().chunked(QUERY_CHUNK_SIZE).forEach { chunk ->
                    addAll(chapterRepository.getChaptersByMangaIds(chunk))
                }
            }.groupBy(Chapter::mangaId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyMap()
        }
    }

    private companion object {
        const val QUERY_CHUNK_SIZE = 500
    }
}
