package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubProgress

interface EpubProgressRepository {

    suspend fun getProgress(chapterId: Long): EpubProgress?

    suspend fun upsertProgress(progress: EpubProgress)

    suspend fun deleteProgress(chapterId: Long)
}
