package koharia.komga.download

import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.BookMetadataDto
import koharia.komga.api.dto.MediaDto
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaChapterMemoTest {

    @Test
    fun `book memo keeps epub page compatibility classification`() {
        val book = BookDto(
            id = "book-id",
            name = "comic.epub",
            fileLastModified = "2026-07-22T00:00:00Z",
            media = MediaDto(
                mediaType = "application/epub+zip",
                mediaProfile = "EPUB",
                epubDivinaCompatible = true,
                pagesCount = 42,
            ),
            metadata = BookMetadataDto(title = "Image EPUB"),
        )

        val memo = KomgaChapterMemo.buildMemo("https://komga.test", book)

        assertEquals(true, KomgaChapterMemo.isEpub(memo))
        assertEquals(true, KomgaChapterMemo.isEpubDivinaCompatible(memo))
        assertEquals(42, KomgaChapterMemo.pagesCount(memo))
    }

    @Test
    fun `legacy memo without page compatibility remains unknown`() {
        val memo = buildJsonObject {
            put(KomgaChapterMemo.IS_EPUB, true)
            put(KomgaChapterMemo.PAGES_COUNT, 42)
        }

        assertEquals(null, KomgaChapterMemo.isEpubDivinaCompatible(memo))
    }

    @Test
    fun `publication version prefers file hash`() {
        val memo = buildJsonObject {
            put(KomgaChapterMemo.FILE_HASH, "hash-v2")
            put(KomgaChapterMemo.FILE_LAST_MODIFIED, "2026-07-17T00:00:00Z")
            put(KomgaChapterMemo.SIZE_BYTES, 128L)
        }

        assertEquals("hash:hash-v2", KomgaChapterMemo.publicationVersion(memo))
    }

    @Test
    fun `page image cache key changes with publication and network url stays clean`() {
        val firstMemo = buildJsonObject { put(KomgaChapterMemo.FILE_HASH, "hash-v1") }
        val secondMemo = buildJsonObject { put(KomgaChapterMemo.FILE_HASH, "hash-v2") }
        val imageUrl = "https://komga.test/api/v1/books/book/pages/1?convert=png"

        val first = KomgaChapterMemo.versionedPageImageUrl(imageUrl, firstMemo)
        val second = KomgaChapterMemo.versionedPageImageUrl(imageUrl, secondMemo)
        val firstToken = KomgaChapterMemo.pageImageCacheToken(firstMemo)

        assertNotEquals(first, second)
        assertEquals(first, KomgaChapterMemo.versionedPageImageUrl(imageUrl, firstToken))
        assertTrue(first.startsWith(imageUrl))
        assertEquals(imageUrl, KomgaChapterMemo.networkPageImageUrl(first))
        assertEquals(first, KomgaChapterMemo.versionedPageImageUrl(first, firstMemo))
    }
}
