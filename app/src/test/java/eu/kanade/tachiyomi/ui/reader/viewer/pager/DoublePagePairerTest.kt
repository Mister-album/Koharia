package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.viewer.pager.DoublePagePairer.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DoublePagePairerTest {

    @Test
    fun `pairs consecutive pages`() {
        assertEquals(
            listOf(Slot(0, 1), Slot(2, 3)),
            DoublePagePairer.pair(listOf(false, false, false, false), shift = false),
        )
    }

    @Test
    fun `shift leaves first page alone`() {
        assertEquals(
            listOf(Slot(0, null), Slot(1, 2), Slot(3, 4)),
            DoublePagePairer.pair(List(5) { false }, shift = true),
        )
    }

    @Test
    fun `odd trailing page remains alone`() {
        assertEquals(
            listOf(Slot(0, 1), Slot(2, null)),
            DoublePagePairer.pair(List(3) { false }, shift = false),
        )
    }

    @Test
    fun `wide page is isolated without changing following pairing`() {
        assertEquals(
            listOf(Slot(0, null), Slot(1, null), Slot(2, 3)),
            DoublePagePairer.pair(listOf(false, true, false, false), shift = false),
        )
    }

    @Test
    fun `leading wide page is isolated`() {
        assertEquals(
            listOf(Slot(0, null), Slot(1, 2)),
            DoublePagePairer.pair(listOf(true, false, false), shift = false),
        )
    }

    @Test
    fun `shift applies to first pairable page while solo pages remain isolated`() {
        assertEquals(
            listOf(Slot(0, null), Slot(1, null), Slot(2, 3), Slot(4, null)),
            DoublePagePairer.pair(listOf(true, false, false, false, false), shift = true),
        )
    }

    @Test
    fun `incompatible page is isolated before trying the next pair`() {
        assertEquals(
            listOf(Slot(0, null), Slot(1, 2), Slot(3, null)),
            DoublePagePairer.pair(
                soloPages = List(4) { false },
                shift = false,
                canPair = { first, second -> first == 1 && second == 2 },
            ),
        )
    }
}
