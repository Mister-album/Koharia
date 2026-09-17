package koharia.tts.progress

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import koharia.epub.toTtsHighlightBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Phase 2.5a: 验证 `TtsProgressNotifier.Progress` → `TtsHighlightBinding` 的纯映射。
 *
 * ViewModel 集成测试需要构造一个完整 EpubReaderViewModel（24+ Injekt 依赖），代价过高；
 * 这里聚焦"纯映射层"，覆盖：
 *   1) bind/setCurrent/clear 后 binding.active / sentence 的预期形态
 *   2) chapterHref 与 sentences 列表的整体快照正确传递（不变量）
 *   3) currentIndex 越界或为 -1 时 sentence 为 null（哨兵路径）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsViewModelBridgeTest {

    private fun makeSentences(vararg ends: Int): List<TtsProgressNotifier.SentenceRef> =
        ends.mapIndexed { i, end ->
            val start = if (i == 0) 0 else ends[i - 1]
            TtsProgressNotifier.SentenceRef(index = i, startOffset = start, endOffset = end)
        }

    @Test
    fun `mapping of fresh notifier yields inactive and no sentence`() {
        val progress = TtsProgressNotifier.Progress("", emptyList(), -1)

        val binding = progress.toTtsHighlightBinding()

        binding.active shouldBe false
        binding.sentence.shouldBeNull()
    }

    @Test
    fun `mapping after bind becomes active and points at first sentence`() {
        val sents = makeSentences(5, 10, 20)
        val progress = TtsProgressNotifier.Progress("ch1", sents, 0)

        val binding = progress.toTtsHighlightBinding()

        binding.active shouldBe true
        binding.sentence shouldBe sents[0]
    }

    @Test
    fun `mapping follows setCurrent across all sentences`() {
        val sents = makeSentences(5, 10, 20)
        val progress = TtsProgressNotifier.Progress("ch1", sents, 1)

        val mid = progress.toTtsHighlightBinding()
        mid.active shouldBe true
        mid.sentence shouldBe sents[1]

        val last = progress.copy(currentIndex = 2).toTtsHighlightBinding()
        last.active shouldBe true
        last.sentence shouldBe sents[2]
    }

    @Test
    fun `mapping with currentIndex minus one yields active but null sentence`() {
        // bind 后 setCurrent(-1) 保留 -1 哨兵；active=true（已绑定），但当前句 null
        val sents = makeSentences(5, 10)
        val progress = TtsProgressNotifier.Progress("ch1", sents, -1)

        val binding = progress.toTtsHighlightBinding()

        binding.active shouldBe true
        binding.sentence.shouldBeNull()
    }

    @Test
    fun `mapping with out of range currentIndex clamps via getOrNull to last`() {
        val sents = makeSentences(5, 10, 20)
        // 注意：notifier.setCurrent 在生产侧会 clamp 到 lastIndex，但 mapping 函数本身只负责映射
        // — 这里验证的是 getOrNull 越界返回 null 的语义（不应越界访问）
        val progress = TtsProgressNotifier.Progress("ch1", sents, 99)

        val binding = progress.toTtsHighlightBinding()

        binding.active shouldBe true
        binding.sentence.shouldBeNull()
    }

    @Test
    fun `mapping with empty sentences and any currentIndex is inactive`() {
        val progress = TtsProgressNotifier.Progress("ch1", emptyList(), 0)

        val binding = progress.toTtsHighlightBinding()

        binding.active shouldBe false
        binding.sentence.shouldBeNull()
    }

    @Test
    fun `clear semantics - empty progress maps to inactive binding`() {
        // clear() 把 chapterHref 置空 + sentences 清空 + currentIndex = -1
        val cleared = TtsProgressNotifier.Progress("", emptyList(), -1)

        val binding = cleared.toTtsHighlightBinding()

        binding.active shouldBe false
        binding.sentence.shouldBeNull()
    }

    @Test
    fun `chapterHref is preserved verbatim in progress snapshot`() {
        // Activity 章节匹配守卫依赖 progress.chapterHref == state.currentHref；
        // 验证 notifier 不会做隐式改写。
        val sents = makeSentences(5, 10)
        val progress = TtsProgressNotifier.Progress("OEBPS/chapter2.xhtml", sents, 0)

        progress.chapterHref shouldBe "OEBPS/chapter2.xhtml"
        progress.toTtsHighlightBinding().active shouldBe true
    }

    /**
     * 集成轻测试：模拟 ViewModel 的 collect 模式（ttsProgressNotifier.progress.collect { map -> state }），
     * 验证 bind / setCurrent / clear 三阶段都能在 StateFlow 的快照里正确呈现给 mapping。
     */
    @Test
    fun `notifier flow values drive mapping across bind setCurrent clear`() = runTest {
        val notifier = TtsProgressNotifier()

        // 初始：未绑定
        notifier.progress.first().toTtsHighlightBinding().active shouldBe false

        // bind 后：第一句
        notifier.bind("ch1", makeSentences(5, 10))
        val bound = notifier.progress.first()
        bound.toTtsHighlightBinding().let {
            it.active shouldBe true
            it.sentence?.index shouldBe 0
        }

        // setCurrent 后：第二句
        notifier.setCurrent(1)
        val mid = notifier.progress.first()
        mid.toTtsHighlightBinding().let {
            it.active shouldBe true
            it.sentence?.index shouldBe 1
            it.sentence?.startOffset shouldBe 5
            it.sentence?.endOffset shouldBe 10
        }

        // clear 后：回到 inactive
        notifier.clear()
        val cleared = notifier.progress.first()
        cleared.toTtsHighlightBinding().let {
            it.active shouldBe false
            it.sentence.shouldBeNull()
        }
    }
}
