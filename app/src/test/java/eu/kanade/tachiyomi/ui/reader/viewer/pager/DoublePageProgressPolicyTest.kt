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

    @Test
    fun `new spread restores its first logical page without changing the progress page`() {
        val first = page(4)
        val second = page(5)
        val slot = PagerSlot.Pages(first, second)
        val anchor = DoublePageProgressPolicy.layoutAnchor(slot, null, page(3), userNavigation = true)
        assertSame(first, anchor)
        assertSame(second, slot.progressPage)
    }

    @Test
    fun `rotation round trip preserves an explicitly selected second page`() {
        val first = page(4)
        val second = page(5)
        val pair = PagerSlot.Pages(first, second)
        val landscape = DoublePageProgressPolicy.layoutAnchor(pair, second, null, userNavigation = false)
        val portrait = DoublePageProgressPolicy.layoutAnchor(
            PagerSlot.Pages(second),
            landscape,
            null,
            userNavigation = false,
        )
        assertSame(second, portrait)
        assertSame(second, DoublePageProgressPolicy.layoutAnchor(pair, second, first, userNavigation = true))
    }

    @Test
    fun `layout changes do not commit progress but classified user navigation still does`() {
        assertFalse(DoublePageProgressPolicy.shouldCommitSelection(false, false, false))
        assertTrue(DoublePageProgressPolicy.shouldCommitSelection(true, false, false))
        assertTrue(DoublePageProgressPolicy.shouldCommitSelection(false, true, false))
        assertTrue(DoublePageProgressPolicy.shouldCommitSelection(false, false, true))
    }

    @Test
    fun `background classification preserves the visible spread reading anchor`() {
        val first = page(4)
        val second = page(5)
        assertSame(
            first,
            DoublePageProgressPolicy.layoutAnchor(
                PagerSlot.Pages(first, second),
                second,
                first,
                userNavigation = false,
                layoutRebuild = true,
            ),
        )
    }

    @Test
    fun `pending spread navigation follows first page into single page layout`() {
        val first = page(4)
        val second = page(5)
        val pending = DoublePageProgressPolicy.layoutState(PagerSlot.Pages(first, second), first, second)
        val rebuilt = PagerSlot.Pages(pending.anchor)
        assertSame(first, pending.anchor)
        assertTrue(
            DoublePageProgressPolicy.shouldCommitSelection(
                userNavigation = false,
                restoringSinglePage = false,
                pendingCommitInSlot = pending.commitPending && rebuilt.contains(pending.anchor),
            ),
        )
    }

    @Test
    fun `recreating a committed spread does not advance progress`() {
        val first = page(4)
        val state = DoublePageProgressPolicy.layoutState(PagerSlot.Pages(first, page(5)), first, null)
        assertFalse(state.commitPending)
        assertSame(first, state.anchor)
    }

    @Test
    fun `layout state does not transfer pending navigation from another slot`() {
        val first = page(4)
        val state = DoublePageProgressPolicy.layoutState(PagerSlot.Pages(first, page(5)), first, page(3))
        assertFalse(state.commitPending)
    }

    private fun page(index: Int) = ReaderPage(index, "page-$index", null)
}
