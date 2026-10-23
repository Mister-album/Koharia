package koharia.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList

class DetailsLoadTraceTest {
    @Test
    fun `parallel suspended stages keep separate correlation and restore context`() = runBlocking {
        val logs = CopyOnWriteArrayList<String>()
        val trace = DetailsLoadTrace(enabled = true, sink = logs::add)
        val ready = CompletableDeferred<Unit>()
        val other = CompletableDeferred<String>()
        val first = async {
            trace.measure("metadata") {
                val before = DetailsLoadTrace.requestCorrelation()
                ready.complete(Unit)
                assertNotEquals(before, other.await())
                withContext(Dispatchers.Default) {
                    assertEquals(before, DetailsLoadTrace.requestCorrelation())
                }
                before
            }
        }
        val second = async {
            ready.await()
            trace.measure("chapters") { other.complete(DetailsLoadTrace.requestCorrelation()) }
        }
        assertTrue(first.await().contains("stage=metadata"))
        second.await()
        assertEquals("details=none", DetailsLoadTrace.requestCorrelation())
        assertEquals(2, logs.count { "outcome=ok" in it })
    }

    @Test
    fun `failure and cancellation retain original throwable without logging messages`() = runBlocking {
        val logs = mutableListOf<String>()
        val trace = DetailsLoadTrace(enabled = true, sink = logs::add)
        for (error in listOf(IllegalStateException("private-content"), CancellationException("private-content"))) {
            val result = runCatching { trace.measure("fetch") { throw error } }
            val actual = result.exceptionOrNull()!!
            assertEquals(error.javaClass, actual.javaClass)
            assertEquals(error.message, actual.message)
            // Coroutine stack-trace recovery may copy an exception and retain the original as its cause.
            assertTrue(generateSequence(actual) { it.cause }.any { it === error })
            assertEquals("details=none", DetailsLoadTrace.requestCorrelation())
        }
        assertTrue(logs.any { "outcome=error" in it })
        assertTrue(logs.any { "outcome=canceled" in it })
        assertFalse(logs.any { "private-content" in it })
    }

    @Test
    fun `disabled diagnostics and broken sink do not change result`() = runBlocking {
        val disabled =
            DetailsLoadTrace(enabled = false, clock = { error("must not read clock") }, sink = { error("disabled") })
        disabled.mark("open")
        assertEquals(42, disabled.measure("fetch") { 42 })
        val broken = DetailsLoadTrace(enabled = true, sink = { error("sink failed") })
        assertEquals(42, broken.measure("fetch") { 42 })
        assertEquals("details=none", DetailsLoadTrace.requestCorrelation())
    }
}
