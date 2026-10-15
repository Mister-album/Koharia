package koharia.epub.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EpubInitializationCoordinatorTest {
    @Test
    fun `close rejects new opens and finish waits for initialization cleanup`() = runTest {
        val coordinator = EpubInitializationCoordinator()
        val cleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val opening = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                    events += "opening closed"
                }
            }
        }
        coordinator.close()
        val late = runCatching { coordinator.runLatest { error("must not open") } }
        assertTrue(late.exceptionOrNull() is CancellationException)
        val finishing = launch { coordinator.finish { events += "resources released" } }
        runCurrent()
        assertTrue(events.isEmpty())
        cleanup.complete(Unit)
        opening.join()
        finishing.join()
        assertEquals(listOf("opening closed", "resources released"), events)
        assertTrue(runCatching { coordinator.runLatest { } }.exceptionOrNull() is CancellationException)
        assertTrue(isActive)
    }

    @Test
    fun `latest request waits for cancelled resolver cleanup and skips intermediate requests`() = runTest {
        val coordinator = EpubInitializationCoordinator()
        val cleanup = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest {
                events += "first"
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                    events += "closed"
                }
            }
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest { events += "second" }
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest {
                events += "third"
                3
            }
        }
        runCurrent()
        assertEquals(listOf("first"), events)
        cleanup.complete(Unit)
        assertEquals(3, third.await())
        first.join()
        second.join()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(isActive)
        assertEquals(listOf("first", "closed", "third"), events)
    }

    @Test
    fun `swallowed cancellation cannot return a successful initialization`() = runTest {
        val coordinator = EpubInitializationCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest {
                runCatching { awaitCancellation() }
                "obsolete"
            }
        }
        assertEquals("current", coordinator.runLatest { "current" })
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(isActive)
    }

    @Test
    fun `caller cancellation and opening failure allow retry`() = runTest {
        val coordinator = EpubInitializationCoordinator()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest { awaitCancellation() }
        }
        first.cancelAndJoin()
        val failure = runCatching { coordinator.runLatest { error("open failed") } }
        assertEquals("open failed", failure.exceptionOrNull()?.message)
        assertEquals("ready", coordinator.runLatest { "ready" })
    }

    @Test
    fun `already cancelled caller does not supersede an active request`() = runTest {
        val coordinator = EpubInitializationCoordinator()
        val finish = CompletableDeferred<Unit>()
        val current = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runLatest {
                finish.await()
                "ready"
            }
        }
        val cancelled = launch {
            coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            coordinator.runLatest { error("must not enter") }
        }
        cancelled.join()
        assertFalse(current.isCancelled)
        finish.complete(Unit)
        assertEquals("ready", current.await())
    }
}
