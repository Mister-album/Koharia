package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfRenderSizeTest {

    @Test
    fun `render size uses a bounded viewport pixel budget`() {
        val size = calculatePdfRenderSize(
            pageWidth = 900,
            pageHeight = 1384,
            viewportWidth = 1080,
            viewportHeight = 2400,
        )

        assertEquals(PdfRenderSize(1590, 2445), size)
        assertTrue(size.width.toLong() * size.height <= 6_000_000)
    }

    @Test
    fun `large displays are capped at maximum render pixels`() {
        val size = calculatePdfRenderSize(
            pageWidth = 1000,
            pageHeight = 1000,
            viewportWidth = 4000,
            viewportHeight = 3000,
        )

        assertEquals(PdfRenderSize(2449, 2449), size)
        assertTrue(size.width.toLong() * size.height <= 6_000_000)
    }

    @Test
    fun `invalid dimensions still produce a renderable bitmap`() {
        val size = calculatePdfRenderSize(
            pageWidth = 0,
            pageHeight = 0,
            viewportWidth = 0,
            viewportHeight = 0,
        )

        assertTrue(size.width > 0)
        assertTrue(size.height > 0)
    }
}
