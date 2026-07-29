package eu.kanade.tachiyomi.ui.reader

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
}
