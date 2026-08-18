package eu.kanade.tachiyomi.ui.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RemoteProgressConflictPolicyTest {

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
    fun `unknown local progress timestamp requires confirmation`() {
        assertEquals(
            RemoteProgressDecision.KEEP_REMOTE,
            RemoteProgressConflictPolicy.decide(
                localUpdatedAtMillis = null,
                remoteUpdatedAtMillis = 2_000L,
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
