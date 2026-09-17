package koharia.tts.progress

import io.kotest.matchers.shouldBe
import koharia.tts.Sentence
import org.junit.jupiter.api.Test

class SwitchToReadingUseCaseTest {

    private fun ref(index: Int, endOffset: Int): TtsProgressNotifier.SentenceRef =
        TtsProgressNotifier.SentenceRef(index, if (index == 0) 0 else endOffset - 5, endOffset)

    private fun fullSentence(index: Int, endOffset: Int): Sentence {
        val startOff = if (index == 0) 0 else endOffset - 5
        val len = endOffset - startOff
        return Sentence("ch1", index, startOff, endOffset, "x".repeat(len))
    }

    // ===== byFraction =====

    @Test
    fun `returns minus one for empty list - byFraction`() {
        SwitchToReadingUseCase()(emptyList<TtsProgressNotifier.SentenceRef>(), 0.5) shouldBe -1
    }

    @Test
    fun `fraction 0_0 maps to first sentence`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 0.0) shouldBe 0
    }

    @Test
    fun `fraction 1_0 maps to last sentence`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 1.0) shouldBe 2
    }

    @Test
    fun `fraction 0_25 maps to first sentence - sentence 0 covers 10 of 40`() {
        val useCase = SwitchToReadingUseCase()
        // ends = [10, 20, 40] → fractions = [0.25, 0.5, 1.0]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 0.25) shouldBe 0
        useCase(sents, 0.3) shouldBe 1 // 0.3 first crosses 0.5 → sentence 1
        useCase(sents, 0.5) shouldBe 1
        useCase(sents, 0.7) shouldBe 2
    }

    @Test
    fun `fraction above 1_0 clamps to last sentence`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20))

        useCase(sents, 1.5) shouldBe 1
    }

    @Test
    fun `fraction below 0_0 clamps to first sentence`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20))

        useCase(sents, -0.5) shouldBe 0
    }

    @Test
    fun `byFraction accepts full Sentence objects via overload`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(
            fullSentence(0, 10),
            fullSentence(1, 20),
            fullSentence(2, 40),
        )

        useCase.byFraction(sents, 0.5) shouldBe 1
    }

    // ===== byOffset =====

    @Test
    fun `returns minus one for empty list - byOffset`() {
        SwitchToReadingUseCase()(emptyList<TtsProgressNotifier.SentenceRef>(), 0) shouldBe -1
    }

    @Test
    fun `negative offset returns minus one`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20))

        useCase(sents, -1) shouldBe -1
    }

    @Test
    fun `offset 0 maps to first sentence`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20))

        useCase(sents, 0) shouldBe 0
    }

    @Test
    fun `offset just before sentence end maps to that sentence`() {
        val useCase = SwitchToReadingUseCase()
        // ends = [10, 20, 40]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 9) shouldBe 0
        useCase(sents, 19) shouldBe 1
        useCase(sents, 39) shouldBe 2
    }

    @Test
    fun `offset at sentence boundary maps to next sentence`() {
        val useCase = SwitchToReadingUseCase()
        // ends = [10, 20, 40]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 10) shouldBe 1
        useCase(sents, 20) shouldBe 2
    }

    @Test
    fun `offset beyond last end maps to last sentence`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        useCase(sents, 40) shouldBe 2
        useCase(sents, 999) shouldBe 2
    }

    @Test
    fun `byOffset accepts full Sentence objects via overload`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(
            fullSentence(0, 10),
            fullSentence(1, 20),
            fullSentence(2, 40),
        )

        useCase.byOffset(sents, 15) shouldBe 1
    }

    // ===== Round-trip property tests =====

    @Test
    fun `round-trip - sentence to fraction to sentence returns same index`() {
        val useCaseListen = SwitchToListeningUseCase()
        val useCaseRead = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        for (i in sents.indices) {
            val frac = useCaseListen(sents, i)
            val back = useCaseRead(sents, frac)
            back shouldBe i
        }
    }

    @Test
    fun `round-trip - offset to sentence to currentEndOffset recovers end offset`() {
        val useCase = SwitchToReadingUseCase()
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        for (i in sents.indices) {
            // offset at the end of sentence i - 1 maps to sentence i
            val offsetAtEnd = sents[i].endOffset
            val index = useCase(sents, offsetAtEnd)
            // offsetAtEnd goes to sentence i+1 (except at last where it stays at i)
            val expected = if (i == sents.lastIndex) i else i + 1
            index shouldBe expected
        }
    }
}
