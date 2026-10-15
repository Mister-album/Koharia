package tachiyomi.domain.chapter.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterProgressOwner
import tachiyomi.domain.chapter.model.ChapterSourceUpdate
import tachiyomi.domain.chapter.model.ChapterUpdate

interface ChapterRepository {

    suspend fun addAll(chapters: List<Chapter>): List<Chapter>

    /** Returns inserted rows, or null if the local snapshot changed. Failures roll back and propagate. */
    suspend fun applySourceUpdate(update: ChapterSourceUpdate, checkCurrent: suspend () -> Unit): List<Chapter>?

    suspend fun update(chapterUpdate: ChapterUpdate)

    suspend fun updateAll(chapterUpdates: List<ChapterUpdate>)

    /** Checks owner/session inside the write transaction. Failure propagates and rolls back all updates. */
    suspend fun updateForReader(
        updates: List<Pair<ChapterProgressOwner, ChapterUpdate>>,
        checkCurrent: suspend () -> Unit,
    )

    suspend fun removeChaptersWithIds(chapterIds: List<Long>)

    suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean = false): List<Chapter>

    suspend fun getChaptersByMangaIds(mangaIds: Collection<Long>): List<Chapter>

    fun getChaptersByMangaIdsAsFlow(mangaIds: Collection<Long>): Flow<List<Chapter>>

    suspend fun getScanlatorsByMangaId(mangaId: Long): List<String>

    fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter>

    suspend fun getChapterById(id: Long): Chapter?

    suspend fun getChapterByMangaIdAsFlow(mangaId: Long, applyScanlatorFilter: Boolean = false): Flow<List<Chapter>>

    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter?

    suspend fun getChaptersByUrlAndSourceId(url: String, sourceId: Long): List<Chapter>
}
