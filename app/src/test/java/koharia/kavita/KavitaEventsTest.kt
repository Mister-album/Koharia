package koharia.kavita

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KavitaEventsTest {
    @Test fun handshakeTimeoutIsRetryableAndNextHandshakeCanSucceed() = runTest {
        val handshake = CompletableDeferred<Unit>()
        val failure = runCatching { awaitKavitaHandshake(handshake) }.exceptionOrNull()
        assertTrue(failure is KavitaException)
        assertEquals(KavitaException.Reason.NETWORK, (failure as KavitaException).reason)
        assertEquals(15_000L, testScheduler.currentTime)
        assertFalse(handshake.isCancelled)
        awaitKavitaHandshake(CompletableDeferred(Unit))
    }

    @Test fun cancellingSessionDoesNotBecomeRetryableFailure() = runTest {
        var retryable = false
        val job = launch {
            try {
                awaitKavitaHandshake(CompletableDeferred())
            } catch (_: KavitaException) {
                retryable = true
            }
        }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(retryable)
    }
}
