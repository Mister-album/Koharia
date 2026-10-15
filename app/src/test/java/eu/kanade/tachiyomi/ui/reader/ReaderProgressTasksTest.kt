package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.CoroutineContext

class ReaderProgressTasksTest {
    private class ReversedIo : CoroutineDispatcher() {
        private val ready = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ready.addLast(block)
        }
        fun drain() {
            while (ready.isNotEmpty()) ready.removeLast().run()
        }
    }

    @Test
    fun `slow old save cannot finish after later forward or backward reading`() = runBlocking {
        val io = ReversedIo()
        val scope = CoroutineScope(SupervisorJob())
        val tasks = ReaderProgressTasks(scope, io)
        val oldWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        var savedPage = -1
        val old = tasks.submit {
            writes += "provider:3"
            oldWrite.await()
            savedPage = 3
            writes += "chapter:3"
        }
        val forward = tasks.submit {
            writes += "provider:8"
            savedPage = 8
            writes += "chapter:8"
        }
        val backward = tasks.submit {
            writes += "provider:2"
            savedPage = 2
            writes += "chapter:2"
        }
        io.drain()
        assertEquals(listOf("provider:3"), writes)
        assertEquals(-1, savedPage)
        oldWrite.complete(Unit)
        io.drain()
        listOf(old, forward, backward).forEach { it.await() }
        assertEquals(listOf("provider:3", "chapter:3", "provider:8", "chapter:8", "provider:2", "chapter:2"), writes)
        assertEquals(2, savedPage)
        scope.cancel()
    }

    @Test
    fun `rejected owner does not cancel the following chapter save`() = runBlocking {
        val io = ReversedIo()
        val scope = CoroutineScope(SupervisorJob())
        val tasks = ReaderProgressTasks(scope, io)
        val rejected = tasks.submit { throw CancellationException("Old owner") }
        val current = tasks.submit { "new chapter saved" }
        io.drain()
        assertTrue(runCatching { rejected.await() }.exceptionOrNull() is CancellationException)
        assertEquals("new chapter saved", current.await())
        scope.cancel()
    }

    @Test
    fun `accepted persistence survives reader scope cancellation and history follows pending saves`() = runBlocking {
        val io = ReversedIo()
        val scope = CoroutineScope(SupervisorJob())
        val tasks = ReaderProgressTasks(scope, io)
        var page = 0
        tasks.submit { page = 7 }
        val history = tasks.submit { page }
        scope.cancel()
        io.drain()
        assertEquals(7, history.await())
    }
}
