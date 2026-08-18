package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import koharia.connection.ConnectionReadStatusAdapter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class SetReadStatusTest {

    private abstract class ReadStatusSource : Source, ConnectionReadStatusAdapter

    @Test
    fun `marking a connection chapter unread updates the provider`() = runTest {
        val downloadPreferences = mockk<DownloadPreferences>(relaxed = true)
        val deleteDownload = mockk<DeleteDownload>(relaxed = true)
        val mangaRepository = mockk<MangaRepository>()
        val chapterRepository = mockk<ChapterRepository>()
        val sourceManager = mockk<SourceManager>()
        val source = mockk<ReadStatusSource>()
        val manga = Manga.create().copy(id = 9L, source = 42L)
        val chapter = Chapter.create().copy(
            id = 7L,
            mangaId = manga.id,
            read = true,
            lastPageRead = 12L,
            url = "https://komga.test/api/v1/books/book-1",
        )

        coEvery { chapterRepository.updateAll(any()) } just runs
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        every { sourceManager.get(manga.source) } returns source
        coEvery { source.setChapterReadStatus(chapter.url, false) } just runs

        val result = interactor(
            downloadPreferences = downloadPreferences,
            deleteDownload = deleteDownload,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = sourceManager,
        ).await(read = false, chapter)

        assertSame(SetReadStatus.Result.Success, result)
        coVerify(exactly = 1) {
            chapterRepository.updateAll(
                match { updates ->
                    updates.single().id == chapter.id &&
                        updates.single().read == false &&
                        updates.single().lastPageRead == 0L
                },
            )
        }
        coVerify(exactly = 1) { source.setChapterReadStatus(chapter.url, false) }
    }

    @Test
    fun `a failed provider update does not prevent remaining chapters from syncing`() = runTest {
        val mangaRepository = mockk<MangaRepository>()
        val chapterRepository = mockk<ChapterRepository>()
        val sourceManager = mockk<SourceManager>()
        val source = mockk<ReadStatusSource>()
        val manga = Manga.create().copy(id = 9L, source = 42L)
        val first = Chapter.create().copy(
            id = 7L,
            mangaId = manga.id,
            read = true,
            url = "https://komga.test/api/v1/books/book-1",
        )
        val second = first.copy(id = 8L, url = "https://komga.test/api/v1/books/book-2")

        coEvery { chapterRepository.updateAll(any()) } just runs
        coEvery { mangaRepository.getMangaById(manga.id) } returns manga
        every { sourceManager.get(manga.source) } returns source
        coEvery { source.setChapterReadStatus(first.url, false) } throws IllegalStateException("offline")
        coEvery { source.setChapterReadStatus(second.url, false) } just runs

        val result = interactor(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = sourceManager,
        ).await(read = false, first, second)

        assertSame(SetReadStatus.Result.Success, result)
        coVerify(exactly = 1) { source.setChapterReadStatus(first.url, false) }
        coVerify(exactly = 1) { source.setChapterReadStatus(second.url, false) }
    }

    private fun interactor(
        downloadPreferences: DownloadPreferences = mockk(relaxed = true),
        deleteDownload: DeleteDownload = mockk(relaxed = true),
        mangaRepository: MangaRepository,
        chapterRepository: ChapterRepository,
        sourceManager: SourceManager,
    ) = SetReadStatus(
        downloadPreferences = downloadPreferences,
        deleteDownload = deleteDownload,
        mangaRepository = mangaRepository,
        chapterRepository = chapterRepository,
        sourceManager = sourceManager,
    )
}
