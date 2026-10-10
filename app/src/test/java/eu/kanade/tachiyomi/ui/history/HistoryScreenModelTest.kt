package eu.kanade.tachiyomi.ui.history

import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.source.CatalogueSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionEpubHistorySyncAdapter
import koharia.connection.ConnectionHistorySyncAdapter
import koharia.connection.ConnectionPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.service.SourceManager
import java.io.IOException
import java.util.Date
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class, InternalVoyagerApi::class)
class HistoryScreenModelTest {
    @Test
    fun `history filters account manga with one scope query per history emission`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1, 3))
        try {
            fixture.awaitVisible(3, 1)

            coVerify(exactly = 1) { fixture.adapter.historyMangaIds() }
            fixture.history.value = fixture.history.value + history(4, 400)
            fixture.allowed = setOf(1, 3, 4)
            fixture.changeAccount()
            fixture.awaitVisible(3, 1, 4)
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `same source account changes resubscribe and hide the prior account history`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        try {
            fixture.awaitVisible(1)

            fixture.allowed = setOf(2)
            fixture.changeAccount()

            fixture.awaitVisible(2)
            assertEquals(listOf(2L), fixture.visible())
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `replacing a provider under the same connection subscribes to the new account scope`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        try {
            fixture.awaitVisible(1)
            val replacement = mockk<CatalogueSource>(moreInterfaces = arrayOf(ConnectionHistorySyncAdapter::class))
            val replacementAdapter = replacement as ConnectionHistorySyncAdapter
            every { replacement.id } returns 7
            every { replacementAdapter.historyScopeChanges } returns MutableStateFlow(Unit)
            coEvery { replacementAdapter.historyMangaIds() } returns setOf(2)
            coEvery { replacementAdapter.syncConnectionHistory() } returns Unit

            fixture.activeSource = replacement
            fixture.catalogue.value = listOf(replacement)

            fixture.awaitVisible(2)
            coVerify(atLeast = 1) { replacementAdapter.historyMangaIds() }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clear history deletes only current account manga without a connection wide delete`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        try {
            fixture.awaitVisible(1)
            fixture.allowed = setOf(2, 3)
            fixture.changeAccount()
            fixture.awaitVisible(2, 3)

            fixture.model.removeAllHistory()
            fixture.model.events.first { it == HistoryScreenModel.Event.HistoryCleared }

            coVerify(exactly = 1) { fixture.removeHistory.await(2L) }
            coVerify(exactly = 1) { fixture.removeHistory.await(3L) }
            coVerify(exactly = 0) { fixture.removeHistory.await(1L) }
            coVerify(exactly = 0) { fixture.removeHistory.awaitAll(any()) }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `resume reading selects the most recent history in the current account`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1, 3))
        val expected = Chapter.create().copy(id = 31, mangaId = 3)
        coEvery { fixture.nextChapters.await(3, 31, false) } returns listOf(expected)
        try {
            fixture.awaitVisible(3, 1)

            assertEquals(expected, fixture.model.getNextChapter())

            coVerify(exactly = 1) { fixture.nextChapters.await(3, 31, false) }
            coVerify(exactly = 0) { fixture.nextChapters.await(onlyUnread = false) }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an empty account cannot resume global history or delete another account history`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(emptySet())
        try {
            fixture.awaitVisible()
            assertNull(fixture.model.getNextChapter())
            fixture.model.removeAllHistory()
            fixture.model.events.first { it == HistoryScreenModel.Event.HistoryCleared }

            coVerify(exactly = 0) { fixture.nextChapters.await(onlyUnread = false) }
            coVerify(exactly = 0) { fixture.removeHistory.await(any<Long>()) }
            coVerify(exactly = 0) { fixture.removeHistory.awaitAll(any()) }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a late resume result is discarded after the account changes`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { fixture.nextChapters.await(1, 11, false) } coAnswers {
            entered.complete(Unit)
            release.await()
            listOf(Chapter.create().copy(id = 11, mangaId = 1))
        }
        try {
            fixture.awaitVisible(1)
            val result = async { fixture.model.getNextChapter() }
            entered.await()
            fixture.allowed = setOf(2)
            fixture.changeAccount()
            fixture.awaitVisible(2)

            release.complete(Unit)

            assertNull(result.await())
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `providers without an account restriction keep their existing clear and resume behavior`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(null)
        val expected = Chapter.create().copy(id = 21, mangaId = 2)
        coEvery { fixture.nextChapters.await(onlyUnread = false) } returns listOf(expected)
        try {
            fixture.awaitVisible(2, 3, 1)

            assertEquals(expected, fixture.model.getNextChapter())
            fixture.model.removeAllHistory()
            fixture.model.events.first { it == HistoryScreenModel.Event.HistoryCleared }

            coVerify(exactly = 1) { fixture.removeHistory.awaitAll(7) }
            coVerify(exactly = 0) { fixture.removeHistory.await(any<Long>()) }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `manual refresh synchronizes and reloads account membership without losing the search`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        try {
            fixture.awaitVisible(1)
            fixture.initialSync.await()
            fixture.model.updateSearchQuery("Book")
            fixture.awaitVisible(1)
            coEvery { fixture.adapter.syncConnectionHistory() } coAnswers {
                fixture.allowed = setOf(2)
            }

            fixture.model.refreshHistory()
            fixture.awaitVisible(2)
            fixture.model.state.first { !it.isRefreshing }

            assertEquals("Book", fixture.model.state.value.searchQuery)
            coVerify(exactly = 2) { fixture.adapter.syncConnectionHistory() }
            io.mockk.verify(exactly = 2) { fixture.getHistory.subscribe("Book", 7) }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `refresh keeps the current rows visible and ignores repeated requests while syncing`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            fixture.awaitVisible(1)
            fixture.initialSync.await()
            coEvery { fixture.adapter.syncConnectionHistory() } coAnswers {
                entered.complete(Unit)
                release.await()
            }

            fixture.model.refreshHistory()
            entered.await()
            fixture.model.refreshHistory()
            fixture.model.refreshHistory()

            assertTrue(fixture.model.state.value.isRefreshing)
            assertEquals(listOf(1L), fixture.visible())
            coVerify(exactly = 2) { fixture.adapter.syncConnectionHistory() }

            release.complete(Unit)
            fixture.model.state.first { !it.isRefreshing }
            coVerify(exactly = 2) { fixture.adapter.syncConnectionHistory() }
        } finally {
            release.complete(Unit)
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed refresh retains history reports an error and can be retried`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        try {
            fixture.awaitVisible(1)
            fixture.initialSync.await()
            coEvery { fixture.adapter.syncConnectionHistory() } throws IOException("offline")

            fixture.model.refreshHistory()
            fixture.model.events.first { it == HistoryScreenModel.Event.HistoryRefreshFailed }
            fixture.model.state.first { !it.isRefreshing }

            assertEquals(listOf(1L), fixture.visible())
            coEvery { fixture.adapter.syncConnectionHistory() } returns Unit
            fixture.model.refreshHistory()
            fixture.model.state.first { !it.isRefreshing }

            coVerify(exactly = 3) { fixture.adapter.syncConnectionHistory() }
            assertEquals(listOf(1L), fixture.visible())
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `switching account cancels the old refresh without reporting a refresh failure`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        try {
            fixture.awaitVisible(1)
            fixture.initialSync.await()
            coEvery { fixture.adapter.syncConnectionHistory() } coAnswers {
                entered.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } catch (error: CancellationException) {
                    cancelled.complete(Unit)
                    throw error
                }
            }

            fixture.model.refreshHistory()
            entered.await()
            coEvery { fixture.adapter.syncConnectionHistory() } returns Unit
            fixture.allowed = setOf(2)
            fixture.changeAccount()
            cancelled.await()
            fixture.awaitVisible(2)

            assertFalse(fixture.model.state.value.isRefreshing)
            assertEquals(listOf(2L), fixture.visible())
            fixture.model.refreshHistory()
            fixture.model.state.first { !it.isRefreshing }
            assertEquals(listOf(2L), fixture.visible())
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `providers without history sync still reload their local history`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(null)
        try {
            fixture.awaitVisible(2, 3, 1)
            fixture.initialSync.await()
            val localSource = mockk<CatalogueSource>()
            every { localSource.id } returns 7
            fixture.activeSource = localSource
            fixture.catalogue.value = listOf(localSource)
            fixture.awaitVisible(2, 3, 1)
            every { fixture.getHistory.subscribe("", 7) } returns flowOf(listOf(history(4, 400)))

            fixture.model.refreshHistory()
            fixture.awaitVisible(4)
            fixture.model.state.first { !it.isRefreshing }

            coVerify(exactly = 1) { fixture.adapter.syncConnectionHistory() }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `late failure from an obsolete account cannot stop the new refresh`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(setOf(1))
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val failure = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) {
            fixture.model.events.first { it == HistoryScreenModel.Event.HistoryRefreshFailed }
        }
        var calls = 0
        try {
            fixture.awaitVisible(1)
            fixture.initialSync.await()
            coEvery { fixture.adapter.syncConnectionHistory() } coAnswers {
                when (calls++) {
                    0 -> withContext(NonCancellable) {
                        oldEntered.complete(Unit)
                        oldRelease.await()
                        throw IOException("obsolete account")
                    }
                    1 -> Unit
                    else -> {
                        newEntered.complete(Unit)
                        newRelease.await()
                    }
                }
            }

            fixture.model.refreshHistory()
            oldEntered.await()
            fixture.allowed = setOf(2)
            fixture.changeAccount()
            fixture.awaitVisible(2)
            fixture.model.state.first { !it.isRefreshing }
            fixture.model.refreshHistory()
            oldRelease.complete(Unit)
            newEntered.await()

            assertTrue(fixture.model.state.value.isRefreshing)
            assertFalse(failure.isCompleted)
            assertEquals(listOf(2L), fixture.visible())

            newRelease.complete(Unit)
            fixture.model.state.first { !it.isRefreshing }
            assertFalse(failure.isCompleted)
        } finally {
            oldRelease.complete(Unit)
            newRelease.complete(Unit)
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `explicit refresh also synchronizes EPUB progress when the provider supports it`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixture = Fixture(null, includeEpubSync = true)
        val epubAdapter = fixture.source as ConnectionEpubHistorySyncAdapter
        try {
            fixture.awaitVisible(2, 3, 1)
            fixture.initialSync.await()
            coVerify(exactly = 0) { epubAdapter.syncConnectionEpubProgress() }

            fixture.model.refreshHistory()
            fixture.model.state.first { !it.isRefreshing }

            coVerify(exactly = 2) { fixture.adapter.syncConnectionHistory() }
            coVerify(exactly = 1) { epubAdapter.syncConnectionEpubProgress() }
        } finally {
            fixture.dispose()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(initialAllowed: Set<Long>?, includeEpubSync: Boolean = false) {
        val source = mockk<CatalogueSource>(
            moreInterfaces = if (includeEpubSync) {
                arrayOf(ConnectionHistorySyncAdapter::class, ConnectionEpubHistorySyncAdapter::class)
            } else {
                arrayOf(ConnectionHistorySyncAdapter::class)
            },
        )
        val adapter = source as ConnectionHistorySyncAdapter
        var activeSource: CatalogueSource = source
        var allowed: Set<Long>? = initialAllowed
        val catalogue = MutableStateFlow(listOf(source))
        val scopeChanges = MutableStateFlow(0)
        val history = MutableStateFlow(listOf(history(2, 300), history(3, 200), history(1, 100)))
        val nextChapters = mockk<GetNextChapters>()
        val removeHistory = mockk<RemoveHistory>()
        val getHistory = mockk<GetHistory>()
        val initialSync = CompletableDeferred<Unit>()
        private val sourceManager = object : SourceManager {
            override val isInitialized = MutableStateFlow(true)
            override val catalogueSources get() = catalogue
            override fun get(sourceKey: Long) = activeSource.takeIf { sourceKey == 7L }
            override fun getOrStub(sourceKey: Long) = checkNotNull(get(sourceKey))
            override fun getOnlineSources() = emptyList<eu.kanade.tachiyomi.source.online.HttpSource>()
            override fun getCatalogueSources() = catalogue.value
            override fun getStubSources() = emptyList<tachiyomi.domain.source.model.StubSource>()
        }
        private val connectionPreferences = mockk<ConnectionPreferences>()
        private val activePreference = mockk<Preference<Long>>()
        private val holderKey = UUID.randomUUID().toString()
        private lateinit var initializationJob: Job
        val model: HistoryScreenModel

        init {
            every { source.id } returns 7
            every { connectionPreferences.activeConnectionId } returns activePreference
            every { activePreference.get() } returns 7
            every { activePreference.changes() } returns MutableStateFlow(7L)
            every { adapter.historyScopeChanges } returns scopeChanges.map { Unit }
            coEvery { adapter.historyMangaIds() } coAnswers { allowed }
            coEvery { adapter.syncConnectionHistory() } coAnswers {
                initialSync.complete(Unit)
                Unit
            }
            if (includeEpubSync) {
                coEvery { (source as ConnectionEpubHistorySyncAdapter).syncConnectionEpubProgress() } returns Unit
            }
            every { getHistory.subscribe(any(), 7) } returns history
            coEvery { removeHistory.await(any<Long>()) } returns Unit
            coEvery { removeHistory.await(any<HistoryWithRelations>()) } returns Unit
            coEvery { removeHistory.awaitAll(7) } returns true
            model = ScreenModelStore.getOrPut(holderKey, null) {
                HistoryScreenModel(
                    addTracks = mockk(), getCategories = mockk(), getDuplicateLibraryManga = mockk(),
                    getHistory = getHistory, getManga = mockk(), getNextChapters = nextChapters,
                    libraryPreferences = mockk(), removeHistory = removeHistory, setMangaCategories = mockk(),
                    updateManga = mockk(), sourceManager = sourceManager, connectionPreferences = connectionPreferences,
                ).also { initializationJob = checkNotNull(it.screenModelScope.coroutineContext[Job]) }
            }
        }

        fun changeAccount() {
            scopeChanges.value++
        }

        fun visible() = model.state.value.list?.filterIsInstance<HistoryUiModel.Item>()?.map { it.item.mangaId }

        suspend fun awaitVisible(vararg ids: Long) {
            model.state.first { state ->
                state.list?.filterIsInstance<HistoryUiModel.Item>()?.map { it.item.mangaId } == ids.toList()
            }
        }

        suspend fun dispose() {
            val job = model.screenModelScope.coroutineContext[Job]
            ScreenModelStore.onDisposeNavigator(holderKey)
            initializationJob.join()
            job?.join()
        }
    }

    private companion object {
        fun history(mangaId: Long, readAt: Long) = HistoryWithRelations(
            id = mangaId,
            chapterId = mangaId * 10 + 1,
            mangaId = mangaId,
            title = "Book $mangaId",
            chapterNumber = 1.0,
            readAt = Date(readAt),
            readDuration = 0,
            coverData = MangaCover(mangaId, 7, false, null, 0),
        )
    }
}
