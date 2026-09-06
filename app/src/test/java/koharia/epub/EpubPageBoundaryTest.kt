package koharia.epub

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubPageBoundaryTest {
    @Test
    fun `first and last page reject navigation outside the book`() {
        assertFalse(canTurnEpubPage(false, 0, 3, 0, 10))
        assertFalse(canTurnEpubPage(true, 2, 3, 9, 10))
        assertFalse(canTurnEpubPage(true, 0, 1, 0, 1))
        assertFalse(canTurnEpubPage(false, 0, 1, 0, 1))
    }

    @Test
    fun `resource boundaries remain navigable inside the book`() {
        assertTrue(canTurnEpubPage(false, 1, 3, 0, 10))
        assertTrue(canTurnEpubPage(true, 1, 3, 9, 10))
        assertTrue(canTurnEpubPage(true, 0, 1, 0, 10))
        assertTrue(canTurnEpubPage(false, 0, 1, 1, 10))
    }

    @Test
    fun `unknown layout does not invent a book boundary`() {
        assertTrue(canTurnEpubPage(false, -1, 3, -1, 0))
    }
}
