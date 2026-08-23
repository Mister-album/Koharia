package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoublePageCompositionPolicyTest {

    @Test
    fun `normal pair is composed when peak stays below both limits`() {
        val page = DoublePageCompositionPolicy.Image(1200, 1800, 2_000_000)

        assertTrue(
            DoublePageCompositionPolicy.shouldCompose(
                page,
                page,
                availableBytes = 512L * 1024 * 1024,
                maxHeapBytes = 512L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun `oversized pair uses tiled views`() {
        val page = DoublePageCompositionPolicy.Image(12_000, 18_000, 40_000_000)

        assertFalse(
            DoublePageCompositionPolicy.shouldCompose(
                page,
                page,
                availableBytes = 512L * 1024 * 1024,
                maxHeapBytes = 512L * 1024 * 1024,
            ),
        )
    }

    @Test
    fun `composition scales both pages to the taller page height`() {
        val layout = DoublePageCompositionPolicy.compositionLayout(
            DoublePageCompositionPolicy.Image(1200, 1800, 2_000_000),
            DoublePageCompositionPolicy.Image(800, 1200, 1_000_000),
        )

        assertEquals(1200, layout?.firstWidth)
        assertEquals(1200, layout?.secondWidth)
        assertEquals(1800, layout?.height)
        assertEquals(2400, layout?.outputWidth)
    }

    @Test
    fun `viewport layout keeps pages touching on a wide tablet`() {
        val page = DoublePageCompositionPolicy.Image(1200, 1800, 2_000_000)

        val layout = DoublePageCompositionPolicy.fitInViewport(
            first = page,
            second = page,
            viewportWidth = 2560,
            viewportHeight = 1600,
        )

        assertEquals(2133, layout?.outputWidth)
        assertEquals(1600, layout?.height)
        assertEquals(213, layout?.left)
        assertEquals(1066, layout?.firstWidth)
        assertEquals(1067, layout?.secondWidth)
        assertEquals(0, layout?.top)
    }

    @Test
    fun `viewport split follows unequal page aspect ratios`() {
        val layout = DoublePageCompositionPolicy.fitInViewport(
            first = DoublePageCompositionPolicy.Image(900, 1800, 1_000_000),
            second = DoublePageCompositionPolicy.Image(1200, 1800, 1_000_000),
            viewportWidth = 2100,
            viewportHeight = 1800,
        )

        assertEquals(900, layout?.firstWidth)
        assertEquals(1200, layout?.secondWidth)
        assertEquals(900f / 2100f, layout?.splitFraction)
    }

    @Test
    fun `placement follows reading direction and explicit inversion`() {
        assertTrue(DoublePagePlacement.firstPageOnLeft(isRightToLeft = false, inverted = false))
        assertFalse(DoublePagePlacement.firstPageOnLeft(isRightToLeft = true, inverted = false))
        assertFalse(DoublePagePlacement.firstPageOnLeft(isRightToLeft = false, inverted = true))
        assertTrue(DoublePagePlacement.firstPageOnLeft(isRightToLeft = true, inverted = true))
    }
}
