package koharia.source.local

import koharia.connection.ConnectionLibraryRefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLibraryRefreshTasksTest {
    @Test
    fun `removing a connection waits for its scan to stop`() = runTest {
        val tasks = LocalLibraryRefreshTasks(backgroundScope)
        var wroteIndex = false
        val waiter = async {
            tasks.refresh(1) {
                CompletableDeferred<Unit>().await()
                wroteIndex = true
                ConnectionLibraryRefreshResult(1, 1)
            }
        }
        runCurrent()
        tasks.cancel(1)
        runCurrent()
        assertTrue(waiter.isCancelled)
        assertFalse(wroteIndex)
        assertTrue(tasks.activeIds.value.isEmpty())
    }

    @Test
    fun `leaving screen cancels waiter but scan completes and reentry shares task`() = runTest {
        val tasks = LocalLibraryRefreshTasks(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var scans = 0
        val waiter = async {
            tasks.refresh(1) {
                scans++
                gate.await()
                ConnectionLibraryRefreshResult(7, 1)
            }
        }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals(setOf(1L), tasks.activeIds.value)
        val reentry = async { tasks.refresh(1) { error("Must reuse running scan") } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(7, reentry.await().getOrThrow().itemCount)
        assertEquals(1, scans)
        assertTrue(tasks.activeIds.value.isEmpty())
    }

    @Test
    fun `failures release task for retry and different libraries remain independent`() = runTest {
        val tasks = LocalLibraryRefreshTasks(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val first = async {
            tasks.refresh(1) {
                gate.await()
                error("Unavailable")
            }
        }
        val second = async { tasks.refresh(2) { ConnectionLibraryRefreshResult(2, 1) } }
        runCurrent()
        assertTrue(second.await().isSuccess)
        assertEquals(setOf(1L), tasks.activeIds.value)
        gate.complete(Unit)
        runCurrent()
        assertTrue(first.await().isFailure)
        val retry = async { tasks.refresh(1) { ConnectionLibraryRefreshResult(1, 2) } }
        runCurrent()
        assertFalse(retry.await().isFailure)
        assertTrue(tasks.activeIds.value.isEmpty())
    }
}
