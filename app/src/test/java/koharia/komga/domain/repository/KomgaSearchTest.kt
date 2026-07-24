package koharia.komga.domain.repository

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaSearchTest {

    @Test
    fun `search query removes delimiters but keeps their content`() {
        assertEquals("Title Edition Extra Full", normalizeSearchQuery(" Title(Edition)【Extra】（Full） "))
        assertEquals("Title Edition", normalizeSearchQuery("Title( Edition"))
        assertEquals("Title Edition", normalizeSearchQuery("Title   Edition"))
    }

    @Test
    fun `search query made only of delimiters becomes blank`() {
        assertEquals("", normalizeSearchQuery("（【()】）"))
    }

    @Test
    fun `search query without delimiters remains unchanged`() {
        assertEquals("A normal title", normalizeSearchQuery("A normal title"))
    }

    @Test
    fun `combined pages interleave books first and retain remaining items`() {
        val books = MangasPage(
            listOf(manga("book-1"), manga("book-2"), manga("book-3")),
            hasNextPage = false,
        )
        val series = MangasPage(
            listOf(manga("series-1"), manga("series-2")),
            hasNextPage = true,
        )

        val merged = mergeSearchPages(books, series)

        assertEquals(
            listOf("book-1", "series-1", "book-2", "series-2", "book-3"),
            merged.mangas.map { it.title },
        )
        assertTrue(merged.hasNextPage)
    }

    @Test
    fun `combined pages stop when both sources stop`() {
        val merged = mergeSearchPages(
            MangasPage(emptyList(), false),
            MangasPage(listOf(manga("series")), false),
        )

        assertEquals(listOf("series"), merged.mangas.map { it.title })
        assertFalse(merged.hasNextPage)
    }

    private fun manga(title: String) = SManga.create().apply {
        this.title = title
        url = "/$title"
    }
}
