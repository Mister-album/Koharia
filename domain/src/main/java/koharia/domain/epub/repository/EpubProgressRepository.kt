package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubProgress
import kotlinx.coroutines.flow.Flow

interface EpubProgressRepository {

    suspend fun getProgress(chapterId: Long): EpubProgress?
    suspend fun getProgressesByMangaId(mangaId: Long): List<EpubProgress>

    fun subscribeProgressesByMangaId(mangaId: Long): Flow<List<EpubProgress>>

    suspend fun upsertProgress(progress: EpubProgress)

    suspend fun deleteProgress(chapterId: Long)
}
