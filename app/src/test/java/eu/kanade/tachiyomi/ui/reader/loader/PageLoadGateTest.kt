package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageLoadGateTest {

    @Test
    fun `prefetch remains closed until active page is displayed`() {
        val gate = PageLoadGate(preloadSize = 4)

        assertTrue(gate.activate(7, 20).changed)
        assertTrue(gate.isActive(7))
        assertFalse(gate.isActive(8))
        assertEquals(emptyList<Int>(), gate.onPageDisplayed(8, 20))
        assertEquals(listOf(8, 9, 10, 11), gate.onPageDisplayed(7, 20))
    }

    @Test
    fun `selection advances prefetch window after first page is displayed`() {
        val gate = PageLoadGate(preloadSize = 4)
        assertEquals(emptyList<Int>(), gate.activate(2, 20).prefetchIndexes)
        assertEquals(listOf(3, 4, 5, 6), gate.onPageDisplayed(2, 20))

        val selection = gate.activate(5, 20)

        assertTrue(selection.changed)
        assertEquals(listOf(6, 7, 8, 9), selection.prefetchIndexes)
    }

    @Test
    fun `jumping replaces old prefetch window and clamps at chapter end`() {
        val gate = PageLoadGate(preloadSize = 4)
        gate.activate(2, 10)
        gate.onPageDisplayed(2, 10)
        val selection = gate.activate(8, 10)

        assertEquals(emptyList<Int>(), gate.onPageDisplayed(2, 10))
        assertEquals(listOf(9), selection.prefetchIndexes)
    }

    @Test
    fun `selecting a previous page reverses prefetch direction`() {
        val gate = PageLoadGate(preloadSize = 4)
        gate.activate(10, 20)
        gate.onPageDisplayed(10, 20)

        val selection = gate.activate(9, 20)

        assertEquals(listOf(8, 7, 6, 5), selection.prefetchIndexes)
        assertEquals(listOf(8, 7, 6, 5), gate.onPageDisplayed(9, 20))
    }

    @Test
    fun `backward prefetch clamps at chapter start`() {
        val gate = PageLoadGate(preloadSize = 4)
        gate.activate(5, 20)
        gate.onPageDisplayed(5, 20)

        val selection = gate.activate(1, 20)

        assertEquals(listOf(0), selection.prefetchIndexes)
    }
}
