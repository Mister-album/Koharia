package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.setting.MergedPageLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergedPageExportPolicyTest {
    private val first = DoublePageCompositionPolicy.Image(101, 201, 0)
    private val second = DoublePageCompositionPolicy.Image(200, 400, 0)

    @Test
    fun `original pixels preserve odd dimensions and center without scaling`() {
        val layout = MergedPageExportPolicy.layout(first, second, MergedPageLayout.ORIGINAL_PIXELS)
        assertEquals(301, layout.width)
        assertEquals(400, layout.height)
        assertEquals(MergedPageExportPolicy.Placement(101, 201, 99), layout.first)
    }

    @Test
    fun `matched layout retains existing display composition dimensions`() {
        val layout = MergedPageExportPolicy.layout(first, second, MergedPageLayout.MATCH_HEIGHT)
        assertEquals(DoublePageCompositionPolicy.compositionLayout(first, second)?.outputWidth, layout.width)
        assertEquals(400, layout.first.height)
        assertEquals(0, layout.first.top)
    }

    @Test
    fun `reject invalid sizes overflow and inadequate memory`() {
        assertThrows(IllegalArgumentException::class.java) {
            MergedPageExportPolicy.layout(first.copy(width = 0), second, MergedPageLayout.MATCH_HEIGHT)
        }
        assertThrows(ArithmeticException::class.java) {
            MergedPageExportPolicy.layout(first.copy(width = Int.MAX_VALUE), second, MergedPageLayout.ORIGINAL_PIXELS)
        }
        val layout = MergedPageExportPolicy.layout(first, second, MergedPageLayout.ORIGINAL_PIXELS)
        assertFalse(MergedPageExportPolicy.fitsMemory(layout, first, second, 1024, 1024))
        assertTrue(MergedPageExportPolicy.fitsMemory(layout, first, second, 100_000_000, 100_000_000))
    }

    @Test
    fun `webp limit never causes a resize`() {
        assertTrue(MergedPageExportPolicy.supportsWebp(16383, 16383, 30))
        assertFalse(MergedPageExportPolicy.supportsWebp(16384, 1, 36))
        assertFalse(MergedPageExportPolicy.supportsWebp(1, 16384, 36))
        assertFalse(MergedPageExportPolicy.supportsWebp(100, 100, 29))
    }
}
