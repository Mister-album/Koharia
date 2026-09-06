package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfPreparationFailureStateTest {
    @Test
    fun `failure ends preparation and keeps the current book and original-reader escape route`() {
        val state = EpubReaderUiState(
            mangaId = 1, chapterId = 2, isReady = true, isPreparingPdf = true,
            canOpenAsPages = true, currentHref = "EPUB/text/chunk-0.xhtml", currentPosition = 3,
            isSearchable = true, localEpubUri = "incomplete.epub",
        ).withPdfPreparationFailure("Reflow failed")
        assertFalse(state.isPreparingPdf)
        assertEquals(EpubPaginationPhase.UNAVAILABLE, state.paginationPhase)
        assertEquals("Reflow failed", state.errorMessage)
        assertFalse(state.isSearchable)
        assertNull(state.localEpubUri)
        assertTrue(state.isReady && state.canOpenAsPages)
        assertEquals(2L, state.chapterId)
        assertEquals("EPUB/text/chunk-0.xhtml", state.currentHref)
        assertEquals(3, state.currentPosition)
    }
}
