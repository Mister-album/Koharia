package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubProgress
import kotlinx.coroutines.flow.Flow

interface EpubProgressRepository {

    suspend fun getProgress(chapterId: Long): EpubProgress?

    suspend fun getProgressesByChapterIds(chapterIds: Collection<Long>): List<EpubProgress>

    suspend fun getProgressesByMangaId(mangaId: Long): List<EpubProgress>

    fun subscribeProgressesByMangaId(mangaId: Long): Flow<List<EpubProgress>>

    suspend fun upsertProgress(progress: EpubProgress)

    /** Commit only against the exact local snapshot; a deleted or newer row must not be recreated by a late response. */
    suspend fun compareAndSetProgress(
        expected: EpubProgress?,
        progress: EpubProgress,
        checkCurrent: suspend () -> Unit,
    ): Boolean

    suspend fun deleteProgress(chapterId: Long)
}
