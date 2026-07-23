package koharia.epub.progress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubPageProgressTest {

    @Test
    fun `legacy progression maps to an image page once`() {
        assertEquals(2, EpubPageProgress.pageIndex(progression = 0.5, totalPages = 5))
    }
}
