package koharia.data.epub

import app.cash.sqldelight.async.coroutines.awaitAsList
import koharia.domain.epub.model.EpubBookmark
import koharia.domain.epub.repository.EpubBookmarkRepository
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import java.util.Date

class EpubBookmarkRepositoryImpl(
    private val database: Database,
) : EpubBookmarkRepository {

    override suspend fun getBookmarksByChapterId(chapterId: Long): List<EpubBookmark> {
        return database.epub_bookmarkQueries
            .getByChapterId(chapterId, ::mapEpubBookmark)
            .awaitAsList()
    }

    override suspend fun getBookmarksByMangaId(mangaId: Long): List<EpubBookmark> {
        return database.epub_bookmarkQueries
            .getByMangaId(mangaId, ::mapEpubBookmark)
            .awaitAsList()
    }

    override suspend fun addBookmark(bookmark: EpubBookmark) {
        try {
            database.epub_bookmarkQueries.insert(
                chapterId = bookmark.chapterId,
                mangaId = bookmark.mangaId,
                locatorJson = bookmark.locatorJson,
                sectionTitle = bookmark.sectionTitle,
                progression = bookmark.progression,
                note = bookmark.note,
                createdAt = bookmark.createdAt,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to add EPUB bookmark for chapterId=${bookmark.chapterId}" }
        }
    }

    override suspend fun deleteBookmark(id: Long) {
        try {
            database.epub_bookmarkQueries.deleteById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to delete EPUB bookmark id=$id" }
        }
    }

    override suspend fun updateBookmarkNote(id: Long, note: String?) {
        try {
            database.epub_bookmarkQueries.updateNote(note, id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to update EPUB bookmark note id=$id" }
        }
    }

    private fun mapEpubBookmark(
        id: Long,
        chapterId: Long,
        mangaId: Long,
        locatorJson: String,
        sectionTitle: String?,
        progression: Double?,
        note: String?,
        createdAt: Date,
    ): EpubBookmark {
        return EpubBookmark(
            id = id,
            chapterId = chapterId,
            mangaId = mangaId,
            locatorJson = locatorJson,
            sectionTitle = sectionTitle,
            progression = progression,
            note = note,
            createdAt = createdAt,
        )
    }
}
