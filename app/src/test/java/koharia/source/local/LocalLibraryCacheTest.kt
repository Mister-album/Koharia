package koharia.source.local

import koharia.connection.ConnectionChapterMetadata
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class LocalLibraryCacheTest {
    @Test
    fun `index snapshots are reused and updated including empty and removed caches`() {
        val cache = LocalLibraryIndexCache(Json)
        val item = LocalLibraryItem(
            "a",
            "root",
            "series/1.cbz",
            kind = LocalLibraryItem.Kind.CHAPTER,
            format = "cbz",
            sizeBytes = 12,
            modifiedAt = 2,
        )
        val encoded = Json.encodeToString(LocalLibraryIndex(items = listOf(item)))
        val first = cache.get(encoded)
        assertSame(first, cache.get(encoded))
        assertEquals(item, first.itemsByKey["a"])
        assertTrue(first.libraryItemsByKey.isEmpty())
        assertEquals(listOf("1"), first.chapterNamesBySeriesKey[LocalLibraryLocator.itemKey("root", "series")])
        val empty = cache.get(Json.encodeToString(LocalLibraryIndex(scannedAt = 3)))
        assertNotSame(first, empty)
        assertTrue(empty.itemsByKey.isEmpty())
        assertEquals(0L, cache.get(null).scannedAt)
        assertTrue(cache.get("invalid json").items.isEmpty())
        assertEquals(first, cache.get(encoded))
    }

    @Test
    fun `chapter page count survives refresh but not replacement or directory changes`() {
        val memo = localChapterMemo(null, 10, 100, "v1")
        val chapter = Chapter.create().copy(
            dateUpload = 10,
            lastPageRead = 4,
            memo = ConnectionChapterMetadata.withPagesCount(memo, 20),
        )
        assertEquals(20, ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 10, 100, "v1")))
        assertNull(ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 11, 100, "v1")))
        assertNull(ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 10, 101, "v1")))
        assertNull(ConnectionChapterMetadata.pagesCount(localChapterMemo(chapter, 10, 100, "v2")))
        val legacy = chapter.copy(memo = ConnectionChapterMetadata.withPagesCount(JsonObject(emptyMap()), 20))
        assertEquals(20, ConnectionChapterMetadata.pagesCount(localChapterMemo(legacy, 10, 100, "v1")))
    }

    @Test
    fun `shelf updates retain item states but reflect membership and order changes`() {
        val cache = LocalMangaStates()
        val a = Manga.create().copy(id = 1, title = "A")
        val b = Manga.create().copy(id = 2, title = "B")
        val first = cache.update(listOf(a, b))
        val updated = cache.update(listOf(a.copy(title = "new"), b))
        assertEquals(first, updated)
        assertEquals("new", first[0].value.title)
        assertEquals(listOf(first[1], first[0]), cache.update(listOf(b, a)))
        assertEquals(listOf(first[1]), cache.update(listOf(b)))
        assertTrue(cache.update(emptyList()).isEmpty())
    }
}
