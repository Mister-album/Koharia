package eu.kanade.presentation.reader

import eu.kanade.tachiyomi.ui.reader.loader.ReaderBufferingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReaderStatusIndicatorTest {
    @Test
    fun `loading label becomes speed only during a measured transfer`() {
        assertNull(ReaderBufferingState().visibleSpeed(enabled = true))
        assertNull(ReaderBufferingState(activeTransfers = 1).visibleSpeed(enabled = true))
        assertEquals(2_048L, ReaderBufferingState(1, 2_048).visibleSpeed(enabled = true))
        // The meter may retain its final sample after download while the page is still decoding.
        assertNull(ReaderBufferingState(0, 2_048).visibleSpeed(enabled = true))
    }

    @Test
    fun `disabling transfer speed keeps the loading label during download`() {
        assertNull(ReaderBufferingState(1, 2_048).visibleSpeed(enabled = false))
    }

    @Test
    fun `transfer speed chooses compact binary units`() {
        assertEquals("512 B/s", formatReaderTransferSpeed(512))
        assertEquals("2 KB/s", formatReaderTransferSpeed(2_048))
        assertEquals("2.0 MB/s", formatReaderTransferSpeed(2L * 1_024 * 1_024))
    }

    @Test
    fun `page range remains compatible with the old indicator`() {
        assertEquals("4–5 / 20", readerPageIndicatorText(5, 20, 4, false))
    }

    @Test
    fun `chapter and page information share the left reader status`() {
        assertEquals("Chapter 7 · 4–5 / 20", readerStatusLeftText("Chapter 7", "4–5 / 20", true))
        assertEquals("4–5 / 20", readerStatusLeftText("Chapter 7", "4–5 / 20", false))
    }
}
