package koharia.epub.replacement

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReplacementNavigationTest {
    @Test
    fun `manual navigation discards a delayed mapping even if its read ignores cancellation`() = runTest {
        val navigation = ReplacementNavigation()
        val read = CompletableDeferred<Unit>()
        var jumps = 0
        navigation.launch(this, prepare = {
            withContext(NonCancellable) { read.await() }
            true
        }, navigate = { jumps++ })
        runCurrent()
        navigation.cancel()
        read.complete(Unit)
        runCurrent()
        assertEquals(0, jumps)
    }

    @Test
    fun `only the latest requested destination is committed`() = runTest {
        val navigation = ReplacementNavigation()
        val firstRead = CompletableDeferred<Unit>()
        val destinations = mutableListOf<String>()
        navigation.launch(this, prepare = {
            firstRead.await()
            true
        }, navigate = { destinations += "old" })
        runCurrent()
        navigation.launch(this, prepare = { true }, navigate = { destinations += "new" })
        firstRead.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), destinations)
        navigation.launch(this, prepare = { false }, navigate = { destinations += "closed-session" })
        runCurrent()
        assertEquals(listOf("new"), destinations)
    }
}
