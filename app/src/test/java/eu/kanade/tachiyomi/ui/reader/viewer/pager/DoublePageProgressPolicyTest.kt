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

    @Test
    fun `activation displays stable anchor instead of provisional later page`() {
        val first = page(0)
        val second = page(1)

        val displayPage = DoublePageProgressPolicy.activationDisplayPage(listOf(first, second), first)
        val visiblePageEnd = DoublePageProgressPolicy.visiblePageEnd(listOf(first, second))

        assertSame(first, displayPage)
        assertSame(second, visiblePageEnd)
    }

    @Test
    fun `activation falls back to last visible page without anchor`() {
        val first = page(0)
        val second = page(1)

        val displayPage = DoublePageProgressPolicy.activationDisplayPage(listOf(first, second), null)

        assertSame(second, displayPage)
    }

    @Test
    fun `activation resolves replacement page with the same physical index`() {
        val replacement = page(0)

        val displayPage = DoublePageProgressPolicy.activationDisplayPage(listOf(replacement, page(1)), page(0))

        assertSame(replacement, displayPage)
    }

    private fun page(index: Int) = ReaderPage(index, "page-$index", null)
}
