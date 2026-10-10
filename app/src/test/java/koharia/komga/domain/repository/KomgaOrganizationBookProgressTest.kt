package koharia.komga.domain.repository

import koharia.komga.api.dto.BookReadProgressDto
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaOrganizationBookProgressTest {
    @Test
    fun `old account rows do not override server progress and new changes survive reopening`() {
        val storage = mutableMapOf<String, String>()
        val progress =
            KomgaOrganizationBookProgress(storage::get) { key, value -> storage[key] = value }
        val remote = BookReadProgressDto(completed = false, lastModified = "first")
        assertFalse(progress.observe("book", remote, mapOf(1L to true)))
        assertFalse(progress.observe("book", remote, mapOf(1L to false)))
        assertTrue(progress.observe("book", remote, mapOf(1L to true)))
        val reopened =
            KomgaOrganizationBookProgress(storage::get) { key, value -> storage[key] = value }
        assertTrue(reopened.observe("book", remote, mapOf(1L to true)))
        assertFalse(
            reopened.observe("book", remote.copy(lastModified = "second"), mapOf(1L to true)),
        )
        val otherAccount = KomgaOrganizationBookProgress({ null }) { _, _ -> }
        assertFalse(otherAccount.observe("book", remote, mapOf(1L to true)))
    }

    @Test
    fun `explicit unread change wins over stale read aliases and download emissions preserve progress`() {
        val storage = mutableMapOf<String, String>()
        val progress =
            KomgaOrganizationBookProgress(storage::get) { key, value -> storage[key] = value }
        val remote = BookReadProgressDto(completed = true)
        assertTrue(progress.observe("book", remote, mapOf(1L to true, 2L to true)))
        assertFalse(progress.observe("book", remote, mapOf(1L to false, 2L to true)))
        assertFalse(progress.observe("book", remote, mapOf(1L to false, 2L to true)))
        assertTrue(progress.observe("another", remote, emptyMap()))
    }
}
