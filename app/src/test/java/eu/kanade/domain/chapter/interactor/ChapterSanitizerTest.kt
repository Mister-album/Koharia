package eu.kanade.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.data.chapter.ChapterSanitizer.sanitize

class ChapterSanitizerTest {
    @Test
    fun `single file chapter keeps its title when it equals the book title`() {
        assertEquals("Public Test - GIF", " Public Test - GIF ".sanitize("Public Test - GIF"))
    }

    @Test
    fun `series chapter still removes the repeated book prefix`() {
        assertEquals("Chapter 2", "Alice - Chapter 2".sanitize("Alice"))
    }
}
