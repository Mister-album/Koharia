package koharia.epub

import eu.kanade.tachiyomi.util.storage.DiskUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubImageFileNameTest {

    @Test
    fun `image name includes series book and original resource name`() {
        val firstBook = buildEpubImageBaseName(
            seriesTitle = "Series",
            bookTitle = "Book One",
            originalFileName = "CoverDesign",
            extension = "jpg",
        )
        val secondBook = buildEpubImageBaseName(
            seriesTitle = "Series",
            bookTitle = "Book Two",
            originalFileName = "CoverDesign",
            extension = "jpg",
        )

        assertEquals("Series - Book One - CoverDesign", firstBook)
        assertEquals("Series - Book Two - CoverDesign", secondBook)
        assertNotEquals(firstBook, secondBook)
    }

    @Test
    fun `long titles preserve original resource name and extension budget`() {
        val baseName = buildEpubImageBaseName(
            seriesTitle = "系列".repeat(100),
            bookTitle = "书名".repeat(100),
            originalFileName = "CoverDesign",
            extension = "webp",
        )

        assertTrue(baseName.contains("书名"))
        assertTrue(baseName.endsWith(" - CoverDesign"))
        assertTrue("$baseName.webp".toByteArray().size <= DiskUtil.MAX_FILE_NAME_BYTES)
    }

    @Test
    fun `long original resource names reserve space for distinct book titles`() {
        val originalFileName = "very-long-resource-name-".repeat(20)
        val firstBook = buildEpubImageBaseName(
            seriesTitle = "Series",
            bookTitle = "Book One",
            originalFileName = originalFileName,
            extension = "jpg",
        )
        val secondBook = buildEpubImageBaseName(
            seriesTitle = "Series",
            bookTitle = "Book Two",
            originalFileName = originalFileName,
            extension = "jpg",
        )

        assertTrue(firstBook.startsWith("Series - Book One - very-long-resource-name-"))
        assertTrue(secondBook.startsWith("Series - Book Two - very-long-resource-name-"))
        assertNotEquals(firstBook, secondBook)
        assertTrue("$firstBook.jpg".toByteArray().size <= DiskUtil.MAX_FILE_NAME_BYTES)
        assertTrue("$secondBook.jpg".toByteArray().size <= DiskUtil.MAX_FILE_NAME_BYTES)
    }

    @Test
    fun `blank components fall back to a stable image name`() {
        assertEquals(
            "Series - image",
            buildEpubImageBaseName(
                seriesTitle = "Series",
                bookTitle = " ",
                originalFileName = "...",
                extension = ".png",
            ),
        )
    }

    @Test
    fun `invalid filename characters are sanitized per component`() {
        assertEquals(
            "Series_One - Book_ Two - cover_",
            buildEpubImageBaseName(
                seriesTitle = "Series/One",
                bookTitle = "Book: Two",
                originalFileName = "cover?",
                extension = "jpg",
            ),
        )
    }
}
