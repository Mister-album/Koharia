package koharia.connection

import eu.kanade.presentation.library.components.MangaReadProgress
import eu.kanade.presentation.library.components.MangaReadProgressDisplay
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ConnectionShelfProgressTest {
    @Test
    fun `local updates replace stale chapter totals only for the same reading unit count`() {
        val local = ConnectionShelfEntryState(3, 4, 3)
        assertEquals(MangaReadProgress(3, 4), resolveShelfReadProgress(local, MangaReadProgress(1, 4), 4))
        assertEquals(MangaReadProgress(3, 4), resolveShelfReadProgress(local, null, null))
        assertEquals(MangaReadProgress(1, 5), resolveShelfReadProgress(local, MangaReadProgress(1, 5), 5))
        assertNull(resolveShelfReadProgress(local, null, 5))
        assertNull(resolveShelfReadProgress(local, null, 0))
        assertEquals(MangaReadProgress(1, 5), resolveShelfReadProgress(local, MangaReadProgress(1, 4), 5))
        assertEquals(MangaReadProgress(3, 3), resolveShelfReadProgress(null, MangaReadProgress(4, 4), 3))
        assertNull(resolveShelfReadProgress(local, MangaReadProgress(1, 4), 0))
    }

    @Test
    fun `archive pages and publication percentages retain provider progress semantics`() {
        val local = ConnectionShelfEntryState(1, 1, 0)
        for (display in listOf(MangaReadProgressDisplay.PAGE, MangaReadProgressDisplay.PERCENTAGE)) {
            val progress = MangaReadProgress(25, 100, display)
            assertEquals(progress, resolveShelfReadProgress(local, progress, 1))
        }
    }

    @Test
    fun `server total changes cannot turn retained downloads into complete series`() {
        val local = ConnectionShelfEntryState(4, 4, 3)
        assertEquals(MangaDownloadStatus.COMPLETE, local.downloads(4).status)
        assertEquals(MangaDownloadStatus.PARTIAL, local.downloads(5).status)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, local.downloads(3).status)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, local.downloads(0).status)
        assertEquals(MangaDownloadStatus.UNKNOWN_TOTAL, ConnectionShelfEntryState(4, null, 0).downloads(4).status)
        assertEquals(MangaDownloadStatus.NONE, ConnectionShelfEntryState(0, 4, 3).downloads(4).status)
    }
}
