package koharia.lanraragi

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import koharia.domain.lanraragi.LanraragiEntry
import koharia.domain.lanraragi.LanraragiReadState
import koharia.domain.lanraragi.LanraragiRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class LanraragiProgressSyncTest {
    @Test
    fun `opening a saved page does not overwrite a different remote position`() = runTest {
        sync(listOf(local.copy(initialPage = true)), remote.copy(lastRead = 10))
        assertTrue(writes.isEmpty())
        assertEquals(7, applied.single().pageIndex)
    }

    @Test
    fun `first display of unread single page archive is uploaded and clears new`() = runTest {
        sync(
            listOf(local.copy(pageIndex = 0, totalPages = 1, initialPage = true)),
            remote.copy(
                pageCount = 1,
                progress = 0,
                lastRead = 0,
                isNew = true,
            ),
        )
        assertEquals(listOf(1), writes)
        assertEquals(listOf("archive"), cleared)
        assertFalse(applied.single().pending)
    }

    @Test
    fun `reading persists during slow fetch and stale response cannot acknowledge it`() = runTest {
        val mutex = Mutex()
        var current = local.copy(revision = 1)
        coEvery { repository.readStates(7) } answers { listOf(current) }
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val syncJob = launch {
            synchronizeLanraragiProgress(7, repository, {
                requested.complete(Unit)
                response.await()
                remote
            }, { _, page -> writes += page }, { cleared += it }, { applied += it }, mutex)
        }
        requested.await()
        mutex.withLock { current = current.copy(pageIndex = 6, revision = 2, readAt = 400) }
        response.complete(Unit)
        syncJob.join()
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { repository.record(any(), any()) }
        assertTrue(current.pending)
        assertEquals(6, current.pageIndex)
    }

    @Test
    fun `unread chosen during upload is not overwritten by upload completion`() = runTest {
        val mutex = Mutex()
        var current = local.copy(revision = 1)
        coEvery { repository.readStates(7) } answers { listOf(current) }
        val uploaded = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val syncJob = launch {
            synchronizeLanraragiProgress(7, repository, { remote }, { _, _ ->
                uploaded.complete(Unit)
                finish.await()
            }, { cleared += it }, { applied += it }, mutex)
        }
        uploaded.await()
        mutex.withLock { current = current.copy(localUnread = true, pending = false, revision = 2) }
        finish.complete(Unit)
        syncJob.join()
        assertTrue(applied.isEmpty())
        assertTrue(cleared.isEmpty())
        coVerify(exactly = 0) { repository.record(any(), any()) }
    }

    private val repository = mockk<LanraragiRepository>(relaxed = true)
    private val local = LanraragiReadState("archive", 2, 10, 200)
    private val remote = LanraragiEntry("archive", title = "Book", pageCount = 10, progress = 8, lastRead = 100)
    private val writes = mutableListOf<Int>()
    private val cleared = mutableListOf<String>()
    private val applied = mutableListOf<LanraragiReadState>()

    private suspend fun sync(
        states: List<LanraragiReadState>,
        snapshot: LanraragiEntry = remote,
        push: suspend (String, Int) -> Unit = { _, page -> writes += page },
        clear: suspend (String) -> Unit = { cleared += it },
    ) {
        coEvery { repository.readStates(7) } returns states
        synchronizeLanraragiProgress(7, repository, { snapshot }, push, clear, { applied += it })
    }

    @Test
    fun `newer offline rereading can move backwards and uses one based page`() = runTest {
        sync(listOf(local))
        assertEquals(listOf(3), writes)
        assertEquals(2, applied.single().pageIndex)
        assertFalse(applied.single().pending)
        coVerify(exactly = 1) { repository.record(7, any()) }
        coVerify(exactly = 0) { repository.record(8, any()) }
    }

    @Test
    fun `newer remote activity wins without uploading stale offline position`() = runTest {
        sync(listOf(local), remote.copy(lastRead = 300))
        assertTrue(writes.isEmpty())
        assertEquals(7, applied.single().pageIndex)
        assertEquals(300L, applied.single().readAt)
    }

    @Test
    fun `same location acknowledges without changing server timestamp`() = runTest {
        sync(listOf(local), remote.copy(progress = 3))
        assertTrue(writes.isEmpty())
        assertFalse(applied.single().pending)
    }

    @Test
    fun `network failure keeps pending event durable`() {
        assertThrows(IOException::class.java) {
            runTest { sync(listOf(local), push = { _, _ -> throw IOException("offline") }) }
        }
        coVerify(exactly = 0) { repository.record(any(), any()) }
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `unread overrides and acknowledged history never generate writes`() = runTest {
        sync(listOf(local.copy(localUnread = true), local.copy(pending = false)), remote.copy(isNew = true))
        assertTrue(writes.isEmpty())
        assertTrue(cleared.isEmpty())
        coVerify(exactly = 0) { repository.record(any(), any()) }
    }

    @Test
    fun `marking completed before pagecount is known waits for valid server metadata`() = runTest {
        sync(listOf(local.copy(totalPages = 0)), remote.copy(pageCount = 0))
        assertTrue(writes.isEmpty())
        coVerify(exactly = 0) { repository.record(any(), any()) }
        sync(listOf(local.copy(totalPages = 0)))
        assertEquals(listOf(10), writes)
        assertEquals(9, applied.single().pageIndex)
    }

    @Test
    fun `clear new retry does not write progress twice`() = runTest {
        val snapshot = remote.copy(isNew = true)
        var failed = false
        try {
            sync(listOf(local), snapshot, clear = { throw IOException("interrupted") })
        } catch (
            _: IOException,
        ) {
            failed =
                true
        }
        assertTrue(failed)
        coVerify(exactly = 0) { repository.record(any(), any()) }
        sync(listOf(local), snapshot.copy(progress = 3, lastRead = 300))
        assertEquals(listOf(3), writes)
        assertEquals(listOf("archive"), cleared)
        assertFalse(applied.single().pending)
    }

    @Test
    fun `never read single page archive is not completed`() {
        val state = remote.copy(pageCount = 1, progress = 0, lastRead = 0).toReadState()
        assertEquals(-1, state.pageIndex)
        assertFalse(state.pending)
    }

    @Test
    fun `late display callbacks cannot replace newer reading or explicit unread`() {
        assertFalse(acceptsLocalReading(local, 100))
        assertFalse(acceptsLocalReading(local.copy(localUnread = true, pending = false), 100))
        assertTrue(acceptsLocalReading(local, 300))
        assertTrue(acceptsLocalReading(local.copy(pending = false), 100))
    }
}
