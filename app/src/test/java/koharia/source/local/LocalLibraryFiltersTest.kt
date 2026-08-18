package koharia.source.local

import koharia.connection.LibraryContentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLibraryFiltersTest {

    @Test
    fun `text filters round trip through source filter list`() {
        val filters = LocalLibraryFilters(
            series = "Series",
            chapter = "Chapter 1",
            author = "Author",
            artist = "Artist",
            genre = "Genre",
            format = "epub",
        )

        assertEquals(filters, filters.toFilterList(LibraryContentScope.BOOK).localLibraryFilters())
    }

    @Test
    fun `active state follows any configured field`() {
        assertFalse(LocalLibraryFilters().isActive)
        assertTrue(LocalLibraryFilters(author = "Author").isActive)
    }
}
