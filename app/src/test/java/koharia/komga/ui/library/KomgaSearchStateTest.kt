package koharia.komga.ui.library

import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.source.komga.KomgaLibraryClassificationManager
import koharia.source.komga.KomgaLibraryScope
import koharia.source.komga.KomgaSource
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TypeSelect
import koharia.source.komga.snapshotKomgaFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class, InternalVoyagerApi::class)
class KomgaSearchStateTest {
    @Test
    fun `quick sort applies immediately toggles direction and preserves search and browse snapshots`() = fixture(1) {
            model,
            source,
        ->
        model.search("comic")
        val originalSearchFilters = model.state.value.listing.filters
        model.selectSearchSort(2, false)
        val firstSelection = model.state.value.listing.filters
        assertEquals(Filter.Sort.Selection(2, false), firstSelection.filterIsInstance<SeriesSort>().single().state)
        assertEquals("comic", model.state.value.listing.query)
        assertNull(model.state.value.dialog)
        assertEquals(0, originalSearchFilters.sortIndex())

        model.selectSearchSort(2, true)
        assertEquals(
            Filter.Sort.Selection(2, true),
            model.state.value.filters.filterIsInstance<SeriesSort>().single().state,
        )
        assertEquals(Filter.Sort.Selection(2, false), firstSelection.filterIsInstance<SeriesSort>().single().state)
        model.selectSearchSort(1, true)
        assertEquals(2, model.state.value.filters.sortIndex())
        model.selectSearchSort(3, false)
        assertEquals(
            Filter.Sort.Selection(3, false),
            model.state.value.filters.filterIsInstance<SeriesSort>().single().state,
        )
        model.selectSearchSort(0, true)
        assertEquals(
            Filter.Sort.Selection(0, true),
            model.state.value.filters.filterIsInstance<SeriesSort>().single().state,
        )
        verify(exactly = 4) { source.savePersistentFilterState(any(), KomgaLibraryScope.ALL) }
        verify(exactly = 4) {
            source.saveSessionFilterState(
                match { it.filterIsInstance<TypeSelect>().single().state == TYPE_ALL_INDEX },
                KomgaLibraryScope.ALL,
            )
        }
        model.exitSearch()
        assertEquals(1, model.state.value.filters.sortIndex())
    }

    @Test
    fun `single type quick name sort toggles and all falls back to relevance`() = fixture(2) { model, _ ->
        model.search("comic")
        model.setSearchType(TYPE_BOOKS_INDEX)
        model.selectSearchSort(1, true)
        assertEquals(
            Filter.Sort.Selection(1, true),
            model.state.value.filters.filterIsInstance<SeriesSort>().single().state,
        )
        model.selectSearchSort(1, false)
        assertEquals(
            Filter.Sort.Selection(1, false),
            model.state.value.filters.filterIsInstance<SeriesSort>().single().state,
        )
        model.setSearchType(TYPE_ALL_INDEX)
        assertEquals(0, model.state.value.filters.sortIndex())
    }

    @Test
    fun `clearing query preserves all time sort routing and exit restores single type browsing`() = fixture(2) {
            model,
            _,
        ->
        assertFalse(model.state.value.listing.requiresSortedSearchSession())
        model.search("comic")
        assertTrue(model.state.value.listing.requiresSortedSearchSession())
        model.search("")
        assertEquals("", model.state.value.listing.query)
        assertTrue(model.state.value.listing.requiresSortedSearchSession())
        model.exitSearch()
        assertFalse(model.state.value.listing.requiresSortedSearchSession())
    }

    @Test
    fun `only all time sorting uses merge session regardless of query`() {
        for (query in listOf(null, "", "   ", "comic")) {
            for (index in 0..4) {
                for (type in listOf(TYPE_ALL_INDEX, TYPE_BOOKS_INDEX)) {
                    val filters = FilterList(
                        TypeSelect().apply { state = type },
                        SeriesSort(Filter.Sort.Selection(index, true)),
                    )
                    val listing = KomgaLibraryScreenModel.Listing.Search(query, filters)
                    assertEquals(type == TYPE_ALL_INDEX && index in 2..3, listing.requiresSortedSearchSession())
                }
            }
        }
        assertFalse(KomgaLibraryScreenModel.Listing.Popular.requiresSortedSearchSession())
        assertFalse(KomgaLibraryScreenModel.Listing.Latest.requiresSortedSearchSession())
    }

