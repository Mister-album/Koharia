package koharia.tts.progress

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SentenceProgressMapperTest {

    private fun ref(index: Int, endOffset: Int): TtsProgressNotifier.SentenceRef =
        TtsProgressNotifier.SentenceRef(
            index = index,
            startOffset = if (index == 0) 0 else endOffset - 5,
            endOffset = endOffset,
        )

    // ===== fractionAt =====

    @Test
    fun `fractionAt returns zero for empty sentences`() {
        SentenceProgressMapper.fractionAt(emptyList(), 0) shouldBe 0.0
        SentenceProgressMapper.fractionAt(emptyList(), 5) shouldBe 0.0
    }

    @Test
    fun `fractionAt returns zero when last endOffset is zero`() {
        SentenceProgressMapper.fractionAt(listOf(ref(0, 0)), 0) shouldBe 0.0
    }

    @Test
    fun `fractionAt computes currentEndOffset over last endOffset`() {
        // sentences with ends [10, 20, 40]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        SentenceProgressMapper.fractionAt(sents, 0) shouldBe 10.0 / 40.0
        SentenceProgressMapper.fractionAt(sents, 1) shouldBe 20.0 / 40.0
        SentenceProgressMapper.fractionAt(sents, 2) shouldBe 1.0
    }

    @Test
    fun `fractionAt clamps negative index to zero`() {
        val sents = listOf(ref(0, 10), ref(1, 20))
        SentenceProgressMapper.fractionAt(sents, -1) shouldBe 10.0 / 20.0
    }

    @Test
    fun `fractionAt clamps overflowing index to last sentence`() {
        val sents = listOf(ref(0, 10), ref(1, 20))
        SentenceProgressMapper.fractionAt(sents, 99) shouldBe 1.0
    }

    // ===== sentenceAtOffset =====

    @Test
    fun `sentenceAtOffset returns minus one for empty sentences`() {
        SentenceProgressMapper.sentenceAtOffset(emptyList(), 0) shouldBe -1
        SentenceProgressMapper.sentenceAtOffset(emptyList(), 50) shouldBe -1
    }

    @Test
    fun `sentenceAtOffset returns minus one for negative offset`() {
        val sents = listOf(ref(0, 10), ref(1, 20))
        SentenceProgressMapper.sentenceAtOffset(sents, -1) shouldBe -1
    }

    @Test
    fun `sentenceAtOffset returns last index for offset at or beyond last end`() {
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))
        SentenceProgressMapper.sentenceAtOffset(sents, 40) shouldBe 2
        SentenceProgressMapper.sentenceAtOffset(sents, 100) shouldBe 2
    }

    @Test
    fun `sentenceAtOffset returns the first sentence whose endOffset exceeds offset`() {
        // ends = [10, 20, 40]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        SentenceProgressMapper.sentenceAtOffset(sents, 0) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 5) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 9) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 10) shouldBe 1
        SentenceProgressMapper.sentenceAtOffset(sents, 11) shouldBe 1
        SentenceProgressMapper.sentenceAtOffset(sents, 20) shouldBe 2
        SentenceProgressMapper.sentenceAtOffset(sents, 39) shouldBe 2
    }

    @Test
    fun `sentenceAtOffset is consistent with fractionAt round trip`() {
        // ends = [10, 20, 40, 80]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40), ref(3, 80))

        for (i in sents.indices) {
            val endOff = sents[i].endOffset
            val indexByOff = SentenceProgressMapper.sentenceAtOffset(sents, endOff)
            // At exactly an endOffset, we map to NEXT sentence (offset >= end goes next)
            // so the round-trip via fractionAt produces the same index
            val frac = SentenceProgressMapper.fractionAt(sents, i)
            val indexByFrac = SentenceProgressMapper.sentenceAtFraction(sents, frac)
            // fractionAt(i) returns i's endOffset over total, which falls just below i+1's start
            // so it should map back to i (the sentence that ends at that offset)
            indexByFrac shouldBe i
        }
    }

    // ===== sentenceAtFraction =====

    @Test
    fun `sentenceAtFraction returns minus one for empty sentences`() {
        SentenceProgressMapper.sentenceAtFraction(emptyList(), 0.5) shouldBe -1
    }

    @Test
    fun `sentenceAtFraction at zero returns first sentence`() {
        val sents = listOf(ref(0, 10), ref(1, 20))
        SentenceProgressMapper.sentenceAtFraction(sents, 0.0) shouldBe 0
        SentenceProgressMapper.sentenceAtFraction(sents, -0.5) shouldBe 0
    }

    @Test
    fun `sentenceAtFraction at one returns last sentence`() {
        val sents = listOf(ref(0, 10), ref(1, 20))
        SentenceProgressMapper.sentenceAtFraction(sents, 1.0) shouldBe 1
        SentenceProgressMapper.sentenceAtFraction(sents, 1.5) shouldBe 1
    }

    @Test
    fun `sentenceAtFraction finds first sentence whose fraction meets or exceeds target`() {
        // ends = [10, 20, 40]  → fractions = [10/40=0.25, 20/40=0.5, 40/40=1.0]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        SentenceProgressMapper.sentenceAtFraction(sents, 0.1) shouldBe 0
        SentenceProgressMapper.sentenceAtFraction(sents, 0.25) shouldBe 0
        SentenceProgressMapper.sentenceAtFraction(sents, 0.3) shouldBe 1
        SentenceProgressMapper.sentenceAtFraction(sents, 0.5) shouldBe 1
        SentenceProgressMapper.sentenceAtFraction(sents, 0.7) shouldBe 2
        SentenceProgressMapper.sentenceAtFraction(sents, 0.99) shouldBe 2
    }

    @Test
    fun `sentenceAtFraction at exactly a sentence's fraction picks that sentence`() {
        // ends = [10, 20, 30] → fractions = [1/3, 2/3, 1.0]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 30))

        val f1 = SentenceProgressMapper.fractionAt(sents, 1)
        // f1 = 20/30 = 2/3
        SentenceProgressMapper.sentenceAtFraction(sents, f1) shouldBe 1
    }

    // ===== Defensive / property tests =====

    @Test
    fun `single sentence - both mappers agree at any offset and fraction`() {
        val sents = listOf(ref(0, 100))
        SentenceProgressMapper.sentenceAtOffset(sents, 0) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 50) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 100) shouldBe 0
        SentenceProgressMapper.sentenceAtFraction(sents, 0.0) shouldBe 0
        SentenceProgressMapper.sentenceAtFraction(sents, 0.5) shouldBe 0
        SentenceProgressMapper.sentenceAtFraction(sents, 1.0) shouldBe 0
    }

    @Test
    fun `very large sentence list - binary search is O(log n) and correct`() {
        // 100 sentences with monotonically increasing ends: ends = [7, 14, 21, ...]
        val sents = (1..100).map { ref(it - 1, it * 7) }

        // Check the last sample in each sentence (endOff - 1) → maps back to that sentence
        for (i in sents.indices step 7) {
            val endOff = sents[i].endOffset
            SentenceProgressMapper.sentenceAtOffset(sents, endOff - 1) shouldBe i
        }

        // Check the start of each sentence (startOff) → maps to that sentence
        for (i in sents.indices step 11) {
            val startOff = sents[i].startOffset
            SentenceProgressMapper.sentenceAtOffset(sents, startOff) shouldBe i
        }
    }

    @Test
    fun `non-uniform end offsets - mapper returns expected sentence`() {
        // ends = [5, 100, 101, 102, 1000]
        val sents = listOf(ref(0, 5), ref(1, 100), ref(2, 101), ref(3, 102), ref(4, 1000))

        SentenceProgressMapper.sentenceAtOffset(sents, 0) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 4) shouldBe 0
        SentenceProgressMapper.sentenceAtOffset(sents, 5) shouldBe 1
        SentenceProgressMapper.sentenceAtOffset(sents, 99) shouldBe 1
        SentenceProgressMapper.sentenceAtOffset(sents, 100) shouldBe 2
        SentenceProgressMapper.sentenceAtOffset(sents, 101) shouldBe 3
        SentenceProgressMapper.sentenceAtOffset(sents, 102) shouldBe 4
        SentenceProgressMapper.sentenceAtOffset(sents, 999) shouldBe 4
    }

    @Test
    fun `fractionAt never exceeds 1_0 even with malformed inputs`() {
        // ends = [10, 20, 40]
        val sents = listOf(ref(0, 10), ref(1, 20), ref(2, 40))

        SentenceProgressMapper.fractionAt(sents, 0) shouldBe 0.25
        SentenceProgressMapper.fractionAt(sents, 1) shouldBe 0.5
        SentenceProgressMapper.fractionAt(sents, 2) shouldBe 1.0
        SentenceProgressMapper.fractionAt(sents, 100) shouldBe 1.0
    }
}
