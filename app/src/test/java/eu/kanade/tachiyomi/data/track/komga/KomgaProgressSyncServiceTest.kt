package eu.kanade.tachiyomi.data.track.komga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class KomgaProgressSyncServiceTest {

    @Test
    fun `missing Komga read progress is applied as unread`() {
        val localChapter = Chapter.create().copy(
            id = 7L,
            read = true,
            lastPageRead = 12L,
        )
        val remoteBook = KomgaApi.SeriesBookProgress(
            seriesUrl = "https://komga.test/api/v1/series/series-1",
            url = "https://komga.test/api/v1/books/book-1",
            readProgress = null,
        )

        val update = remoteBook.toChapterUpdate(localChapter)

        assertNotNull(update)
        assertFalse(update!!.read!!)
        assertEquals(0L, update.lastPageRead)
    }

    @Test
    fun `remote page progress remains one based when present`() {
        val localChapter = Chapter.create().copy(id = 7L)
        val remoteBook = KomgaApi.SeriesBookProgress(
            seriesUrl = "https://komga.test/api/v1/series/series-1",
            url = "https://komga.test/api/v1/books/book-1",
            readProgress = BookReadProgressDto(completed = true, page = 4),
        )

        val update = remoteBook.toChapterUpdate(localChapter)

        assertNotNull(update)
        assertEquals(3L, update!!.lastPageRead)
        assertEquals(true, update.read)
    }

    @Test
    fun `completed progress without a page does not reset local page`() {
        val localChapter = Chapter.create().copy(
            id = 7L,
            lastPageRead = 12L,
        )
        val remoteBook = KomgaApi.SeriesBookProgress(
            seriesUrl = "https://komga.test/api/v1/series/series-1",
            url = "https://komga.test/api/v1/books/book-1",
            readProgress = BookReadProgressDto(completed = true, page = null),
        )

        val update = remoteBook.toChapterUpdate(localChapter)

        assertNotNull(update)
        assertEquals(true, update!!.read)
        assertEquals(null, update.lastPageRead)
    }
}
