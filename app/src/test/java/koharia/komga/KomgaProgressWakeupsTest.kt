package koharia.komga

import androidx.work.WorkInfo
import koharia.domain.komga.KomgaReadStatusKey
import koharia.komga.progress.KomgaProgressWakeups
import koharia.komga.progress.KomgaProgressWorkBackend
import koharia.komga.progress.KomgaProgressWorkManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaProgressWakeupsTest {
    private val key = KomgaReadStatusKey(17, "private-owner", "book")
    private class Backend : KomgaProgressWorkBackend {
        val work = mutableMapOf<KomgaReadStatusKey, List<WorkInfo.State>>()
        var appended = 0
        var finishDuringAppend = false
        override suspend fun states(key: KomgaReadStatusKey) = work[key].orEmpty()
        override suspend fun append(key: KomgaReadStatusKey) {
            appended++
            val previous = if (finishDuringAppend) emptyList() else work[key].orEmpty()
            work[key] =
                previous + if (WorkInfo.State.RUNNING in previous) WorkInfo.State.BLOCKED else WorkInfo.State.ENQUEUED
        }
    }

    @Test
    fun `concurrent offline wakeups retain one queued task and running work retains one successor`() = runTest {
        val backend = Backend()
        val wakeups = KomgaProgressWakeups(backend)
        coroutineScope { (0..63).map { async { wakeups.wake(key) } }.awaitAll() }
        assertEquals(1, backend.appended)
        backend.work[key] = listOf(WorkInfo.State.RUNNING)
        coroutineScope { (0..63).map { async { wakeups.wake(key) } }.awaitAll() }
        assertEquals(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), backend.work[key])
        assertEquals(2, backend.appended)
    }

    @Test
    fun `worker completion between query and append still leaves a runnable wakeup`() = runTest {
        val backend = Backend().apply {
            work[key] = listOf(WorkInfo.State.RUNNING)
            finishDuringAppend = true
        }
        assertTrue(KomgaProgressWakeups(backend).wake(key))
        assertEquals(listOf(WorkInfo.State.ENQUEUED), backend.work[key])
    }

    @Test
    fun `terminal work does not prevent new work and owners never share names`() = runTest {
        val backend = Backend().apply { work[key] = listOf(WorkInfo.State.FAILED) }
        val wakeups = KomgaProgressWakeups(backend)
        assertTrue(wakeups.wake(key))
        assertTrue(wakeups.wake(key.copy(ownerKey = "other")))
        assertTrue(wakeups.wake(key.copy(connectionId = 18)))
        assertNotEquals(KomgaProgressWorkManager.name(key), KomgaProgressWorkManager.name(key.copy(ownerKey = "other")))
        assertNotEquals(KomgaProgressWorkManager.name(key), KomgaProgressWorkManager.name(key.copy(bookId = "another")))
        assertFalse(KomgaProgressWorkManager.name(key).contains(key.ownerKey))
    }
}
