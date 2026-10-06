package koharia.suwayomi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SuwayomiShelfVisibilityTest {
    private val first = SuwayomiCategory(1, "First")
    private val second = SuwayomiCategory(2, "Second")
    private val shelf = SuwayomiShelf(
        listOf(
            SuwayomiManga(1, "First", categories = SuwayomiNodes(listOf(first))),
            SuwayomiManga(2, "Both", categories = SuwayomiNodes(listOf(first, second))),
            SuwayomiManga(3, "Second", categories = SuwayomiNodes(listOf(second))),
            SuwayomiManga(4, "Uncategorized"),
        ),
        listOf(first, second),
    )

    @Test fun allIncludesUncategorizedAndFutureCategories() {
        assertSame(shelf, shelf.withVisibleCategories(null))
    }

    @Test fun subsetFiltersEntriesAndTabsWithoutDuplicatingSharedEntries() {
        val visible = shelf.withVisibleCategories(setOf(2))
        assertEquals(listOf(second), visible.categories)
        assertEquals(listOf(2, 3), visible.mangas.map { it.id })
        assertEquals(4, shelf.mangas.size)
    }

    @Test fun emptyOrDeletedSelectionNeverFallsBackToAll() {
        for (ids in listOf(emptySet(), setOf(99))) {
            assertEquals(emptyList<SuwayomiManga>(), shelf.withVisibleCategories(ids).mangas)
            assertEquals(emptyList<SuwayomiCategory>(), shelf.withVisibleCategories(ids).categories)
        }
    }
}
