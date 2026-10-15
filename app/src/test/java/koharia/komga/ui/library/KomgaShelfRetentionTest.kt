package koharia.komga.ui.library

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.paging.PagingState
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.connection.LibraryContentScope
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.service.SourceManager
import java.util.UUID
import java.util.concurrent.Executors

@OptIn(InternalVoyagerApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KomgaShelfRetentionTest {
    @Test
    fun `production model releases paged rows without retaining one producer per visited manga`() =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            runBlocking(dispatcher) {
                Dispatchers.setMain(dispatcher)
                val holder = UUID.randomUUID().toString()
                try {
                    val source = mockk<KomgaSource>(relaxed = true) {
                        every { id } returns 42L
                        every { hasValidBaseUrl() } returns true
                        every { getFilterList() } returns FilterList()
                        every {
                            buildFilterListForLibrary(
                                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                            )
                        } returns
                            FilterList()
                    }
                    val sourceManager = object : SourceManager by mockk<SourceManager>() {
                        override fun getOrStub(sourceKey: Long) = source
                        override val catalogueSources = flowOf(listOf(source))
                    }
                    val sourcePreferences = mockk<SourcePreferences> {
                        every { sourceDisplayMode } returns preference<LibraryDisplayMode>(LibraryDisplayMode.List)
                    }
                    val base = mockk<BasePreferences> {
                        every { downloadedOnly } returns preference(false)
                    }
                    val library = mockk<LibraryPreferences> {
                        every { showLibraryReadProgress } returns preference(false)
                    }
                    val classifications = mockk<KomgaLibraryClassificationManager>(relaxed = true) {
                        every { getLibraries(42) } returns emptyList()
                        every { classificationsChanges(42) } returns emptyFlow()
                    }
                    val incognito = mockk<GetIncognitoState> { every { await(42) } returns true }
                    val getManga = mockk<GetManga> {
                        every { subscribe(any(), any()) } returns flowOf(null)
                    }
                    val remote = mockk<GetRemoteManga>()
                    every { remote(42L, any(), any()) } answers {
                        object : SourcePagingSource() {
                            override fun getRefreshKey(state: PagingState<Long, Manga>): Long? = null
                            override suspend fun load(params: LoadParams<Long>): LoadResult<Long, Manga> {
                                val page = params.key ?: 1L
                                return LoadResult.Page(
                                    (0 until 25).map {
                                        val id = (page - 1) * 25 + it
                                        Manga.create().copy(id = id, source = 42, url = "$id", title = "Fixture $id")
                                    },
                                    (page - 1).takeIf { page > 1 },
                                    (page + 1).takeIf { page < 80 },
                                )
                            }
                        }
                    }
                    val model = ScreenModelStore.getOrPut(holder, null) {
                        KomgaLibraryScreenModel(
                            42, null, sourceManager, sourcePreferences, base, library, mockk(), remote, getManga,
                            mockk(), mockk(), mockk(), incognito, LibraryContentScope.ALL, classifications, mockk(),
                        )
                    }
                    val presenter = object : PagingDataPresenter<StateFlow<Manga>>(dispatcher) {
                        override suspend fun presentPagingDataEvent(
                            event: PagingDataEvent<StateFlow<Manga>>,
                        ) = Unit
                    }
                    val job = launch(dispatcher) { model.mangaPagerFlow.collectLatest(presenter::collectFrom) }
                    try {
                        withTimeout(15_000) {
                            presenter.loadStateFlow.first { it?.refresh is LoadState.NotLoading }
                            val initialJobs = model.ioCoroutineScope.coroutineContext[Job]!!.children.count()
                            while (presenter.snapshot().items.lastOrNull()?.value?.id != 1999L) {
                                presenter[presenter.size - 1]
                                delay(10)
                            }
                            assertTrue(presenter.size <= 300)
                            assertTrue(presenter.snapshot().items.first().value.id > 0)
                            assertTrue(
                                model.ioCoroutineScope.coroutineContext[Job]!!.children.count() <= initialJobs + 4,
                            )
                            verify(exactly = 0) { getManga.subscribe(any(), any()) }
                            while (presenter.snapshot().items.firstOrNull()?.value?.id != 0L) {
                                presenter[0]
                                delay(10)
                            }
                            assertEquals(0L, presenter.snapshot().items.first().value.id)
                        }
                    } finally {
                        job.cancelAndJoin()
                    }
                } finally {
                    ScreenModelStore.onDisposeNavigator(holder)
                    Dispatchers.resetMain()
                }
            }
        }

    private fun <T> preference(value: T): Preference<T> = mockk {
        every { get() } returns value
        every { changes() } returns MutableSharedFlow()
    }
}
