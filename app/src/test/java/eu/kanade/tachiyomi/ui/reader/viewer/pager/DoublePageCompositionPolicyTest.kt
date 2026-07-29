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
    fun `placement follows reading direction and explicit inversion`() {
        assertTrue(DoublePagePlacement.firstPageOnLeft(isRightToLeft = false, inverted = false))
        assertFalse(DoublePagePlacement.firstPageOnLeft(isRightToLeft = true, inverted = false))
        assertFalse(DoublePagePlacement.firstPageOnLeft(isRightToLeft = false, inverted = true))
        assertTrue(DoublePagePlacement.firstPageOnLeft(isRightToLeft = true, inverted = true))
    }
}
