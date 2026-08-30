package eu.kanade.tachiyomi.ui.reader.loader

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PdfPageLoaderLifecycleTest {

    @Test
    fun `waiting page stops normally when loader is recycled`() = runTest {
        val allowedPageIndexes = MutableStateFlow<Set<Int>?>(setOf(0))
        val waitingPage = async { awaitPdfPageAccess(allowedPageIndexes, pageIndex = 4) }
        runCurrent()

        allowedPageIndexes.value = null

        assertFalse(waitingPage.await())
    }

    @Test
    fun `waiting page continues when it becomes active`() = runTest {
        val allowedPageIndexes = MutableStateFlow<Set<Int>?>(emptySet())
        val waitingPage = async { awaitPdfPageAccess(allowedPageIndexes, pageIndex = 4) }
        runCurrent()

        allowedPageIndexes.value = setOf(4)

        assertTrue(waitingPage.await())
    }
}
