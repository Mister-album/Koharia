package koharia.source.local

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionLibraryRefreshResult
import koharia.connection.LibraryContentScope
import koharia.domain.epub.interactor.GetEpubProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class, InternalVoyagerApi::class)
class LocalLibraryLoadingTest {
    @Test
    fun `first database result is pending rather than an empty library`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val databaseRequested = CompletableDeferred<Unit>()
        val databaseReady = CompletableDeferred<Unit>()
        val book = Manga.create().copy(id = 1, source = 42)
        val fixture = fixture(
            mangas = flow {
                databaseRequested.complete(Unit)
                databaseReady.await()
                emit(listOf(book))
            },
        )
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            databaseRequested.await()
            assertNull(presenter.loadStateFlow.value)
            coVerify(exactly = 0) { fixture.source.browseIndexedLibrary(any(), any(), any(), any(), any()) }
            databaseReady.complete(Unit)
            presenter.loadStateFlow.first { it?.refresh is LoadState.NotLoading }
            assertEquals(listOf(book.id), presenter.snapshot().items.map { it.value.id })
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `opening uses cached books and only manual refresh starts a scan`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val scanStarted = CompletableDeferred<Unit>()
        val finishScan = CompletableDeferred<Unit>()
        val cachedBook = Manga.create().copy(id = 1, source = 42)
        val mangas = MutableStateFlow(listOf(cachedBook))
        val book = Manga.create().copy(id = 7, source = 42)
        val fixture = fixture(mangas, needsScan = true) {
            scanStarted.complete(Unit)
            finishScan.await()
            mangas.value = listOf(book)
            Result.success(ConnectionLibraryRefreshResult(1, 1))
        }
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            presenter.loadStateFlow.first { it?.refresh is LoadState.NotLoading }
            assertEquals(listOf(cachedBook.id), presenter.snapshot().items.map { it.value.id })
            coVerify(exactly = 0) { fixture.source.needsInitialScan() }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
            fixture.model.refresh()
            scanStarted.await()
            assertTrue(fixture.model.state.value.isRefreshing)
            assertEquals(listOf(cachedBook.id), presenter.snapshot().items.map { it.value.id })
            finishScan.complete(Unit)
            presenter.presentedIds.first { it == listOf(book.id) }
            assertEquals(listOf(book.id), presenter.snapshot().items.map { it.value.id })
            coVerify(exactly = 1) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed manual scan preserves its error without resuming an automatic scan`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(flowOf(emptyList()), needsScan = true) { Result.failure(IOException("scan failed")) }
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            presenter.loadStateFlow.first { it?.refresh is LoadState.NotLoading }
            assertTrue(presenter.snapshot().isEmpty())
            assertNull(fixture.model.state.value.refreshError)
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
            fixture.model.refresh()
            fixture.model.state.first { it.refreshError != null && !it.isRefreshing }
            assertNotNull(fixture.model.state.value.refreshError)
            assertEquals(false, fixture.model.state.value.isRefreshing)
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `genuinely empty library finishes loading without an error`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = fixture(flowOf(emptyList()))
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            presenter.loadStateFlow.first { it?.refresh is LoadState.NotLoading }
            assertTrue(presenter.snapshot().isEmpty())
            assertNull(fixture.model.state.value.refreshError)
            coVerify(exactly = 0) { fixture.source.needsInitialScan() }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `shelf progress uses cached counts without opening documents`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val partial = Manga.create().copy(id = 1, source = 42, url = "partial")
        val finished = Manga.create().copy(id = 2, source = 42, url = "finished")
        val fixture = fixture(
            mangas = flowOf(listOf(partial, finished)),
            progressChapters = mapOf(
                partial.id to listOf(Chapter.create().copy(id = 1, mangaId = partial.id, lastPageRead = 12)),
                finished.id to listOf(Chapter.create().copy(id = 2, mangaId = finished.id, read = true)),
            ),
        )
        try {
            val progress = fixture.model.readProgressByUrl.first { finished.url in it }
            assertEquals(setOf(finished.url), progress.keys)
            assertEquals(100L, progress.getValue(finished.url).readCount)
            coVerify(exactly = 0) { fixture.source.documentPageCount(any()) }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `network downloads retain indexed totals when reading progress is hidden`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manga = Manga.create().copy(id = 1, source = 42, url = "book")
        val fixture = fixture(flowOf(listOf(manga)), networkStorage = true)
        try {
            val counts = fixture.model.readingUnitCounts.first { manga.url in it }
            assertEquals(1L, counts.getValue(manga.url))
            coVerify(exactly = 0) { fixture.source.documentPageCount(any()) }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `reading filters apply immediately with progress hidden and keep the settings sheet open`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val unread = Manga.create().copy(id = 1, source = 42, url = "unread")
        val read = Manga.create().copy(id = 2, source = 42, url = "read")
        val fixture = fixture(
            flowOf(listOf(unread, read)),
            progressChapters = mapOf(read.id to listOf(Chapter.create().copy(id = 2, mangaId = read.id, read = true))),
        )
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            presenter.presentedIds.first { it.size == 2 }
            fixture.model.openFilterDialog()
            fixture.model.updateFilters(LocalLibraryFilters(unread = TriState.ENABLED_IS), false)
            presenter.presentedIds.first { it == listOf(unread.id) }
            assertEquals(LocalLibraryScreenModel.Dialog.Filter, fixture.model.state.value.dialog)
            fixture.model.updateFilters(LocalLibraryFilters(unread = TriState.ENABLED_NOT), false)
            presenter.presentedIds.first { it == listOf(read.id) }
            fixture.model.updateFilters(LocalLibraryFilters(), false)
            presenter.presentedIds.first { it.size == 2 }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `bookmark filter observes chapter changes without a manual refresh`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manga = Manga.create().copy(id = 1, source = 42, url = "book")
        val chapter = Chapter.create().copy(id = 1, mangaId = manga.id)
        val chapters = MutableStateFlow(mapOf(manga.id to listOf(chapter)))
        val fixture = fixture(flowOf(listOf(manga)), chapterStates = chapters)
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            presenter.presentedIds.first { it == listOf(manga.id) }
            fixture.model.updateFilters(LocalLibraryFilters(bookmarked = TriState.ENABLED_IS), false)
            presenter.presentedIds.first { it.isEmpty() }
            chapters.value = mapOf(manga.id to listOf(chapter.copy(bookmark = true)))
            presenter.presentedIds.first { it == listOf(manga.id) }
            chapters.value = mapOf(manga.id to listOf(chapter))
            presenter.presentedIds.first { it.isEmpty() }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `network download filter reacts to deletion without confusing it with reading progress`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val manga = Manga.create().copy(id = 1, source = 42, url = "book")
        val counts = mutableMapOf(manga.id to 1)
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val fixture =
            fixture(flowOf(listOf(manga)), networkStorage = true, downloadCounts = counts, downloadChanges = changes)
        try {
            val presenter = presenter()
            backgroundScope.launch(dispatcher) { fixture.model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
            fixture.model.updateFilters(LocalLibraryFilters(downloaded = TriState.ENABLED_IS), false)
            presenter.presentedIds.first { it == listOf(manga.id) }
            counts[manga.id] = 0
            changes.emit(Unit)
            presenter.presentedIds.first { it.isEmpty() }
            fixture.model.updateFilters(LocalLibraryFilters(downloaded = TriState.ENABLED_NOT), false)
            presenter.presentedIds.first { it == listOf(manga.id) }
            coVerify(exactly = 0) { fixture.source.refreshLibrary() }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `folder title mode updates the owning manga through shared chapter settings`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val folder = Manga.create().copy(id = 9, source = 42, url = "folder")
        val flags = mockk<SetMangaChapterFlags>()
        val applied = CompletableDeferred<Unit>()
        coEvery { flags.awaitSetDisplayMode(folder, Manga.CHAPTER_DISPLAY_FILE_NAME) } coAnswers {
            applied.complete(Unit)
            true
        }
        val fixture = fixture(flowOf(listOf(folder)), parentUrl = folder.url, setMangaChapterFlags = flags)
        try {
            fixture.model.setTitleDisplayMode(Manga.CHAPTER_DISPLAY_FILE_NAME)
            applied.await()
            coVerify(exactly = 1) { flags.awaitSetDisplayMode(folder, Manga.CHAPTER_DISPLAY_FILE_NAME) }
        } finally {
            ScreenModelStore.onDisposeNavigator(fixture.holderKey)
            Dispatchers.resetMain()
        }
    }

    private fun presenter() = RecordingPresenter()

    private class RecordingPresenter : PagingDataPresenter<StateFlow<Manga>>(Dispatchers.Main) {
        val presentedIds = MutableStateFlow(emptyList<Long>())
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<StateFlow<Manga>>) {
            presentedIds.value = snapshot().items.map { it.value.id }
        }
    }

    private fun fixture(
        mangas: Flow<List<Manga>>,
        needsScan: Boolean = false,
        networkStorage: Boolean = false,
        progressChapters: Map<Long, List<Chapter>> = emptyMap(),
        chapterStates: MutableStateFlow<Map<Long, List<Chapter>>> = MutableStateFlow(progressChapters),
        downloadCounts: Map<Long, Int> = emptyMap(),
        downloadChanges: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1),
        parentUrl: String? = null,
        setMangaChapterFlags: SetMangaChapterFlags = mockk(),
        refresh: suspend () -> Result<ConnectionLibraryRefreshResult> = {
            Result.success(ConnectionLibraryRefreshResult(0, 1))
        },
    ): Fixture {
        val source = mockk<LocalFolderSource>()
        every { source.supportsFileTransfers } returns networkStorage
        val sourceManager = mockk<SourceManager>()
        val sourcePreferences = mockk<SourcePreferences>()
        val mangaRepository = mockk<MangaRepository>()
        val getChapters = mockk<GetChaptersByMangaId>()
        val getProgress = mockk<GetEpubProgress>()
        val chapters = mockk<ChapterRepository>()
        val downloads = mockk<DownloadManager>()
        every { downloads.cacheChanges } returns downloadChanges
        every { downloads.getDownloadCount(any<Manga>()) } answers { downloadCounts[firstArg<Manga>().id] ?: 0 }
        coEvery { chapters.getChapterByMangaIdAsFlow(any(), any()) } answers {
            val mangaId = firstArg<Long>()
            chapterStates.map { it[mangaId].orEmpty() }
        }
        every { getProgress.subscribeByMangaId(any()) } returns flowOf(emptyList())
        coEvery { getChapters.await(any<Collection<Long>>()) } coAnswers { chapterStates.value }
        coEvery { getProgress.await(any<Collection<Long>>()) } returns emptyMap()
        coEvery { mangaRepository.getMangaBySourceId(42) } coAnswers { mangas.first() }
        coEvery { mangaRepository.getMangaByUrlAndSourceId(any(), 42) } coAnswers {
            mangas.first().firstOrNull { it.url == firstArg<String>() }
        }
        every { source.readProgressIndexes(any()) } answers {
            firstArg<Collection<String>>().associateWith { LocalReadProgressIndex(1, true) }
        }
        every { source.indexedEntry(any()) } returns null
        coEvery { source.documentPageCount(any()) } returns 20
        every { sourceManager.getOrStub(42) } returns source
        every { sourcePreferences.sourceDisplayMode } returns
            preference<LibraryDisplayMode>(LibraryDisplayMode.CompactGrid)
        every { source.libraryRefreshes } returns MutableSharedFlow()
        every { source.libraryShelves } returns flowOf(emptyList())
        coEvery { source.needsInitialScan() } returns needsScan
        coEvery { source.resumePendingNetworkScan() } returns Unit
        coEvery { source.refreshLibrary() } coAnswers { refresh() }
        coEvery { source.browseIndexedLibrary(any(), any(), any(), any(), any()) } coAnswers { firstArg() }
        every { mangaRepository.getMangaBySourceIdAsFlow(42) } returns mangas
        val holderKey = UUID.randomUUID().toString()
        val model = ScreenModelStore.getOrPut(holderKey, null) {
            LocalLibraryScreenModel(
                sourceId = 42, scope = LibraryContentScope.ALL, initialQuery = null,
                sourceManager = sourceManager, sourcePreferences = sourcePreferences, mangaRepository = mangaRepository,
                getChaptersByMangaId = getChapters, getEpubProgress = getProgress,
                setMangaChapterFlags = setMangaChapterFlags, entryOpenManager = mockk(), updateManga = mockk(),
                coverCache = mockk(), itemActions = mockk(),
                chapterRepository = chapters, downloadManager = downloads,
                parentUrl = parentUrl,
            )
        }
        return Fixture(model, source, holderKey)
    }

    private fun <T> preference(value: T): Preference<T> = mockk<Preference<T>>().also {
        every { it.get() } returns value
        every { it.changes() } returns flowOf(value)
    }

    private data class Fixture(val model: LocalLibraryScreenModel, val source: LocalFolderSource, val holderKey: String)
}
