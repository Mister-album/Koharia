package koharia.source.komga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KomgaFilterStateTest {

    @Test
    fun `legacy default series selection migrates to all once`() {
        val legacy = PersistentFilterState(
            selects = mapOf("Search for" to TYPE_SERIES_INDEX),
            sorts = mapOf("Sort" to PersistentSortState(index = 1, ascending = true)),
        )

        val migrated = legacy.migrateSearchType()

        assertEquals(TYPE_ALL_INDEX, migrated.selects["Search for"])
        assertEquals(0, migrated.sorts["Sort"]?.index)
        assertEquals(migrated, migrated.migrateSearchType())
    }

    @Test
    fun `legacy explicit book selection remains unchanged`() {
        val legacy = PersistentFilterState(
            selects = mapOf("Search for" to TYPE_BOOKS_INDEX),
            sorts = mapOf("Sort" to PersistentSortState(index = 2, ascending = false)),
        )

        val migrated = legacy.migrateSearchType()

        assertEquals(TYPE_BOOKS_INDEX, migrated.selects["Search for"])
        assertEquals(PersistentSortState(index = 2, ascending = false), migrated.sorts["Sort"])
    }
}
