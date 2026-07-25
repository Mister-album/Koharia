package koharia.source.komga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaFilterStateTest {

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
