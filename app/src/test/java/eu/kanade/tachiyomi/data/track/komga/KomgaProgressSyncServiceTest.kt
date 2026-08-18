package eu.kanade.tachiyomi.data.track.komga

import koharia.domain.epub.model.EpubProgress
import koharia.domain.epub.model.EpubRemoteProgressCache
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.util.Date

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

    @Test
    fun `manual EPUB refresh selects only entries with relevant progress`() {
        val untouched = epubChapter(id = 1L, bookId = "untouched")
        val remoteInProgress = epubChapter(id = 2L, bookId = "remote")
        val localInProgress = epubChapter(id = 3L, bookId = "local")
        val cachedInProgress = epubChapter(id = 4L, bookId = "cached")
        val completedWithCache = epubChapter(id = 5L, bookId = "completed", read = true)

        val selected = selectRelevantKomgaEpubChapters(
            chapters = listOf(
                untouched,
                remoteInProgress,
                localInProgress,
                cachedInProgress,
                completedWithCache,
            ),
            remoteInProgressBookUrls = setOf(remoteInProgress.url),
            localProgressChapterIds = setOf(localInProgress.id),
            remoteProgressChapterIds = setOf(cachedInProgress.id, completedWithCache.id),
        )

        assertEquals(listOf(2L, 3L, 4L), selected.map(Chapter::id))
    }

    @Test
    fun `server progress can identify EPUB when chapter memo is stale`() {
        val chapter = Chapter.create().copy(
            id = 7L,
            url = "https://komga.test/api/v1/books/remote#cached-version",
        )

        val selected = selectRelevantKomgaEpubChapters(
            chapters = listOf(chapter),
            remoteInProgressBookUrls = setOf("https://komga.test/api/v1/books/remote"),
            localProgressChapterIds = emptySet(),
            remoteProgressChapterIds = emptySet(),
        )

        assertEquals(listOf(chapter), selected)
    }

    @Test
    fun `unread reset only clears effective EPUB progress`() {
        val local = epubProgress(updatedAt = 200L)
        val staleEmptyRemote = remoteProgress(checkedAt = 100L)
        val currentEmptyRemote = remoteProgress(checkedAt = 300L)
        val populatedRemote = remoteProgress(checkedAt = 300L, progression = 0.5)

        assertFalse(hasEpubProgressToClear(null, null))
        assertEquals(true, hasEpubProgressToClear(local, null))
        assertEquals(true, hasEpubProgressToClear(local, staleEmptyRemote))
        assertFalse(hasEpubProgressToClear(local, currentEmptyRemote))
        assertEquals(true, hasEpubProgressToClear(null, populatedRemote))
    }

    private fun epubChapter(id: Long, bookId: String, read: Boolean = false): Chapter {
        return Chapter.create().copy(
            id = id,
            read = read,
            url = "https://komga.test/api/v1/books/$bookId",
            memo = buildJsonObject { put("isEpub", true) },
        )
    }

    private fun epubProgress(updatedAt: Long): EpubProgress {
        return EpubProgress(
            chapterId = 1L,
            mangaId = 1L,
            bookUrl = "https://komga.test/api/v1/books/book-1",
            locatorJson = "{}",
            progression = 0.25,
            positionIndex = 1L,
            updatedAt = Date(updatedAt),
            lastSyncedAt = null,
        )
    }

    private fun remoteProgress(checkedAt: Long, progression: Double? = null): EpubRemoteProgressCache {
        return EpubRemoteProgressCache(
            chapterId = 1L,
            mangaId = 1L,
            bookUrl = "https://komga.test/api/v1/books/book-1",
            locatorJson = null,
            progression = progression,
            positionIndex = null,
            modifiedAt = null,
            checkedAt = Date(checkedAt),
            serverDate = null,
        )
    }
}
