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

    @Test
    fun `double page slot activates both pages and waits for both to display`() {
        val gate = PageLoadGate(preloadSize = 4)

        val activation = gate.activate(setOf(6, 7), logicalPageIndex = 7, pageCount = 20)

        assertTrue(activation.changed)
        assertTrue(gate.isActive(6))
        assertTrue(gate.isActive(7))
        assertEquals(emptyList<Int>(), gate.onPagesDisplayed(setOf(6), 20))
        assertEquals(listOf(8, 9, 10, 11), gate.onPagesDisplayed(setOf(6, 7), 20))
    }

    @Test
    fun `backward double page prefetch starts before first visible page`() {
        val gate = PageLoadGate(preloadSize = 4)
        gate.activate(setOf(10, 11), logicalPageIndex = 11, pageCount = 20)
        gate.onPagesDisplayed(setOf(10, 11), 20)

        val activation = gate.activate(setOf(8, 9), logicalPageIndex = 9, pageCount = 20)

        assertEquals(listOf(7, 6, 5, 4), activation.prefetchIndexes)
    }

    @Test
    fun `late display from an old spread cannot unlock the new spread`() {
        val gate = PageLoadGate(preloadSize = 4)
        gate.activate(setOf(2, 3), logicalPageIndex = 3, pageCount = 20)
        gate.onPagesDisplayed(setOf(2, 3), 20)
        gate.activate(setOf(8, 9), logicalPageIndex = 9, pageCount = 20)

        assertEquals(emptyList<Int>(), gate.onPagesDisplayed(setOf(2, 3), 20))
        assertEquals(listOf(10, 11, 12, 13), gate.onPagesDisplayed(setOf(8, 9), 20))
    }
}
