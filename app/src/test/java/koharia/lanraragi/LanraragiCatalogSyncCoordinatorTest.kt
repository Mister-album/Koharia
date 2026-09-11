package koharia.lanraragi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanraragiCatalogSyncCoordinatorTest {
    @Test
    fun `another instance cannot stage data while the same connection publishes`() = runTest {
        val coordinator = LanraragiCatalogSyncCoordinator()
        val archiveStaged = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val rows = mutableMapOf<Long, MutableList<String>>()
        var secondStarted = false
        val first = launch {
            coordinator.mutexFor(10).withLock {
                rows[1] = mutableListOf("archive")
                archiveStaged.complete(Unit)
                finishFirst.await()
                rows.getValue(1).add("category")
                rows.keys.removeAll { it != 1L }
            }
        }
        archiveStaged.await()
        val second = launch {
            coordinator.mutexFor(10).withLock {
                secondStarted = true
                rows[2] = mutableListOf("archive", "category")
                rows.keys.removeAll { it != 2L }
            }
        }
        runCurrent()
        assertFalse(secondStarted)
        finishFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("archive", "category"), rows.getValue(2))
    }

    @Test
    fun `different connections can refresh independently`() = runTest {
        val coordinator = LanraragiCatalogSyncCoordinator()
        coordinator.mutexFor(1).withLock { assertTrue(coordinator.mutexFor(2).tryLock()) }
        coordinator.mutexFor(2).unlock()
    }
}
