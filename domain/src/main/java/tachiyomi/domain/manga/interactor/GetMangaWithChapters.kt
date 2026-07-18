package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class GetMangaWithChapters(
    private val mangaRepository: MangaRepository,
    private val chapterRepository: ChapterRepository,
) {

    suspend fun subscribe(id: Long, applyScanlatorFilter: Boolean = false): Flow<Pair<Manga, List<Chapter>>> {
        return combine(
            mangaRepository.getMangaByIdAsFlowOrNull(id),
            chapterRepository.getChapterByMangaIdAsFlow(id, applyScanlatorFilter),
        ) { manga, chapters -> manga?.let { Pair(it, chapters) } }.filterNotNull()
    }

    suspend fun awaitManga(id: Long): Manga {
        return mangaRepository.getMangaById(id)
    }

    suspend fun awaitMangaOrNull(id: Long): Manga? {
        return mangaRepository.getMangaByIdOrNull(id)
    }

    suspend fun awaitChapters(id: Long, applyScanlatorFilter: Boolean = false): List<Chapter> {
        return chapterRepository.getChapterByMangaId(id, applyScanlatorFilter)
    }
}
