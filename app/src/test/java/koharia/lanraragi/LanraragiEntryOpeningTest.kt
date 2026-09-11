package koharia.lanraragi

import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga

class LanraragiEntryOpeningTest {
    @Test
    fun `archive opens reader by default and can opt into previews`() {
        val url = "/lanraragi/42/archive/abc"
        assertEquals(
            LanraragiEntryDestination.READER,
            lanraragiEntryDestination(url, 42, LanraragiArchiveOpenMode.READER),
        )
        assertEquals(
            LanraragiEntryDestination.PAGE_PREVIEW,
            lanraragiEntryDestination(url, 42, LanraragiArchiveOpenMode.PAGE_PREVIEW),
        )
        assertEquals(
            LanraragiEntryDestination.PAGE_PREVIEW,
            lanraragiEntryDestination(url, 42, LanraragiArchiveOpenMode.READER, longClick = true),
        )
    }

    @Test
    fun `tankoubons and unrelated identities never open as single archives`() {
        for (url in listOf("/lanraragi/42/tank/set", "/lanraragi/43/archive/abc", "/lanraragi/42/category/cat")) {
            for (mode in LanraragiArchiveOpenMode.entries) {
                for (longClick in listOf(false, true)) {
                    assertEquals(
                        LanraragiEntryDestination.DETAILS,
                        lanraragiEntryDestination(url, 42, mode, longClick),
                    )
                }
            }
        }
    }

    @Test
    fun `offline opening retains chapter progress without network or writes`() = runTest {
        val manga = Manga.create().copy(id = 7, source = 42, url = "/lanraragi/42/archive/abc")
        val chapter = Chapter.create().copy(id = 8, mangaId = manga.id, url = manga.url, lastPageRead = 12)
        val chapters = mockk<ChapterRepository>()
        val sync = mockk<SyncChaptersWithSource>()
        val source = mockk<LanraragiSource>()
        every { source.id } returns 42
        coEvery { chapters.getChapterByUrlAndMangaId(manga.url, manga.id) } returns chapter

        assertSame(chapter, LanraragiEntryOpenManager(chapters, sync).prepareChapter(source, manga))

        coVerify(exactly = 1) { chapters.getChapterByUrlAndMangaId(manga.url, manga.id) }
        coVerify(exactly = 0) { source.getChapterList(any()) }
        coVerify(exactly = 0) { chapters.update(any()) }
        coVerify(exactly = 0) { chapters.updateAll(any()) }
        verify { sync wasNot Called }
    }
}
