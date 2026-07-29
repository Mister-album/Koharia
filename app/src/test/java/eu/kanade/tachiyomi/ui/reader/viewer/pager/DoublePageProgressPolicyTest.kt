package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoublePageProgressPolicyTest {

    @Test
    fun `pending pair commit transfers to first replacement page`() {
        val first = page(0)
        val second = page(1)

        val selection = DoublePageProgressPolicy.classificationAnchor(
            pendingCommitAnchor = second,
            stableAnchor = second,
            classifiedPages = listOf(first, second),
        )

        assertSame(first, selection?.page)
        assertTrue(selection?.transfersPendingCommit == true)
    }

    @Test
    fun `layout-only rebuild retains stable anchor without transferring progress`() {
        val first = page(0)
        val second = page(1)

        val selection = DoublePageProgressPolicy.classificationAnchor(
            pendingCommitAnchor = null,
            stableAnchor = second,
            classifiedPages = listOf(first, second),
        )

        assertSame(second, selection?.page)
        assertFalse(selection?.transfersPendingCommit == true)
    }

    @Test
    fun `stale pending commit does not replace a newer stable anchor`() {
        val first = page(0)
        val second = page(1)
        val stale = page(2)

        val selection = DoublePageProgressPolicy.classificationAnchor(
            pendingCommitAnchor = stale,
            stableAnchor = second,
            classifiedPages = listOf(first, second),
        )

        assertSame(second, selection?.page)
        assertFalse(selection?.transfersPendingCommit == true)
    }

    private fun page(index: Int) = ReaderPage(index, "page-$index", null)
}
