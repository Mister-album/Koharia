package koharia.source.local

import koharia.connection.LibraryContentScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.manga.model.Manga

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

        assertEquals(
            filters.copy(sort = 0, descending = false),
            filters.toFilterList(LibraryContentScope.BOOK).localLibraryFilters(),
        )
    }

    @Test
    fun `active state follows any configured field`() {
        assertFalse(LocalLibraryFilters().isActive)
        assertTrue(LocalLibraryFilters(author = "Author").isActive)
    }

    @Test
    fun `folders first alone activates the shelf filter indicator`() {
        val filters = LocalLibraryFilters(foldersFirst = true)

        assertTrue(filters.isActive)
        assertFalse(filters.copy(foldersFirst = false).isActive)
    }

    @Test
    fun `saved filters retain new conditions and accept old snapshots`() {
        val filters = LocalLibraryFilters(
            unread = TriState.ENABLED_IS,
            bookmarked = TriState.ENABLED_NOT,
            downloaded = TriState.ENABLED_IS,
            excludedScanlators = setOf("Group A"),
        )
        assertEquals(filters, Json.decodeFromString<LocalLibraryFilters>(Json.encodeToString(filters)))
        assertEquals(
            LocalLibraryFilters(author = "Author"),
            Json.decodeFromString<LocalLibraryFilters>("""{"author":"Author"}"""),
        )
    }

    @Test
    fun `chapter sorting preserves its direction when changing the sort field`() {
        val filters = LocalLibraryFilters(sort = 0, descending = true)
        val changed = filters.selectSort(localSortIndex(Manga.CHAPTER_SORTING_NUMBER))
        assertEquals(Manga.CHAPTER_SORTING_NUMBER, changed.chapterSortingMode)
        assertTrue(changed.descending)
        assertFalse(changed.selectSort(changed.sort).descending)
        assertFalse(filters.selectSort(3, preserveDirection = false).descending)
    }
}