    @Test
    fun `search inherits time then restores browse snapshot and cancel discards draft`() = fixture(3) { model, source ->
        val original = model.state.value.filters
        model.search("comic")
        assertEquals(3, model.state.value.filters.sortIndex())
        assertEquals(TYPE_ALL_INDEX, model.state.value.searchType)
        model.setSearchType(TYPE_BOOKS_INDEX)
        assertEquals(3, model.state.value.filters.sortIndex())
        model.openFilterSheet()
        model.state.value.filters.filterIsInstance<SeriesSort>().single().state = Filter.Sort.Selection(2, true)
        model.setDialog(null)
        assertEquals(3, model.state.value.filters.sortIndex())
        model.openFilterSheet()
        model.state.value.filters.filterIsInstance<SeriesSort>().single().state = Filter.Sort.Selection(2, true)
        model.search(filters = model.state.value.filters)
        model.setDialog(null)
        assertEquals(2, model.state.value.filters.sortIndex())
        verify(exactly = 1) { source.savePersistentFilterState(any(), KomgaLibraryScope.ALL) }
        model.exitSearch()
        assertNull(model.state.value.listing.query)
        assertEquals(3, model.state.value.filters.sortIndex())
        assertEquals(3, original.sortIndex())
    }

    @Test
    fun `fallback and rapid switches do not persist browse changes`() = fixture(1) { model, source ->
        model.search("first")
        assertEquals(0, model.state.value.filters.sortIndex())
        repeat(5) {
            model.setSearchType(TYPE_BOOKS_INDEX)
            model.setSearchType(TYPE_ALL_INDEX)
            model.search("query-$it")
        }
        verify(exactly = 0) { source.savePersistentFilterState(any(), any()) }
        model.setPersistentFilteringEnabled(true)
        assertEquals(true, model.state.value.persistentFilteringEnabled)
        model.setPersistentFilteringEnabled(false)
        assertEquals(false, model.state.value.persistentFilteringEnabled)
        verify { source.setPersistentFilteringEnabled(false, any(), KomgaLibraryScope.ALL) }
        model.exitSearch()
        assertEquals(1, model.state.value.filters.sortIndex())
    }

    private fun FilterList.sortIndex() = filterIsInstance<SeriesSort>().single().state?.index

    private fun fixture(sort: Int, test: (KomgaLibraryScreenModel, KomgaSource) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher())
        val key = UUID.randomUUID().toString()
        val filters = FilterList(TypeSelect(), SeriesSort(Filter.Sort.Selection(sort, false)))
        val source = mockk<KomgaSource>(relaxed = true)
        every { source.id } returns 42
        every { source.getFilterList() } answers { filters.snapshotKomgaFilters() }
        every { source.snapshotFilters(any()) } answers { firstArg<FilterList>().snapshotKomgaFilters() }
        every {
            source.buildFilterListForLibrary(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            filters.snapshotKomgaFilters()
        }
        val manager = object : SourceManager by mockk<SourceManager>() {
            override fun getOrStub(sourceKey: Long) = source
            override val catalogueSources = MutableStateFlow(listOf(source))
        }
        val sourcePreferences = mockk<SourcePreferences>()
        every { sourcePreferences.sourceDisplayMode } returns
            preference<LibraryDisplayMode>(LibraryDisplayMode.CompactGrid)
        val basePreferences = mockk<BasePreferences>()
        every { basePreferences.downloadedOnly } returns preference(false)
        val libraryPreferences = mockk<LibraryPreferences>()
        every { libraryPreferences.showLibraryReadProgress } returns preference(false)
        val classification = mockk<KomgaLibraryClassificationManager>()
        every { classification.getLibraries(42) } returns emptyList()
        every { classification.classificationsChanges(42) } returns emptyFlow()
        val incognito = mockk<GetIncognitoState>()
        every { incognito.await(42) } returns true
        try {
            val model = ScreenModelStore.getOrPut(key, null) {
                KomgaLibraryScreenModel(
                    sourceId = 42,
                    listingQuery = null,
                    sourceManager = manager,
                    sourcePreferences = sourcePreferences,
                    basePreferences = basePreferences,
                    libraryPreferences = libraryPreferences,
                    downloadManager = mockk(),
                    getRemoteManga = mockk(),
                    getManga = mockk(),
                    updateManga = mockk(),
                    getChaptersByMangaId = mockk(),
                    epubCacheManager = mockk(),
                    getIncognitoState = incognito,
                    libraryScope = KomgaLibraryScope.ALL,
                    libraryClassificationManager = classification,
                    trackerManager = mockk(),
                )
            }
            test(model, source)
        } finally {
            ScreenModelStore.onDisposeNavigator(key)
            Dispatchers.resetMain()
        }
    }

    private fun <T> preference(value: T): Preference<T> = mockk<Preference<T>>().also {
        every { it.get() } returns value
        every { it.changes() } returns emptyFlow()
    }
}
