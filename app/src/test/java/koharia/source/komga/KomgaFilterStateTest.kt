package koharia.source.komga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaFilterStateTest {
    @Test
    fun `snapshot preserves offline options and isolates nested mutable selections`() {
        val original = eu.kanade.tachiyomi.source.model.FilterList(
            TypeSelect(),
            SeriesSort(eu.kanade.tachiyomi.source.model.Filter.Sort.Selection(3, false)),
            LibraryFilter(listOf(koharia.komga.api.dto.LibraryDto("library", "Offline library")), setOf("library")),
            UriMultiSelectFilter("Tags", listOf(UriMultiSelectOption("Offline tag").apply { state = true })),
            ReadingStateGroup(),
        )
        val copy = original.snapshotKomgaFilters()
        copy.filterIsInstance<LibraryFilter>().single().state.single().state = false
        copy.filterIsInstance<UriMultiSelectFilter>().first { it.name == "Tags" }.state.single().state = false
        copy.filterIsInstance<SeriesSort>().single().state =
            eu.kanade.tachiyomi.source.model.Filter.Sort.Selection(2, true)
        copy.filterIsInstance<ReadingStateGroup>().single().state.first().state = true
        assertTrue(original.filterIsInstance<LibraryFilter>().single().state.single().state)
        assertTrue(original.filterIsInstance<UriMultiSelectFilter>().first { it.name == "Tags" }.state.single().state)
        assertEquals(3, original.filterIsInstance<SeriesSort>().single().state?.index)
        assertEquals(false, original.filterIsInstance<ReadingStateGroup>().single().state.first().state)
    }

    @Test
    fun `legacy series selection and sort remain unchanged after version migration`() {
        val legacy = PersistentFilterState(
            selects = mapOf("Search for" to TYPE_SERIES_INDEX),
            sorts = mapOf("Sort" to PersistentSortState(index = 1, ascending = true)),
        )

        val migrated = legacy.migratePersistentFilterState()

        assertEquals(TYPE_SERIES_INDEX, migrated.selects["Search for"])
        assertEquals(PersistentSortState(index = 1, ascending = true), migrated.sorts["Sort"])
        assertTrue(migrated.version > legacy.version)
        assertEquals(migrated, migrated.migratePersistentFilterState())
    }

    @Test
    fun `legacy explicit book selection remains unchanged`() {
        val legacy = PersistentFilterState(
            selects = mapOf("Search for" to TYPE_BOOKS_INDEX),
            sorts = mapOf("Sort" to PersistentSortState(index = 2, ascending = false)),
        )

        val migrated = legacy.migratePersistentFilterState()

        assertEquals(TYPE_BOOKS_INDEX, migrated.selects["Search for"])
        assertEquals(PersistentSortState(index = 2, ascending = false), migrated.sorts["Sort"])
    }
}
