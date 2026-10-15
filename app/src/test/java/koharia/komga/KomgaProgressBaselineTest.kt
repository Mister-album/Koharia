package koharia.komga

import koharia.komga.progress.KomgaProgressBaseline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaProgressBaselineTest {
    private val unread = KomgaProgressBaseline(
        1, "book", "content", "publication", 100, 10, false, false, false, null, false, null, null,
    )

    @Test
    fun `versioned evidence roundtrips and rejects missing or future schemas`() {
        assertEquals(unread, KomgaProgressBaseline.decode(unread.encode()))
        assertNull(KomgaProgressBaseline.decode(unread.encode().replace("\"version\":1", "\"version\":2")))
        assertNull(KomgaProgressBaseline.decode(unread.encode().replace("\"version\":1,", "")))
        assertNull(KomgaProgressBaseline.decode("legacy-read-date"))
        assertNull(KomgaProgressBaseline.decode(null))
    }

    @Test
    fun `progress existence and modification are distinct from read date and zero index`() {
        val reading = unread.copy(progressPresent = true, page = 1, progressModified = "remote-1")
        assertTrue(reading.valid)
        assertFalse(reading.copy(progressModified = null).valid)
        assertFalse(unread.copy(page = 1).valid)
        assertFalse(reading.copy(page = 0).valid)
        assertFalse(reading.copy(page = 11).valid)
        assertFalse(unread.copy(fileHash = null, fileLastModified = null).valid)
    }

    @Test
    fun `native EPUB explicit status retains null page and zero visual total`() {
        val book = unread.copy(total = 0, epub = true)
        val read = book.copy(progressPresent = true, completed = true, progressModified = "server")
        assertTrue(book.valid)
        assertTrue(read.valid)
        assertTrue(read.matchesReadStatusTarget(book, true))
        assertTrue(book.matchesReadStatusTarget(read, false))
        assertFalse(read.matchesPageTarget(book, 0, 1))
        assertFalse(book.copy(fileHash = "replacement").matchesReadStatusTarget(read, false))
    }

    @Test
    fun `target match includes publication ownership total and actual completion semantics`() {
        val target = unread.copy(progressPresent = true, page = 4, progressModified = "remote-2")
        assertTrue(target.matchesPageTarget(unread, 3, 10))
        for (changed in listOf(
            target.copy(bookId = "another"),
            target.copy(fileHash = "replaced"),
            target.copy(total = 20),
            target.copy(size = 200),
            target.copy(completed = true),
            target.copy(epub = true),
            target.copy(page = 5),
        )) {
            assertFalse(changed.matchesPageTarget(unread, 3, 10))
        }
        val complete = target.copy(page = 10, completed = true)
        assertTrue(complete.matchesPageTarget(unread, 9, 10))
        // Page-only PATCH leaves an already-completed book completed.
        assertTrue(target.copy(completed = true).matchesPageTarget(complete, 3, 10))
        assertFalse(target.copy(epub = true).matchesPageTarget(unread.copy(epub = true), 3, 10))
    }
}
