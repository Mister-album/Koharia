package koharia.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuwayomiBrowseFiltersTest {
    private fun manga(id: Int, title: String, inLibrary: Boolean = false) =
        SuwayomiManga(id = id, title = title, inLibrary = inLibrary)

    private val entries = listOf(
        manga(1, "Beta", inLibrary = true),
        manga(2, "alpha"),
        manga(3, "Gamma"),
    )

    @Test
    fun `hiding library entries keeps the source order of the rest`() {
        val result = applySuwayomiBrowseFilters(
            entries,
            SuwayomiBrowseFilters(hideInLibrary = true),
            SuwayomiBrowseSort.SOURCE,
        )

        assertEquals(listOf(2, 3), result.map { it.id })
    }

    @Test
    fun `title sorting is case insensitive and reversible`() {
        val ascending =
            applySuwayomiBrowseFilters(entries, SuwayomiBrowseFilters.NONE, SuwayomiBrowseSort.TITLE_ASCENDING)
        val descending =
            applySuwayomiBrowseFilters(entries, SuwayomiBrowseFilters.NONE, SuwayomiBrowseSort.TITLE_DESCENDING)

        assertEquals(listOf("alpha", "Beta", "Gamma"), ascending.map { it.title })
        assertEquals(listOf("Gamma", "Beta", "alpha"), descending.map { it.title })
    }

    @Test
    fun `narrowing and sorting combine`() {
        val result = applySuwayomiBrowseFilters(
            entries,
            SuwayomiBrowseFilters(hideInLibrary = true),
            SuwayomiBrowseSort.TITLE_DESCENDING,
        )

        assertEquals(listOf(3, 2), result.map { it.id })
    }

    @Test
    fun `filters are reported as inactive by default`() {
        assertFalse(SuwayomiBrowseFilters.NONE.active)
        assertTrue(SuwayomiBrowseFilters(hideInLibrary = true).active)
    }

    @Test
    fun `the chip counts the client-side narrowing and a non-default sort`() {
        assertEquals(0, activeSuwayomiBrowseFilterCount(SuwayomiBrowseFilters.NONE, SuwayomiBrowseSort.SOURCE))
        assertEquals(
            1,
            activeSuwayomiBrowseFilterCount(SuwayomiBrowseFilters(hideInLibrary = true), SuwayomiBrowseSort.SOURCE),
        )
        assertEquals(
            1,
            activeSuwayomiBrowseFilterCount(SuwayomiBrowseFilters.NONE, SuwayomiBrowseSort.TITLE_ASCENDING),
        )
        assertEquals(
            2,
            activeSuwayomiBrowseFilterCount(
                SuwayomiBrowseFilters(hideInLibrary = true),
                SuwayomiBrowseSort.TITLE_DESCENDING,
            ),
        )
    }

    @Test
    fun `source filters only reach the server in search mode`() {
        assertTrue(suwayomiFiltersRequireSearch(SuwayomiSourceMangaType.POPULAR))
        assertTrue(suwayomiFiltersRequireSearch(SuwayomiSourceMangaType.LATEST))
        assertFalse(suwayomiFiltersRequireSearch(SuwayomiSourceMangaType.SEARCH))
    }

    @Test
    fun `active filter count only counts filters moved from their default`() {
        val select = SuwayomiSelectFilter(name = "Genre", values = listOf("Any", "Action"), default = 0)
        val sort = SuwayomiSortFilter(
            name = "Order",
            values = listOf("Title", "Added"),
            default = SuwayomiSortSelection(index = 0, ascending = true),
        )
        val group = SuwayomiGroupFilter(
            name = "More",
            filters = listOf(SuwayomiCheckBoxFilter(name = "Completed", default = false)),
        )

        assertEquals(0, activeSuwayomiFilterCount(SuwayomiFilterState.of(listOf(select, sort, group))))

        val changed = SuwayomiFilterState.of(listOf(select, sort, group)).toMutableList()
        changed[0] = SelectState(select, index = 1)
        changed[2] = GroupState(
            group,
            listOf(CheckBoxState(SuwayomiCheckBoxFilter("Completed", default = false), checked = true)),
        )

        // The group's nested change counts once, next to the changed select.
        assertEquals(2, activeSuwayomiFilterCount(changed))
    }
}
