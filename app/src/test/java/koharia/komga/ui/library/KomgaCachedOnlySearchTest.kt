package koharia.komga.ui.library

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import koharia.core.common.extensions.EMPTY
import koharia.komga.api.dto.KOMGA_LIBRARY_IDS_MEMO_KEY
import koharia.komga.api.dto.KOMGA_LIBRARY_ID_MEMO_KEY
import koharia.komga.api.dto.KomgaOfflineAuthor
import koharia.komga.api.dto.KomgaOfflineFilterMetadata
import koharia.komga.api.dto.withOfflineFilterMetadata
import koharia.source.komga.AuthorGroup
import koharia.source.komga.OneshotFilter
import koharia.source.komga.ReadingStateGroup
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TYPE_READ_LISTS_INDEX
import koharia.source.komga.TYPE_SERIES_INDEX
import koharia.source.komga.TypeSelect
import koharia.source.komga.UriMultiSelectFilter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
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
    fun `cached read list matches any library represented by its books`() {
        val readList = manga(
            title = "Mixed read list",
            libraryIds = setOf("comic-library", "book-library"),
            url = "https://komga.test/api/v1/readlists/1",
        )

        assertTrue(readList.matchesCachedLibraryFilter(setOf("comic-library")))
        assertTrue(readList.matchesCachedLibraryFilter(setOf("book-library")))
        assertFalse(readList.matchesCachedLibraryFilter(setOf("other-library")))
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

    @Test
    fun `cached advanced filters use structured detail metadata`() {
        val manga = manga(
            title = "Series",
            offlineMetadata = KomgaOfflineFilterMetadata(
                status = "ENDED",
                genres = setOf("Fantasy"),
                tags = setOf("Award winner"),
                publisher = "Publisher",
                authors = setOf(KomgaOfflineAuthor("Writer", "writer")),
                oneShot = true,
            ),
        )

        assertTrue(
            manga.matchesCachedAdvancedFilters(
                CachedAdvancedFilterSelection(
                    statuses = setOf("ENDED"),
                    genres = setOf("Fantasy"),
                    tags = setOf("Award winner"),
                    publishers = setOf("Publisher"),
                    authors = setOf(KomgaOfflineAuthor("Writer", "writer")),
                    oneShot = true,
                ),
            ),
        )
        assertFalse(
            manga.matchesCachedAdvancedFilters(
                CachedAdvancedFilterSelection(tags = setOf("Missing")),
            ),
        )
    }

    @Test
    fun `cached reading status follows local chapters`() {
        val manga = manga(title = "Series")

        assertTrue(
            manga.matchesCachedAdvancedFilters(
                CachedAdvancedFilterSelection(readingStatuses = setOf("UNREAD")),
                chapters = listOf(chapter(read = false)),
            ),
        )
        assertTrue(
            manga.matchesCachedAdvancedFilters(
                CachedAdvancedFilterSelection(readingStatuses = setOf("IN_PROGRESS")),
                chapters = listOf(chapter(read = true), chapter(read = false)),
            ),
        )
        assertTrue(
            manga.matchesCachedAdvancedFilters(
                CachedAdvancedFilterSelection(readingStatuses = setOf("READ")),
                chapters = listOf(chapter(read = true)),
            ),
        )
    }

    @Test
    fun `cached sort uses detail title and timestamps`() {
        val laterTitle = manga(
            id = 1L,
            title = "Displayed A",
            offlineMetadata = KomgaOfflineFilterMetadata(
                titleSort = "Zulu",
                createdDate = "2026-02-01T00:00:00Z",
            ),
        )
        val earlierTitle = manga(
            id = 2L,
            title = "Displayed Z",
            offlineMetadata = KomgaOfflineFilterMetadata(
                titleSort = "Alpha",
                createdDate = "2026-01-01T00:00:00Z",
            ),
        )

        assertEquals(
            listOf(earlierTitle, laterTitle),
            listOf(laterTitle, earlierTitle).sortedForCachedFilters(
                CachedAdvancedFilterSelection(sortIndex = 1),
                randomSeed = 0,
            ),
        )
        assertEquals(
            listOf(earlierTitle, laterTitle),
            listOf(laterTitle, earlierTitle).sortedForCachedFilters(
                CachedAdvancedFilterSelection(sortIndex = 2),
                randomSeed = 0,
            ),
        )
    }

    @Test
    fun `cached filter options are derived from persisted detail metadata`() {
        val cachedManga = manga(
            title = "Series",
            offlineMetadata = KomgaOfflineFilterMetadata(
                tags = setOf("Offline tag"),
                authors = setOf(KomgaOfflineAuthor("Offline writer", "writer")),
            ),
        )
        val filters = FilterList(
            UriMultiSelectFilter("Tags", emptyList()),
            SeriesSort(),
        ).withCachedMetadataOptions(listOf(cachedManga))

        assertEquals(
            listOf("Offline tag"),
            filters.filterIsInstance<UriMultiSelectFilter>().single().state.map { it.id },
        )
        assertEquals(
            listOf("Offline writer"),
            filters.filterIsInstance<AuthorGroup>().single().state.map { it.author.name },
        )
    }

    @Test
    fun `cached one shot filter only applies to series`() {
        val type = TypeSelect().apply { state = TYPE_BOOKS_INDEX }
        val reading = ReadingStateGroup().apply {
            state.filterIsInstance<OneshotFilter>().single().state = true
        }

        val selection = FilterList(type, reading).cachedAdvancedFilterSelection()

        assertFalse(selection.oneShot)
    }

    private fun manga(
        id: Long = 1L,
        title: String,
        author: String? = null,
        genres: List<String>? = null,
        libraryId: String? = null,
        libraryIds: Set<String> = emptySet(),
        offlineMetadata: KomgaOfflineFilterMetadata? = null,
        url: String = "https://komga.test/api/v1/series/1",
    ) = Manga(
        id = id,
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
        memo = buildJsonObject {
            libraryId?.let { put(KOMGA_LIBRARY_ID_MEMO_KEY, it) }
            if (libraryIds.isNotEmpty()) {
                put(KOMGA_LIBRARY_IDS_MEMO_KEY, JsonArray(libraryIds.map(::JsonPrimitive)))
            }
        }.let { memo ->
            offlineMetadata?.let { memo.withOfflineFilterMetadata(it) } ?: memo
        }.takeUnless { it.isEmpty() } ?: JsonObject.EMPTY,
    )

    private fun chapter(read: Boolean) = Chapter.create().copy(read = read)
}
