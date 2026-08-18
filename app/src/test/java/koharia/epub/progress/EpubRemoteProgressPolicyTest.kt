package koharia.epub.progress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubRemoteProgressPolicyTest {

    @Test
    fun `cached fallback is not accepted as a successful refresh`() {
        assertFalse(
            EpubRemoteProgressPolicy.isFreshResult(
                checkStartedAtMillis = 2_000L,
                checkedAtMillis = 1_999L,
            ),
        )
        assertTrue(
            EpubRemoteProgressPolicy.isFreshResult(
                checkStartedAtMillis = 2_000L,
                checkedAtMillis = 2_000L,
            ),
        )
    }

    @Test
    fun `newer remote progress is kept when locations differ`() {
        assertEquals(
            EpubRemoteProgressDecision.KEEP_REMOTE,
            EpubRemoteProgressPolicy.decide(
                localUpdatedAtMillis = 1_000L,
                remoteModifiedAtMillis = 2_000L,
                sameLocation = false,
                localChangedDuringCheck = false,
            ),
        )
    }

    @Test
    fun `newer local progress is kept when locations differ`() {
        assertEquals(
            EpubRemoteProgressDecision.KEEP_LOCAL,
            EpubRemoteProgressPolicy.decide(
                localUpdatedAtMillis = 2_000L,
                remoteModifiedAtMillis = 1_000L,
                sameLocation = false,
                localChangedDuringCheck = false,
            ),
        )
    }

    @Test
    fun `matching locations accept remote timestamp`() {
        assertEquals(
            EpubRemoteProgressDecision.SAME_LOCATION,
            EpubRemoteProgressPolicy.decide(
                localUpdatedAtMillis = 1_000L,
                remoteModifiedAtMillis = 2_000L,
                sameLocation = true,
                localChangedDuringCheck = false,
            ),
        )
    }

    @Test
    fun `navigation during refresh keeps local progress`() {
        assertEquals(
            EpubRemoteProgressDecision.KEEP_LOCAL,
            EpubRemoteProgressPolicy.decide(
                localUpdatedAtMillis = 1_000L,
                remoteModifiedAtMillis = 2_000L,
                sameLocation = false,
                localChangedDuringCheck = true,
            ),
        )
    }
}
