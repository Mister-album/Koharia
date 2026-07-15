package koharia.domain.epub.repository

import koharia.domain.epub.model.EpubBookmark

interface EpubBookmarkRepository {

    suspend fun getBookmarksByChapterId(chapterId: Long): List<EpubBookmark>

    suspend fun getBookmarksByMangaId(mangaId: Long): List<EpubBookmark>

    suspend fun addBookmark(bookmark: EpubBookmark)

    suspend fun deleteBookmark(id: Long)

    suspend fun updateBookmarkNote(id: Long, note: String?)
}
