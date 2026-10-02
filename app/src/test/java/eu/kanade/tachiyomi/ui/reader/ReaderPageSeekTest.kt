package eu.kanade.tachiyomi.ui.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPageSeekTest {
    @Test
    fun `holding on a page loads after 100 milliseconds without release`() = runTest {
        val pages = mutableListOf<Int>()
        val previews = mutableListOf<Int>()
        val seek = ReaderPageSeek(this)
        seek.update(4, preview = { previews += 4 }) { pages += 4 }
        previews shouldBe listOf(4)
        advanceTimeBy(99)
        pages shouldBe emptyList()
        advanceTimeBy(1)
        runCurrent()
        pages shouldBe listOf(4)
    }

    @Test
    fun `moving across pages cancels intermediate loads`() = runTest {
        val pages = mutableListOf<Int>()
        val seek = ReaderPageSeek(this)
        seek.update(4) { pages += 4 }
        advanceTimeBy(60)
        seek.update(8) { pages += 8 }
        advanceTimeBy(60)
        pages shouldBe emptyList()
        advanceUntilIdle()
        pages shouldBe listOf(8)
    }

    @Test
    fun `movement within the same page does not restart the timer`() = runTest {
        val pages = mutableListOf<Int>()
        val seek = ReaderPageSeek(this)
        seek.update(4) { pages += 4 }
        advanceTimeBy(60)
        seek.update(4) { pages += 4 }
        advanceTimeBy(40)
        runCurrent()
        pages shouldBe listOf(4)
        seek.finish(4) { pages += 4 }
        advanceUntilIdle()
        pages shouldBe listOf(4)
    }

    @Test
    fun `release immediately loads the final page and cancels the pending load`() = runTest {
        val pages = mutableListOf<Int>()
        val seek = ReaderPageSeek(this)
        seek.update(4) { pages += 4 }
        advanceTimeBy(20)
        seek.finish(8) { pages += 8 }
        pages shouldBe listOf(8)
        advanceUntilIdle()
        pages shouldBe listOf(8)
        seek.update(8) { pages += 8 }
        advanceUntilIdle()
        pages shouldBe listOf(8, 8)
    }

    @Test
    fun `leaving the chapter cancels pending navigation and resets the target`() = runTest {
        val pages = mutableListOf<Int>()
        val seek = ReaderPageSeek(this)
        seek.update(4) { pages += 4 }
        advanceTimeBy(50)
        seek.cancel()
        advanceUntilIdle()
        pages shouldBe emptyList()
        seek.update(4) { pages += 4 }
        advanceUntilIdle()
        pages shouldBe listOf(4)
    }

    @Test
    fun `returning to a previewed page starts a new network debounce`() = runTest {
        val pages = mutableListOf<Int>()
        val seek = ReaderPageSeek(this)
        seek.update(4) { pages += 4 }
        advanceUntilIdle()
        seek.update(8) { pages += 8 }
        advanceTimeBy(50)
        seek.update(4) { pages += 4 }
        advanceUntilIdle()
        seek.finish(4) { pages += 4 }
        pages shouldBe listOf(4, 4)
    }

    @Test
    fun `release at a new target previews immediately before allowing network`() = runTest {
        val events = mutableListOf<String>()
        val seek = ReaderPageSeek(this)
        seek.update(4, preview = { events += "preview 4" }) { events += "load 4" }
        advanceTimeBy(20)
        seek.finish(8, preview = { events += "preview 8" }) { events += "load 8" }
        events shouldBe listOf("preview 4", "preview 8", "load 8")
        advanceUntilIdle()
        events shouldBe listOf("preview 4", "preview 8", "load 8")
    }
}
