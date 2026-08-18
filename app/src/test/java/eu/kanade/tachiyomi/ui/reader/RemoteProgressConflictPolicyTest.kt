package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RemoteProgressConflictPolicyTest {

    @Test
    fun `layout-only progress change does not conflict with opening server position`() {
        assertFalse(
            RemoteProgressConflictPolicy.hasConflict(
                openingPageIndex = 20,
                currentPageIndex = 22,
                remotePageIndex = 20,
            ),
        )
    }

    @Test
    fun `server position matching current navigation does not conflict`() {
        assertFalse(
            RemoteProgressConflictPolicy.hasConflict(
                openingPageIndex = 20,
                currentPageIndex = 24,
                remotePageIndex = 24,
            ),
        )
    }

    @Test
    fun `server position differing from opening and current positions conflicts`() {
        assertTrue(
            RemoteProgressConflictPolicy.hasConflict(
                openingPageIndex = 20,
                currentPageIndex = 24,
                remotePageIndex = 18,
            ),
        )
    }

    @Test
    fun `newer local progress may be written when positions differ`() {
        assertEquals(
            RemoteProgressDecision.KEEP_LOCAL,
            RemoteProgressConflictPolicy.decide(
                localUpdatedAtMillis = 2_000L,
                remoteUpdatedAtMillis = 1_000L,
                sameLocation = false,
                localChangedDuringCheck = false,
            ),
        )
    }

    @Test
    fun `newer or unknown remote progress requires confirmation`() {
        assertEquals(
            RemoteProgressDecision.KEEP_REMOTE,
            RemoteProgressConflictPolicy.decide(
                localUpdatedAtMillis = 1_000L,
                remoteUpdatedAtMillis = 2_000L,
                sameLocation = false,
                localChangedDuringCheck = false,
            ),
        )
        assertEquals(
            RemoteProgressDecision.KEEP_REMOTE,
            RemoteProgressConflictPolicy.decide(
                localUpdatedAtMillis = 1_000L,
                remoteUpdatedAtMillis = null,
                sameLocation = false,
                localChangedDuringCheck = false,
            ),
        )
    }

    @Test
    fun `navigation during refresh makes local progress newest`() {
        assertEquals(
            RemoteProgressDecision.KEEP_LOCAL,
            RemoteProgressConflictPolicy.decide(
                localUpdatedAtMillis = 1_000L,
                remoteUpdatedAtMillis = 2_000L,
                sameLocation = false,
                localChangedDuringCheck = true,
            ),
        )
    }
}
