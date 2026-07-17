package koharia.komga.download

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaChapterMemoTest {

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

        assertNotEquals(first, second)
        assertTrue(first.startsWith(imageUrl))
        assertEquals(imageUrl, KomgaChapterMemo.networkPageImageUrl(first))
        assertEquals(first, KomgaChapterMemo.versionedPageImageUrl(first, firstMemo))
    }
}
