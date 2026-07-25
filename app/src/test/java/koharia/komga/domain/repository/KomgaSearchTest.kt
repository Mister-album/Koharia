package koharia.komga.domain.repository

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import koharia.komga.api.KomgaApiClient
import koharia.komga.api.KomgaSearchCapabilities
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TypeSelect
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaSearchTest {

    @Test
    fun `search query removes delimiters but keeps their content`() {
        assertEquals("Title Edition Extra Full English", normalizeSearchQuery(" Title(Edition)【Extra】（Full）[English] "))
        assertEquals("Title Edition", normalizeSearchQuery("Title( Edition"))
        assertEquals("Title Scan", normalizeSearchQuery("Title[Scan"))
        assertEquals("Title Edition", normalizeSearchQuery("Title   Edition"))
    }

    @Test
    fun `search query made only of delimiters becomes blank`() {
        assertEquals("", normalizeSearchQuery("（【([])】）"))
    }

    @Test
    fun `search query without delimiters remains unchanged`() {
        assertEquals("A normal title", normalizeSearchQuery("A normal title"))
    }

    @Test
    fun `advanced lucene queries keep grouping delimiters`() {
        assertEquals("writer:(sean murphy)", normalizeSearchQuery(" writer:(sean murphy) "))
        assertEquals("(batman OR robin)", normalizeSearchQuery("(batman OR robin)"))
        assertEquals("release_date:[2020 TO 2024]", normalizeSearchQuery("release_date:[2020 TO 2024]"))
    }

    @Test
    fun `legacy request path maps all search to series`() {
        val apiClient = KomgaApiClient(
            baseUrl = "https://komga.test",
            headers = Headers.Builder().build(),
            client = OkHttpClient(),
            json = Json,
            searchCapabilities = KomgaSearchCapabilities(),
        )
        val repository = KomgaRepository("https://komga.test", apiClient)

        val filters = FilterList(TypeSelect().apply { state = TYPE_ALL_INDEX })
        val request = repository.searchMangaRequest(1, "title", filters, emptySet())

        assertEquals("/api/v1/series", request.url.encodedPath)
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
