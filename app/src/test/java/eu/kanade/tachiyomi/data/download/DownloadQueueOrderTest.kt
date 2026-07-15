package eu.kanade.tachiyomi.data.download

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadQueueOrderTest {

    @Test
    fun `reorder accepts the same chapters in a different order`() {
        assertTrue(isValidDownloadQueueReorder(listOf(1L, 2L, 3L), listOf(3L, 1L, 2L)))
    }

    @Test
    fun `reorder rejects a transient empty adapter snapshot`() {
        assertFalse(isValidDownloadQueueReorder(listOf(1L), emptyList()))
    }

    @Test
    fun `reorder rejects missing added or duplicated chapters`() {
        assertFalse(isValidDownloadQueueReorder(listOf(1L, 2L), listOf(1L)))
        assertFalse(isValidDownloadQueueReorder(listOf(1L, 2L), listOf(1L, 2L, 3L)))
        assertFalse(isValidDownloadQueueReorder(listOf(1L, 2L), listOf(1L, 1L)))
    }
}
