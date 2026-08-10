package koharia.komga.ui.library

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import koharia.core.common.extensions.EMPTY
import koharia.komga.api.dto.KOMGA_LIBRARY_ID_MEMO_KEY
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

    private fun manga(
        title: String,
        author: String? = null,
        genres: List<String>? = null,
        libraryId: String? = null,
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
        url = "https://komga.test/api/v1/series/1",
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
