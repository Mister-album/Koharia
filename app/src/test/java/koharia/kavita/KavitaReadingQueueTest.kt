package koharia.kavita

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KavitaReadingQueueTest {
    @Test fun persistedQueuePreservesMixedMediaOrderWithoutDuplicatingChapterOwnership() {
        val comic =
            KavitaListItem(order = 2, chapterId = 3, seriesId = 30, volumeId = 300, libraryId = 1, seriesFormat = 1)
        val epub =
            KavitaListItem(order = 0, chapterId = 2, seriesId = 20, volumeId = 200, libraryId = 1, seriesFormat = 3)
        val pdf =
            KavitaListItem(order = 1, chapterId = 1, seriesId = 10, volumeId = 100, libraryId = 2, seriesFormat = 4)
        val snapshot = KavitaQueueSnapshot.create("list", listOf(comic, epub, pdf, comic.copy(order = 3)))
        val restored = Json.decodeFromString<KavitaQueueSnapshot>(Json.encodeToString(snapshot))
        val identity = KavitaIdentity(1, "owner")
        val epubUrl = identity.chapter(KavitaChapterRef(1, 20, 200, 2, 3))
        val first = restored.position(identity, epubUrl)
        assertEquals(3, first.size)
        assertEquals(0, first.index)
        assertNull(first.previous)
        val second = restored.position(identity, requireNotNull(first.next))
        assertEquals(4, identity.chapter(first.next!!).format)
        assertEquals(epubUrl, second.previous)
        val last = restored.position(identity, requireNotNull(second.next))
        assertNull(last.next)
        assertEquals(30, identity.chapter(second.next!!).seriesId)
        assertThrows(IllegalArgumentException::class.java) {
            restored.position(KavitaIdentity(1, "another-account"), epubUrl)
        }
    }
}
