package koharia.data.epub

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import java.util.Date

class EpubProgressRepositoryImpl(
    private val database: Database,
) : EpubProgressRepository {

    override suspend fun getProgress(chapterId: Long): EpubProgress? {
        return database.epub_progressQueries
            .getByChapterId(chapterId, ::mapEpubProgress)
            .awaitAsOneOrNull()
    }

    override suspend fun upsertProgress(progress: EpubProgress) {
        try {
            database.epub_progressQueries.upsert(
                chapterId = progress.chapterId,
                mangaId = progress.mangaId,
                bookUrl = progress.bookUrl,
                locatorJson = progress.locatorJson,
                progression = progress.progression,
                positionIndex = progress.positionIndex,
                updatedAt = progress.updatedAt,
                lastSyncedAt = progress.lastSyncedAt,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to upsert EPUB progress for chapterId=${progress.chapterId}" }
        }
    }

    override suspend fun deleteProgress(chapterId: Long) {
        try {
            database.epub_progressQueries.deleteByChapterId(chapterId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to delete EPUB progress for chapterId=$chapterId" }
        }
    }

    private fun mapEpubProgress(
        chapter_id: Long,
        manga_id: Long,
        book_url: String?,
        locator_json: String,
        progression: Double?,
        position_index: Long?,
        updated_at: Date,
        last_synced_at: Date?,
    ): EpubProgress {
        return EpubProgress(
            chapterId = chapter_id,
            mangaId = manga_id,
            bookUrl = book_url,
            locatorJson = locator_json,
            progression = progression,
            positionIndex = position_index,
            updatedAt = updated_at,
            lastSyncedAt = last_synced_at,
        )
    }
}
