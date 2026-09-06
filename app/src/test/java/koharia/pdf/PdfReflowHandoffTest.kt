package koharia.pdf

import eu.kanade.presentation.reader.readerPageIndicatorText
import koharia.epub.EpubPaginationPhase
import koharia.epub.hasAccuratePageCount
import koharia.pdf.reflow.PdfReflowHandoff
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfReflowHandoffTest {
    @Test
    fun `background conversion follows the page read while conversion was running`() {
        val handoff = PdfReflowHandoff(10, 40)
        assertEquals(51, handoff.pageFor(10, 52))
        assertEquals(40, handoff.pageFor(10, -1))
        assertNull(handoff.pageFor(11, 52))
        assertNull(handoff.pageFor(null, 52))
    }

    @Test
    fun `pending reading indicator contains only reading percentage`() {
        assertEquals("0%", readerPageIndicatorText(1, 101, 1, true))
        assertEquals("50%", readerPageIndicatorText(51, 101, 50, true))
        assertEquals("100%", readerPageIndicatorText(101, 101, 101, true))
        assertNull(readerPageIndicatorText(-1, -1, -1, true))
        assertEquals("50–51 / 101", readerPageIndicatorText(51, 101, 50, false))
    }

    @Test
    fun `accurate page counts are shown only after calculation or a verified cache hit`() {
        assertFalse(EpubPaginationPhase.CALCULATING.hasAccuratePageCount)
        assertFalse(EpubPaginationPhase.UNAVAILABLE.hasAccuratePageCount)
        assertTrue(EpubPaginationPhase.READY.hasAccuratePageCount)
        assertTrue(EpubPaginationPhase.CACHED.hasAccuratePageCount)
    }
}
