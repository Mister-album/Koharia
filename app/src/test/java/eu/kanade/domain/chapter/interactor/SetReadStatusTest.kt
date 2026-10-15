package eu.kanade.domain.chapter.interactor

import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import koharia.connection.ConnectionReadStatus
import koharia.connection.ConnectionReadStatusAdapter
import koharia.connection.ConnectionReadStatusCommitAdapter
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class SetReadStatusTest {

    private abstract class ReadStatusSource : Source, ConnectionReadStatusAdapter
    private abstract class AtomicReadStatusSource : Source, ConnectionReadStatusCommitAdapter

    @ParameterizedTest
    @EnumSource(ConnectionReadStatus::class)
    fun `transactional adapters own local writes and gate deletion`(status: ConnectionReadStatus) = runTest {
        val preferences = mockk<DownloadPreferences>()
        val delete = mockk<DeleteDownload>(relaxed = true)
        val mangas = mockk<MangaRepository>()
        val chapters = mockk<ChapterRepository>(relaxed = true)
        val sources = mockk<SourceManager>()
        val source = mockk<AtomicReadStatusSource>()
        val manga = Manga.create().copy(id = 9, source = 42)
        val chapter = Chapter.create().copy(id = 7, mangaId = manga.id, url = "book", read = false)
        every { preferences.removeAfterMarkedAsRead.get() } returns true
        coEvery { mangas.getMangaById(manga.id) } returns manga
        every { sources.get(manga.source) } returns source
        coEvery { source.commitChapterReadStatus(chapter, true) } returns status

        val result = interactor(preferences, delete, mangas, chapters, sources).await(true, chapter)

        assertEquals(SetReadStatus.Result.Completed(mapOf(chapter.id to status)), result)
        coVerify(exactly = 0) { chapters.updateAll(any()) }
        coVerify(exactly = 0) { source.setChapterReadStatus(any(), any()) }
        coVerify(exactly = 1) { source.commitChapterReadStatus(chapter, true) }
        coVerify(exactly = if (status.allowsDownloadDeletion) 1 else 0) { delete.awaitAll(manga, chapter) }
    }

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
        coEvery { source.setChapterReadStatus(chapter.url, false) } returns ConnectionReadStatus.SYNCED

        val result = interactor(
            downloadPreferences = downloadPreferences,
            deleteDownload = deleteDownload,
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = sourceManager,
        ).await(read = false, chapter)

        assertEquals(SetReadStatus.Result.Completed(mapOf(chapter.id to ConnectionReadStatus.SYNCED)), result)
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
        coEvery { source.setChapterReadStatus(second.url, false) } returns ConnectionReadStatus.SYNCED

        val result = interactor(
            mangaRepository = mangaRepository,
            chapterRepository = chapterRepository,
            sourceManager = sourceManager,
        ).await(read = false, first, second)

        assertEquals(
            SetReadStatus.Result.Completed(
                mapOf(first.id to ConnectionReadStatus.UNAVAILABLE, second.id to ConnectionReadStatus.SYNCED),
            ),
            result,
        )
        coVerify(exactly = 1) { source.setChapterReadStatus(first.url, false) }
        coVerify(exactly = 1) { source.setChapterReadStatus(second.url, false) }
    }

    @ParameterizedTest
    @EnumSource(ConnectionReadStatus::class)
    fun `download deletion requires a durable accepted operation`(status: ConnectionReadStatus) = runTest {
        val preferences = mockk<DownloadPreferences>()
        val delete = mockk<DeleteDownload>(relaxed = true)
        val mangas = mockk<MangaRepository>()
        val chapters = mockk<ChapterRepository>(relaxed = true)
        val sources = mockk<SourceManager>()
        val source = mockk<ReadStatusSource>()
        val manga = Manga.create().copy(id = 9, source = 42)
        val chapter = Chapter.create().copy(id = 7, mangaId = manga.id, url = "book", read = false)
        every { preferences.removeAfterMarkedAsRead.get() } returns true
        coEvery { mangas.getMangaById(manga.id) } returns manga
        every { sources.get(manga.source) } returns source
        coEvery { source.setChapterReadStatus(chapter.url, true) } returns status

        val result = interactor(preferences, delete, mangas, chapters, sources).await(true, chapter)

        assertEquals(SetReadStatus.Result.Completed(mapOf(chapter.id to status)), result)
        coVerify(exactly = if (status.allowsDownloadDeletion) 1 else 0) { delete.awaitAll(manga, chapter) }
    }

    @Test
    fun `repeating an unchanged local flag retries the provider without deleting downloads`() = runTest {
        val preferences = mockk<DownloadPreferences>()
        val delete = mockk<DeleteDownload>(relaxed = true)
        val mangas = mockk<MangaRepository>()
        val chapters = mockk<ChapterRepository>(relaxed = true)
        val sources = mockk<SourceManager>()
        val source = mockk<ReadStatusSource>()
        val manga = Manga.create().copy(id = 9, source = 42)
        val chapter = Chapter.create().copy(id = 7, mangaId = manga.id, url = "book", read = true)
        every { preferences.removeAfterMarkedAsRead.get() } returns true
        coEvery { mangas.getMangaById(manga.id) } returns manga
        every { sources.get(manga.source) } returns source
        coEvery { source.setChapterReadStatus(chapter.url, true) } returns ConnectionReadStatus.PENDING

        val result = interactor(preferences, delete, mangas, chapters, sources).await(true, chapter, chapter)

        assertEquals(SetReadStatus.Result.Completed(mapOf(chapter.id to ConnectionReadStatus.PENDING)), result)
        coVerify(exactly = 1) { source.setChapterReadStatus(chapter.url, true) }
        coVerify(exactly = 0) { chapters.updateAll(any()) }
        coVerify(exactly = 0) { delete.awaitAll(any(), *anyVararg()) }
    }

    @Test
    fun `missing source preserves downloads and is not reported as local-only support`() = runTest {
        val preferences = mockk<DownloadPreferences>()
        val delete = mockk<DeleteDownload>(relaxed = true)
        val mangas = mockk<MangaRepository>()
        val chapters = mockk<ChapterRepository>(relaxed = true)
        val sources = mockk<SourceManager>()
        val manga = Manga.create().copy(id = 9, source = 42)
        val chapter = Chapter.create().copy(id = 7, mangaId = manga.id, read = false)
        every { preferences.removeAfterMarkedAsRead.get() } returns true
        coEvery { mangas.getMangaById(manga.id) } returns manga
        every { sources.get(manga.source) } returns null

        val result = interactor(preferences, delete, mangas, chapters, sources).await(true, chapter)

        assertEquals(SetReadStatus.Result.Completed(mapOf(chapter.id to ConnectionReadStatus.UNAVAILABLE)), result)
        coVerify(exactly = 0) { delete.awaitAll(any(), *anyVararg()) }
    }

    @Test
    fun `failed local commit cannot send remote status or delete downloads`() = runTest {
        val mangas = mockk<MangaRepository>()
        val chapters = mockk<ChapterRepository>()
        val sources = mockk<SourceManager>()
        val delete = mockk<DeleteDownload>(relaxed = true)
        val error = IllegalStateException("database write failed")
        coEvery { chapters.updateAll(any()) } throws error

        val result = interactor(
            deleteDownload = delete,
            mangaRepository = mangas,
            chapterRepository = chapters,
            sourceManager = sources,
        ).await(true, Chapter.create())

        assertSame(error, assertInstanceOf(SetReadStatus.Result.InternalError::class.java, result).error)
        coVerify(exactly = 0) { delete.awaitAll(any(), *anyVararg()) }
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
