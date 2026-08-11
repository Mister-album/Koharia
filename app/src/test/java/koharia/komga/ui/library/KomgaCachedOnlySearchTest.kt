package koharia.komga.ui.library

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import koharia.core.common.extensions.EMPTY
import koharia.komga.api.dto.KOMGA_LIBRARY_ID_MEMO_KEY
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TYPE_READ_LISTS_INDEX
import koharia.source.komga.TYPE_SERIES_INDEX
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class KomgaCachedOnlySearchTest {

    @Test
    fun `cached search matches local metadata without case sensitivity`() {
        val manga = manga(
            title = "The Sandman",
            author = "Neil Gaiman",
            genres = listOf("Fantasy"),
        )

        assertTrue(manga.matchesCachedOnlyQuery("sand"))
        assertTrue(manga.matchesCachedOnlyQuery("GAIMAN"))
        assertTrue(manga.matchesCachedOnlyQuery("fantasy"))
        assertFalse(manga.matchesCachedOnlyQuery("science"))
    }

    @Test
    fun `blank cached search includes every local item`() {
        assertTrue(manga(title = "Anything").matchesCachedOnlyQuery(null))
        assertTrue(manga(title = "Anything").matchesCachedOnlyQuery("  "))
    }

    @Test
    fun `cached paging is complete in every direction`() {
        assertTrue(CACHED_ONLY_LOAD_STATES.refresh.endOfPaginationReached)
        assertTrue(CACHED_ONLY_LOAD_STATES.prepend.endOfPaginationReached)
        assertTrue(CACHED_ONLY_LOAD_STATES.append.endOfPaginationReached)
    }

    @Test
    fun `cached library filter keeps items in their assigned shelf`() {
        val comic = manga(title = "Comic", libraryId = "comic-library")
        val book = manga(title = "Book", libraryId = "book-library")

        assertTrue(comic.matchesCachedLibraryFilter(setOf("comic-library")))
        assertFalse(comic.matchesCachedLibraryFilter(setOf("book-library")))
        assertTrue(book.matchesCachedLibraryFilter(setOf("book-library")))
        assertTrue(book.matchesCachedLibraryFilter(emptySet()))
    }

    @Test
    fun `cached library filter excludes legacy items with unknown shelf`() {
        assertFalse(manga(title = "Unknown").matchesCachedLibraryFilter(setOf("comic-library")))
    }

    @Test
    fun `cached content type follows Komga URL kind`() {
        val series = manga(title = "Series", url = "https://komga.test/api/v1/series/1")
        val readList = manga(title = "Read list", url = "https://komga.test/api/v1/readlists/2")
        val book = manga(title = "Book", url = "https://komga.test/api/v1/books/3")

        assertTrue(series.matchesCachedContentType(TYPE_SERIES_INDEX))
        assertFalse(series.matchesCachedContentType(TYPE_BOOKS_INDEX))
        assertTrue(readList.matchesCachedContentType(TYPE_READ_LISTS_INDEX))
        assertTrue(book.matchesCachedContentType(TYPE_BOOKS_INDEX))
        assertTrue(series.matchesCachedContentType(TYPE_ALL_INDEX))
        assertTrue(readList.matchesCachedContentType(TYPE_ALL_INDEX))
        assertTrue(book.matchesCachedContentType(TYPE_ALL_INDEX))
    }

    private fun manga(
        title: String,
        author: String? = null,
        genres: List<String>? = null,
        libraryId: String? = null,
        url: String = "https://komga.test/api/v1/series/1",
    ) = Manga(
        id = 1L,
        source = 1L,
        favorite = false,
        lastUpdate = 0L,
        nextUpdate = 0L,
        fetchInterval = 0,
        dateAdded = 0L,
        viewerFlags = 0L,
        chapterFlags = 0L,
        coverLastModified = 0L,
        url = url,
        title = title,
        artist = null,
        author = author,
        description = null,
        genre = genres,
        status = 0L,
        thumbnailUrl = null,
        updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        initialized = true,
        lastModifiedAt = 0L,
        favoriteModifiedAt = null,
        version = 0L,
        notes = "",
        memo = libraryId?.let {
            buildJsonObject { put(KOMGA_LIBRARY_ID_MEMO_KEY, it) }
        } ?: JsonObject.EMPTY,
    )
}
