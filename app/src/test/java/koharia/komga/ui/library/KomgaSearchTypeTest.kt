package koharia.komga.ui.library

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import koharia.source.komga.CollectionFilterEntry
import koharia.source.komga.CollectionSelect
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_SERIES_INDEX
import koharia.source.komga.TypeSelect
import koharia.source.komga.UriMultiSelectFilter
import koharia.source.komga.UriMultiSelectOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaSearchTypeTest {

    @Test
    fun `all sort indices map to original choices and normalize only unsupported selections`() {
        assertEquals(listOf(0, 2, 3), availableKomgaSortIndices(TYPE_ALL_INDEX))
        for (index in 0..4) {
            val sort = SeriesSort(Filter.Sort.Selection(index, false))
            val filters = FilterList(TypeSelect(), sort)
            filters.selectContentType(TYPE_ALL_INDEX)
            assertEquals(if (index in listOf(0, 2, 3)) index else 0, sort.state?.index)
            assertEquals(index !in listOf(0, 2, 3), sort.state?.ascending)
        }
    }

    @Test
    fun `browse type defaults to series`() {
        assertEquals(TYPE_SERIES_INDEX, TypeSelect().state)
    }

    @Test
    fun `toolbar search scope defaults to all independently`() {
        val state = KomgaLibraryScreenModel.State(
            listing = KomgaLibraryScreenModel.Listing.Search(query = null, filters = FilterList()),
        )

        assertEquals(TYPE_ALL_INDEX, state.searchType)
    }

    @Test
    fun `selecting all clears unsupported series filters`() {
        val type = TypeSelect().apply { state = 0 }
        val collection = CollectionSelect(
            listOf(
                CollectionFilterEntry("None"),
                CollectionFilterEntry("Collection", "collection-id"),
            ),
        ).apply { state = 1 }
        val genre = UriMultiSelectOption("Action").apply { state = true }
        val tag = UriMultiSelectOption("Favorite").apply { state = true }
        val sort = SeriesSort(Filter.Sort.Selection(2, false))
        val filters = FilterList(
            type,
            collection,
            UriMultiSelectFilter("Genres", listOf(genre)),
            UriMultiSelectFilter("Tags", listOf(tag)),
            sort,
        )

        filters.selectContentType(TYPE_ALL_INDEX)

        assertEquals(TYPE_ALL_INDEX, type.state)
        assertEquals(0, collection.state)
        assertFalse(genre.state)
        assertTrue(tag.state)
        assertEquals(Filter.Sort.Selection(2, false), sort.state)
    }
}
