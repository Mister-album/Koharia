package koharia.source.local

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.source.service.SourcePreferences
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
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
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
            showReadProgress = true,
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
        showReadProgress: Boolean = false,
        progressChapters: Map<Long, List<Chapter>> = emptyMap(),
        refresh: suspend () -> Result<ConnectionLibraryRefreshResult> = {
            Result.success(ConnectionLibraryRefreshResult(0, 1))
        },
    ): Fixture {
        val source = mockk<LocalFolderSource>()
        val sourceManager = mockk<SourceManager>()
        val sourcePreferences = mockk<SourcePreferences>()
        val libraryPreferences = mockk<LibraryPreferences>()
        val mangaRepository = mockk<MangaRepository>()
        val getChapters = mockk<GetChaptersByMangaId>()
        val getProgress = mockk<GetEpubProgress>()
        coEvery { getChapters.await(any<Collection<Long>>()) } returns progressChapters
        coEvery { getProgress.await(any<Collection<Long>>()) } returns emptyMap()
        coEvery { mangaRepository.getMangaBySourceId(42) } coAnswers { mangas.first() }
        every { source.readProgressIndexes(any()) } answers {
            firstArg<Collection<String>>().associateWith { LocalReadProgressIndex(1, true) }
        }
        coEvery { source.documentPageCount(any()) } returns 20
        every { sourceManager.getOrStub(42) } returns source
        every { sourcePreferences.sourceDisplayMode } returns
            preference<LibraryDisplayMode>(LibraryDisplayMode.CompactGrid)
        every { libraryPreferences.showLibraryReadProgress } returns preference(showReadProgress)
        every { source.libraryRefreshes } returns MutableSharedFlow()
        every { source.libraryShelves } returns flowOf(emptyList())
        coEvery { source.needsInitialScan() } returns needsScan
        coEvery { source.refreshLibrary() } coAnswers { refresh() }
        coEvery { source.browseIndexedLibrary(any(), any(), any(), any(), any()) } coAnswers { firstArg() }
        every { mangaRepository.getMangaBySourceIdAsFlow(42) } returns mangas
        val holderKey = UUID.randomUUID().toString()
        val model = ScreenModelStore.getOrPut(holderKey, null) {
            LocalLibraryScreenModel(
                sourceId = 42, scope = LibraryContentScope.ALL, initialQuery = null,
                sourceManager = sourceManager, sourcePreferences = sourcePreferences, mangaRepository = mangaRepository,
                getChaptersByMangaId = getChapters, getEpubProgress = getProgress,
                libraryPreferences = libraryPreferences, entryOpenManager = mockk(), updateManga = mockk(),
                coverCache = mockk(), itemActions = mockk(),
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
