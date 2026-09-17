package koharia.tts.progress

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TtsProgressNotifierTest {

    private fun makeSentences(vararg ends: Int): List<TtsProgressNotifier.SentenceRef> =
        ends.mapIndexed { i, end ->
            val start = if (i == 0) 0 else ends[i - 1]
            TtsProgressNotifier.SentenceRef(index = i, startOffset = start, endOffset = end)
        }

    @Test
    fun `initial state is empty and unbound`() {
        val n = TtsProgressNotifier()
        n.isBound shouldBe false
        n.progress.value.currentIndex shouldBe -1
        n.progress.value.sentences shouldHaveSize 0
    }

    @Test
    fun `bind with sentences sets chapter and resets currentIndex to zero`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 10, 20)

        n.bind("ch1", sents)

        n.isBound shouldBe true
        n.progress.value.chapterHref shouldBe "ch1"
        n.progress.value.sentences shouldHaveSize 3
        n.progress.value.currentIndex shouldBe 0
    }

    @Test
    fun `bind with empty sentences leaves currentIndex at minus one`() {
        val n = TtsProgressNotifier()
        n.bind("ch1", emptyList())

        n.isBound shouldBe false
        n.progress.value.currentIndex shouldBe -1
    }

    @Test
    fun `setCurrent updates the current index`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 10, 20)
        n.bind("ch1", sents)

        n.setCurrent(1)
        n.progress.value.currentIndex shouldBe 1

        n.setCurrent(2)
        n.progress.value.currentIndex shouldBe 2
    }

    @Test
    fun `setCurrent clamps negative to minus one sentinel and out-of-range positive to lastIndex`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 10, 20)
        n.bind("ch1", sents)

        // 负数 → -1（"未开始"哨兵，与 Progress.fraction 的零路径对齐）
        n.setCurrent(-5)
        n.progress.value.currentIndex shouldBe -1

        // 超过 lastIndex → lastIndex
        n.setCurrent(99)
        n.progress.value.currentIndex shouldBe 2

        // 合法值
        n.setCurrent(1)
        n.progress.value.currentIndex shouldBe 1
    }

    @Test
    fun `setCurrent is a no-op when currentIndex does not change`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 10, 20)
        n.bind("ch1", sents)
        n.setCurrent(1)
        // After setCurrent(1) we should be at 1; calling again with same value shouldn't crash
        n.setCurrent(1)
        n.progress.value.currentIndex shouldBe 1
    }

    @Test
    fun `setCurrent on empty sentences is a no-op`() {
        val n = TtsProgressNotifier()
        n.setCurrent(5) // should not throw
        n.progress.value.currentIndex shouldBe -1
        n.progress.value.sentences shouldHaveSize 0
    }

    @Test
    fun `clear resets to unbound empty state`() {
        val n = TtsProgressNotifier()
        n.bind("ch1", makeSentences(5, 10))
        n.setCurrent(1)

        n.clear()

        n.isBound shouldBe false
        n.progress.value.chapterHref shouldBe ""
        n.progress.value.currentIndex shouldBe -1
    }

    @Test
    fun `Progress currentStartOffset and currentEndOffset return sentence bounds`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 12, 25)
        n.bind("ch1", sents)

        n.setCurrent(1)
        n.progress.value.currentStartOffset shouldBe 5
        n.progress.value.currentEndOffset shouldBe 12

        n.setCurrent(0)
        n.progress.value.currentStartOffset shouldBe 0
        n.progress.value.currentEndOffset shouldBe 5
    }

    @Test
    fun `Progress fraction increases monotonically across sentences`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(10, 20, 40)
        n.bind("ch1", sents)

        n.setCurrent(0)
        val f0 = n.progress.value.fraction

        n.setCurrent(1)
        val f1 = n.progress.value.fraction

        n.setCurrent(2)
        val f2 = n.progress.value.fraction

        (f1 > f0) shouldBe true
        (f2 > f1) shouldBe true
        f2 shouldBe 1.0
    }

    @Test
    fun `Progress fraction is zero when no sentence is bound`() {
        val n = TtsProgressNotifier()
        n.progress.value.fraction shouldBe 0.0
    }

    @Test
    fun `Progress fraction is zero when currentIndex is minus one sentinel`() {
        // -1 是"未开始"哨兵；setCurrent(-1) 保留为 -1 → fraction 走 0.0 短路
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 10)
        n.bind("ch1", sents)
        n.setCurrent(-1)
        n.progress.value.currentIndex shouldBe -1
        n.progress.value.fraction shouldBe 0.0
    }

    @Test
    fun `setCurrent clamps out-of-range positive values to lastIndex`() {
        val n = TtsProgressNotifier()
        val sents = makeSentences(5, 10, 20)
        n.bind("ch1", sents)

        n.setCurrent(99)
        n.progress.value.currentIndex shouldBe 2
    }

    @Test
    fun `progress StateFlow emits on changes - bind then setCurrent then clear`() = runTest {
        val n = TtsProgressNotifier()

        n.progress.first().let { it.sentences shouldHaveSize 0 }

        n.bind("ch1", makeSentences(5, 10))
        n.progress.first().let { it.sentences shouldHaveSize 2 }

        n.setCurrent(1)
        n.progress.first().currentIndex shouldBe 1

        n.clear()
        n.progress.first().sentences shouldHaveSize 0
    }

    @Test
    fun `progress StateFlow does not emit when setCurrent no-ops`() = runTest {
        val n = TtsProgressNotifier()
        n.bind("ch1", makeSentences(5, 10))
        n.setCurrent(1)

        // 拿一次当前值（应该就是 index=1）
        n.progress.first().currentIndex shouldBe 1

        // 再调用 setCurrent(1) 是 no-op；StateFlow.value 应该仍是 1
        n.setCurrent(1)
        n.progress.value.currentIndex shouldBe 1
    }

    @Test
    fun `rebind replaces previous sentences and resets current index`() {
        val n = TtsProgressNotifier()
        n.bind("ch1", makeSentences(5, 10))
        n.setCurrent(1)

        n.bind("ch2", makeSentences(7, 14, 21))

        n.progress.value.chapterHref shouldBe "ch2"
        n.progress.value.sentences shouldHaveSize 3
        n.progress.value.currentIndex shouldBe 0
    }

    @Test
    fun `rebind clears currentIndex when new chapter has no sentences`() {
        val n = TtsProgressNotifier()
        n.bind("ch1", makeSentences(5, 10))
        n.setCurrent(1)

        n.bind("ch2", emptyList())

        n.progress.value.chapterHref shouldBe "ch2"
        n.progress.value.currentIndex shouldBe -1
    }
}
