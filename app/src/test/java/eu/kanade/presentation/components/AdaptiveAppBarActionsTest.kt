package eu.kanade.presentation.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdaptiveAppBarActionsTest {
    @Test
    fun `six actions give title space back without shrinking icons below compact size`() {
        assertEquals(List(6) { 44 }, appBarActionWidths(List(6) { 48 }, 264, 40, 48))
        assertEquals(List(6) { 48 }, appBarActionWidths(List(6) { 48 }, 400, 40, 48))
        assertEquals(List(6) { 40 }, appBarActionWidths(List(6) { 48 }, 100, 40, 48))
    }

    @Test
    fun `text controls and empty popup anchors retain their widths`() {
        assertEquals(listOf(40, 80, 0, 40), appBarActionWidths(listOf(48, 80, 0, 48), 160, 40, 48))
        assertEquals(emptyList<Int>(), appBarActionWidths(emptyList(), 0, 40, 48))
    }

    @Test
    fun `rounding distributes space evenly at different pixel densities`() {
        for (density in 1..4) {
            val minimum = 40 * density
            val regular = 48 * density
            for (budget in minimum * 6..regular * 6) {
                val widths = appBarActionWidths(List(6) { regular }, budget, minimum, regular)
                assertEquals(budget, widths.sum())
                assertTrue(widths.all { it in minimum..regular })
                assertTrue(widths.max() - widths.min() <= 1)
            }
        }
    }
}
