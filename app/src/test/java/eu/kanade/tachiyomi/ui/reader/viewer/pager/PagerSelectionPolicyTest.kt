package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PagerSelectionPolicyTest {

    @Test
    fun `viewport resize selection without navigation is ignored`() {
        assertFalse(
            PagerSelectionPolicy.shouldHandle(
                selectedPosition = 6,
                pendingPosition = null,
                userDragSelectionPending = false,
            ),
        )
    }

    @Test
    fun `matching app page turn is handled`() {
        assertTrue(
            PagerSelectionPolicy.shouldHandle(
                selectedPosition = 6,
                pendingPosition = 6,
                userDragSelectionPending = false,
            ),
        )
    }

    @Test
    fun `stale app page turn does not authorize another position`() {
        assertFalse(
            PagerSelectionPolicy.shouldHandle(
                selectedPosition = 7,
                pendingPosition = 6,
                userDragSelectionPending = false,
            ),
        )
    }

    @Test
    fun `direct user drag selection is handled`() {
        assertTrue(
            PagerSelectionPolicy.shouldHandle(
                selectedPosition = 7,
                pendingPosition = null,
                userDragSelectionPending = true,
            ),
        )
    }
}
