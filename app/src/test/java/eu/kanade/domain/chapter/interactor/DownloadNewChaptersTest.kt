package eu.kanade.domain.chapter.interactor

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.connection.ChapterFetchOrigin
import koharia.connection.ChapterFetchSession
import koharia.connection.ConnectionChapterOwner
import koharia.connection.ConnectionChapterSessionAdapter
import koharia.connection.ConnectionMangaBehavior
import koharia.connection.ConnectionMangaBehaviorAdapter
import koharia.connection.MutableTestPreferenceStore
import koharia.domain.chapter.interactor.FilterChaptersForDownload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class DownloadNewChaptersTest {
    private suspend fun fetched(origin: ChapterFetchOrigin = ChapterFetchOrigin.EXPLICIT_REFRESH) =
        ChapterFetchSession.capture(source, origin, manager).fetch { emptyList() }

    private abstract class DownloadSource : Source, ConnectionMangaBehaviorAdapter, ConnectionChapterSessionAdapter

    private val manga = Manga.create().copy(id = 11, source = 42, url = "series", favorite = true)
    private val chapters = listOf(
        Chapter.create().copy(id = 21, mangaId = manga.id, url = "first", chapterNumber = 1.0),
        Chapter.create().copy(id = 22, mangaId = manga.id, url = "second", chapterNumber = 2.0),
    )
    private var currentManga: Manga? = manga
    private var allowed = true
    private var ownerGeneration = 0
    private val source = mockk<DownloadSource> {
        every { id } returns manga.source
        every { mangaBehavior } answers { ConnectionMangaBehavior(allowsChapterDownloads = allowed) }
        every { captureChapterOwner() } answers {
            val generation = ownerGeneration
            ConnectionChapterOwner("owner-$generation") {
                if (generation != ownerGeneration) throw CancellationException("Owner changed")
            }
        }
    }
    private var currentSource: Source? = source
    private val manager = mockk<SourceManager> { every { get(manga.source) } answers { currentSource } }
    private val getManga = mockk<GetManga> { coEvery { await(manga.id) } answers { currentManga } }
    private val categories = mockk<GetCategories> { coEvery { await(manga.id) } returns emptyList() }
    private val getChapters = mockk<GetChaptersByMangaId> { coEvery { await(manga.id) } returns emptyList() }
    private val preferences = DownloadPreferences(MutableTestPreferenceStore(mutableMapOf())).apply {
        downloadNewChapters.set(true)
    }
    private val downloads = mockk<DownloadManager>(relaxed = true)
    private val subject = DownloadNewChapters(
        FilterChaptersForDownload(getChapters, preferences, categories),
        getManga,
        manager,
        downloads,
    )

    @Test
    fun `opening and progress preparation never trigger automatic downloads`() = runTest {
        for (origin in listOf(ChapterFetchOrigin.OPEN, ChapterFetchOrigin.PROGRESS)) {
            assertFalse(subject.await(manga, fetched(origin), chapters))
        }
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `automatic download preserves default category and deferred batch startup`() = runTest {
        preferences.downloadNewChapterCategories.set(setOf("0"))
        assertTrue(subject.await(manga, fetched(), chapters, autoStart = false))
        verify(exactly = 1) { downloads.downloadChapters(manga, chapters, false, null) }
        verify(exactly = 0) { downloads.startDownloads() }
    }

    @Test fun `actual server content events retain automatic download duties`() = runTest {
        assertTrue(subject.await(manga, fetched(ChapterFetchOrigin.SERVER_UPDATE), chapters))
        verify(exactly = 1) { downloads.downloadChapters(manga, chapters, true, null) }
    }

    @Test
    fun `existing excluded categories and duplicate read number rules still apply`() = runTest {
        coEvery { categories.await(manga.id) } returns listOf(Category(3, "Test", 0, 0))
        preferences.downloadNewChapterCategoriesExclude.set(setOf("3"))
        assertFalse(subject.await(manga, fetched(), chapters))
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }

        preferences.downloadNewChapterCategoriesExclude.set(emptySet())
        preferences.downloadNewUnreadChaptersOnly.set(true)
        coEvery { getChapters.await(manga.id) } returns listOf(chapters.first().copy(read = true))
        assertTrue(subject.await(manga, fetched(), chapters))
        verify(exactly = 1) { downloads.downloadChapters(manga, listOf(chapters.last()), true, null) }
    }

    @Test
    fun `disabled option missing favorite and denied capability do not queue`() = runTest {
        preferences.downloadNewChapters.set(false)
        assertFalse(subject.await(manga, fetched(), chapters))
        preferences.downloadNewChapters.set(true)
        currentManga = manga.copy(favorite = false)
        assertFalse(subject.await(manga, fetched(), chapters))
        currentManga = manga
        allowed = false
        assertFalse(subject.await(manga, fetched(), chapters))
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `entry removed or changed while category query suspends is never queued`() = runTest {
        for (replacement in listOf(
            null,
            manga.copy(favorite = false),
            manga.copy(url = "other"),
            manga.copy(source = 43),
        )) {
            currentManga = manga
            coEvery { categories.await(manga.id) } coAnswers {
                currentManga = replacement
                emptyList()
            }
            assertFalse(subject.await(manga, fetched(), chapters))
        }
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `permission lost while filtering is respected before enqueue`() = runTest {
        coEvery { categories.await(manga.id) } coAnswers {
            allowed = false
            emptyList()
        }
        assertFalse(subject.await(manga, fetched(), chapters))
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `replaced source during filtering cancels without adopting current connection`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { categories.await(manga.id) } coAnswers {
            entered.complete(Unit)
            release.await()
            emptyList()
        }
        val pending = async { runCatching { subject.await(manga, fetched(), chapters) } }
        entered.await()
        currentSource = mockk<Source>()
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `same source owner change during filtering cancels automatic download`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { categories.await(manga.id) } coAnswers {
            entered.complete(Unit)
            release.await()
            emptyList()
        }
        val pending = async { runCatching { subject.await(manga, fetched(), chapters) } }
        entered.await()
        ownerGeneration++
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `cancelled refresh cannot leave a detached automatic download`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { categories.await(manga.id) } coAnswers {
            entered.complete(Unit)
            release.await()
            emptyList()
        }
        val pending = async { subject.await(manga, fetched(), chapters) }
        entered.await()
        pending.cancel()
        pending.join()
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }

    @Test
    fun `foreign chapters fail closed and empty results have no effect`() = runTest {
        assertTrue(
            runCatching {
                subject.await(manga, fetched(), listOf(chapters.first().copy(mangaId = 99)))
            }.exceptionOrNull() is IllegalArgumentException,
        )
        assertFalse(subject.await(manga, fetched(), emptyList()))
        verify(exactly = 0) { downloads.downloadChapters(any(), any(), any(), any()) }
    }
}
