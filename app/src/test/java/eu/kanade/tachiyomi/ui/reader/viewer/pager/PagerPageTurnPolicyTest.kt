package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagerPageTurnPolicyTest {

    @Test
    fun `no animation uses discrete swipes on either paging axis`() {
        for (horizontal in listOf(true, false)) {
            assertTrue(PagerPageTurnPolicy.shouldInterceptSwipe(PageTransitionEffect.NONE, horizontal, true))
        }
    }

    @Test
    fun `disabled system animations use discrete swipes for every effect`() {
        for (effect in PageTransitionEffect.entries) {
            for (horizontal in listOf(true, false)) {
                assertTrue(PagerPageTurnPolicy.shouldInterceptSwipe(effect, horizontal, false))
            }
        }
    }

    @Test
    fun `native page transformers retain dragging except horizontal curl`() {
        for (effect in PageTransitionEffect.entries.filter { it != PageTransitionEffect.NONE }) {
            assertEquals(
                effect == PageTransitionEffect.CURL,
                PagerPageTurnPolicy.shouldInterceptSwipe(effect, true, true),
            )
            assertFalse(PagerPageTurnPolicy.shouldInterceptSwipe(effect, false, true))
        }
    }

    @Test
    fun `rendered destination page turns immediately`() {
        assertFalse(wait(targetHolderAttached = true, targetRendered = true))
    }

    @Test
    fun `attached but unrendered destination page is waited for`() {
        assertTrue(wait(targetHolderAttached = true, targetRendered = false))
    }

    @Test
    fun `destination without a holder is not waited for`() {
        // Nothing can report readiness for a holder that was never attached, so waiting would only
        // stall the reader until the fallback timeout.
        assertFalse(wait(targetHolderAttached = false, targetRendered = false))
    }

    @Test
    fun `turn already in flight keeps banking further turns`() {
        assertTrue(wait(turnInFlight = true, targetHolderAttached = false, targetRendered = false))
    }

    @Test
    fun `running scroll keeps banking further turns`() {
        assertTrue(wait(pagerIdle = false, targetHolderAttached = true, targetRendered = true))
    }

    @Test
    fun `chapter transition turns are never deferred`() {
        assertFalse(wait(sourceIsPage = true, targetIsPage = false, targetHolderAttached = false))
        assertFalse(wait(sourceIsPage = false, targetIsPage = true, targetHolderAttached = false))
    }

    @Test
    fun `banked turns keep the net direction`() {
        var pending = 0
        repeat(5) { pending = PagerPageTurnPolicy.accumulate(pending, 1) }
        assertEquals(5, pending)

        pending = PagerPageTurnPolicy.accumulate(pending, -1)
        assertEquals(4, pending)

        pending = PagerPageTurnPolicy.accumulate(pending, -4)
        assertEquals(0, pending)

        pending = PagerPageTurnPolicy.accumulate(pending, -2)
        assertEquals(-2, pending)
    }

    @Test
    fun `banked turns are bounded`() {
        var pending = 0
        repeat(100) { pending = PagerPageTurnPolicy.accumulate(pending, 1) }
        assertEquals(PagerPageTurnPolicy.MAX_PENDING_TURNS, pending)

        repeat(200) { pending = PagerPageTurnPolicy.accumulate(pending, -1) }
        assertEquals(-PagerPageTurnPolicy.MAX_PENDING_TURNS, pending)
    }

    @Test
    fun `banked turns are applied one page at a time`() {
        assertEquals(1, PagerPageTurnPolicy.nextStep(7))
        assertEquals(-1, PagerPageTurnPolicy.nextStep(-7))
        assertEquals(0, PagerPageTurnPolicy.nextStep(0))

        var pending = 3
        val applied = mutableListOf<Int>()
        while (true) {
            val step = PagerPageTurnPolicy.nextStep(pending)
            if (step == 0) break
            pending -= step
            applied += step
        }
        assertEquals(listOf(1, 1, 1), applied)
        assertEquals(0, pending)
    }

    private fun wait(
        turnInFlight: Boolean = false,
        pagerIdle: Boolean = true,
        sourceIsPage: Boolean = true,
        targetIsPage: Boolean = true,
        targetHolderAttached: Boolean,
        targetRendered: Boolean = false,
    ): Boolean = PagerPageTurnPolicy.shouldWaitForTarget(
        turnInFlight = turnInFlight,
        pagerIdle = pagerIdle,
        sourceIsPage = sourceIsPage,
        targetIsPage = targetIsPage,
        targetHolderAttached = targetHolderAttached,
        targetRendered = targetRendered,
    )
}
