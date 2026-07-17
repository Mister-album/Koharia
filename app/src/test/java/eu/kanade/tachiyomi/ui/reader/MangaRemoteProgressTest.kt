package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MangaRemoteProgressTest {

    @Test
    fun `page percentage uses one based visible page`() {
        assertEquals(1, pageProgressPercent(pageIndex = 0, totalPages = 100))
        assertEquals(50, pageProgressPercent(pageIndex = 49, totalPages = 100))
        assertEquals(100, pageProgressPercent(pageIndex = 99, totalPages = 100))
    }

    @Test
    fun `page percentage handles unavailable totals`() {
        assertEquals(0, pageProgressPercent(pageIndex = 0, totalPages = 0))
    }

    @Test
    fun `initial publication metadata is not treated as a file change`() {
        assertEquals(false, hasPublicationChanged(oldVersion = null, newVersion = "hash:v1"))
    }

    @Test
    fun `known publication version change is detected`() {
        assertEquals(true, hasPublicationChanged(oldVersion = "hash:v1", newVersion = "hash:v2"))
    }
}
