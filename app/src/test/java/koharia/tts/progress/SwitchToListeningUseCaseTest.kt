package koharia.tts.progress

import io.kotest.matchers.shouldBe
import koharia.tts.Sentence
import org.junit.jupiter.api.Test

class SwitchToListeningUseCaseTest {

    private fun ref(index: Int, endOffset: Int): TtsProgressNotifier.SentenceRef =
        TtsProgressNotifier.SentenceRef(index, if (index == 0) 0 else endOffset - 5, endOffset)

    private fun fullSentence(index: Int, endOffset: Int, text: String? = null): Sentence {
        val startOff = if (index == 0) 0 else endOffset - 5
        val len = endOffset - startOff
        // Sentence 要求 text.length == endOffset - startOffset；测试用左对齐占位
        val resolvedText: String = if (text != null) text.padEnd(len, ' ').take(len) else "x".repeat(len)
        return Sentence(
            chapterHref = "ch1",
            index = index,
            startOffset = startOff,
            endOffset = endOffset,
            text = resolvedText,
        )
    }

    @Test
    fun `returns 0_0 for empty sentence list`() {
        val useCase = SwitchToListeningUseCase()
        useCase(emptyList<TtsProgressNotifier.SentenceRef>(), 0) shouldBe 0.0
        useCase(emptyList<TtsProgressNotifier.SentenceRef>(), 5) shouldBe 0.0
    }

    @Test
    fun `fractionAt from sentence refs - first sentence at 25 percent`() {
        val useCase = SwitchToListeningUseCase()
        // ends = [10, 20, 40]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 0) shouldBe 0.25
    }

    @Test
    fun `fractionAt clamps to 1_0 at last sentence`() {
        val useCase = SwitchToListeningUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 2) shouldBe 1.0
    }

    @Test
    fun `fromSentences accepts full Sentence objects via overload`() {
        val useCase = SwitchToListeningUseCase()
        val sents = listOf(
            fullSentence(0, 10, "First."),
            fullSentence(1, 20, "Second."),
            fullSentence(2, 40, "Third long sentence."),
        )

        useCase.fromSentences(sents, 1) shouldBe 0.5
    }

    @Test
    fun `fractionAt handles single sentence correctly`() {
        val useCase = SwitchToListeningUseCase()
        val sents = listOf(ref(0, 100))

        useCase(sents, 0) shouldBe 1.0
    }

    @Test
    fun `fractionAt clamps out-of-range index to last sentence`() {
        val useCase = SwitchToListeningUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20))

        useCase(sents, 99) shouldBe 1.0
    }

    @Test
    fun `fractionAt stays in 0_0 to 1_0 range even with degenerate inputs`() {
        val useCase = SwitchToListeningUseCase()
        // ends = [0] → degenerate; should return 0.0 not NaN
        useCase(listOf(ref(0, 0)), 0) shouldBe 0.0
    }

    @Test
    fun `fractionAt is monotonic across indices`() {
        val useCase = SwitchToListeningUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 30), ref(3, 40))

        val fractions = sents.indices.map { useCase(sents, it) }
        for (i in 1 until fractions.size) {
            (fractions[i] >= fractions[i - 1]) shouldBe true
        }
    }
}
