package koharia.data.epub

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.repository.EpubProgressRepository
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import java.util.Date

class EpubProgressRepositoryImpl(
    private val database: Database,
) : EpubProgressRepository {

    override suspend fun getProgress(chapterId: Long): EpubProgress? {
        return database.epub_progressQueries
            .getByChapterId(chapterId, ::mapEpubProgress)
            .awaitAsOneOrNull()
    }

    override suspend fun getProgressesByChapterIds(chapterIds: Collection<Long>): List<EpubProgress> {
        if (chapterIds.isEmpty()) return emptyList()
        return database.epub_progressQueries
            .getByChapterIds(chapterIds.toList(), ::mapEpubProgress)
            .awaitAsList()
    }

    override suspend fun getProgressesByMangaId(mangaId: Long): List<EpubProgress> {
        return database.epub_progressQueries
            .getByMangaId(mangaId, ::mapEpubProgress)
            .awaitAsList()
    }

    override fun subscribeProgressesByMangaId(mangaId: Long): Flow<List<EpubProgress>> {
        return database.epub_progressQueries
            .getByMangaId(mangaId, ::mapEpubProgress)
            .subscribeToList()
    }

    override suspend fun upsertProgress(progress: EpubProgress) {
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
    }

    override suspend fun compareAndSetProgress(
        expected: EpubProgress?,
        progress: EpubProgress,
        checkCurrent: suspend () -> Unit,
    ): Boolean = database.transactionWithResult {
        require(expected == null || (expected.chapterId == progress.chapterId && expected.mangaId == progress.mangaId))
        checkCurrent()
        check(
            database.epub_progressQueries.getChapterMangaId(progress.chapterId).awaitAsOneOrNull() == progress.mangaId,
        ) {
            "EPUB progress chapter ownership changed"
        }
        if (getProgress(progress.chapterId) != expected) return@transactionWithResult false
        upsertProgress(progress)
        checkCurrent()
        true
    }

    override suspend fun deleteProgress(chapterId: Long) {
        database.epub_progressQueries.deleteByChapterId(chapterId)
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
