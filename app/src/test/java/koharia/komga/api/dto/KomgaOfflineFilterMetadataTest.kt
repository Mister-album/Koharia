package koharia.komga.api.dto

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaOfflineFilterMetadataTest {

    @Test
    fun `series details keep fields needed by cached filters`() {
        val series = SeriesDto(
            id = "series-id",
            libraryId = "library-id",
            name = "Series",
            created = "2026-01-01T00:00:00Z",
            lastModified = "2026-02-01T00:00:00Z",
            fileLastModified = "2026-02-01T00:00:00Z",
            booksCount = 1,
            metadata = SeriesMetadataDto(
                status = "ENDED",
                title = "Displayed title",
                titleSort = "Sortable title",
                publisher = "Publisher",
                genres = setOf("Fantasy"),
                tags = setOf("Award winner"),
            ),
            booksMetadata = BookMetadataAggregationDto(
                authors = listOf(AuthorDto("Writer", "writer"), AuthorDto("Artist", "penciller")),
                tags = setOf("Book tag"),
            ),
        )

        val metadata = requireNotNull(series.toSManga("https://komga.test").memo.offlineFilterMetadata())

        assertEquals("ENDED", metadata.status)
        assertEquals(setOf("Fantasy"), metadata.genres)
        assertEquals(setOf("Award winner", "Book tag"), metadata.tags)
        assertEquals("Publisher", metadata.publisher)
        assertEquals(
            setOf(KomgaOfflineAuthor("Writer", "writer"), KomgaOfflineAuthor("Artist", "penciller")),
            metadata.authors,
        )
        assertEquals("Sortable title", metadata.titleSort)
        assertEquals("2026-01-01T00:00:00Z", metadata.createdDate)
        assertEquals("2026-02-01T00:00:00Z", metadata.lastModifiedDate)
        assertTrue(metadata.oneShot == true)
    }

    @Test
    fun `book and read list details keep their supported cached sort metadata`() {
        val book = BookDto(
            id = "book-id",
            name = "book.epub",
            created = "2026-03-01T00:00:00Z",
            lastModified = "2026-04-01T00:00:00Z",
            fileLastModified = "2026-04-01T00:00:00Z",
            metadata = BookMetadataDto(
                title = "Book title",
                tags = setOf("Novel"),
                authors = listOf(AuthorDto("Author", "writer")),
            ),
        ).toSManga("https://komga.test").memo.offlineFilterMetadata()
        val readList = ReadListDto(
            id = "list-id",
            name = "Reading order",
            createdDate = "2026-05-01T00:00:00Z",
            lastModifiedDate = "2026-06-01T00:00:00Z",
        ).toSManga("https://komga.test").memo.offlineFilterMetadata()

        assertEquals(setOf("Novel"), book?.tags)
        assertEquals(setOf(KomgaOfflineAuthor("Author", "writer")), book?.authors)
        assertEquals("Book title", book?.titleSort)
        assertEquals("Reading order", readList?.titleSort)
        assertEquals("2026-05-01T00:00:00Z", readList?.createdDate)
    }

    @Test
    fun `offline detail metadata merges without dropping durable library membership`() {
        val localMemo = buildJsonObject {
            put(KOMGA_LIBRARY_IDS_MEMO_KEY, JsonArray(listOf(JsonPrimitive("library-a"), JsonPrimitive("library-b"))))
        }
        val remoteMemo = buildJsonObject {}
            .withOfflineFilterMetadata(KomgaOfflineFilterMetadata(titleSort = "Title"))

        val merged = requireNotNull(mergeKomgaOfflineMemo(localMemo, remoteMemo))

        assertTrue(KOMGA_LIBRARY_IDS_MEMO_KEY in merged)
        assertEquals("Title", merged.offlineFilterMetadata()?.titleSort)
        assertFalse(merged.isEmpty())
    }
}
