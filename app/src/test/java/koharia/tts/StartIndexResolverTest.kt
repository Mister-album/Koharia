package koharia.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StartIndexResolverTest {

    // ---------- DOM 起始偏移（主路径） ----------

    @Test
    fun `dom offset inside a sentence returns that sentence`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        // 偏移 17 落在第 2 句 [14, 22) 内。
        val start = resolve(sentences, startOffset = 17, textLength = textLength)

        start shouldBe 2
    }

    @Test
    fun `dom offset on a sentence boundary returns the next sentence`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        // 偏移 7 == 第 0 句 endOffset → 取第一条 endOffset > 7 的句子（第 1 句）。
        val start = resolve(sentences, startOffset = 7, textLength = textLength)

        start shouldBe 1
    }

    @Test
    fun `dom offset zero returns the first sentence`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
        )

        val start = resolve(sentences, startOffset = 0, textLength = textLength)

        start shouldBe 0
    }

    @Test
    fun `dom offset past the end clamps to the last sentence`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        // 偏移越界时必须停在末句，而不是越界回跳到第 0 句。
        val start = resolve(sentences, startOffset = textLength + 500, textLength = textLength)

        start shouldBe 3
    }

    @Test
    fun `dom offset wins over anchor and progression`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        // 锚指向第 0 句、进度指向接近末尾，但 DOM 偏移指向第 1 句 → 第 1 句胜。
        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = 9,
            textAnchor = "这是第一章。",
            anchorIsBefore = false,
            progression = 0.9,
            textLength = textLength,
        )

        start shouldBe 1
    }

    @Test
    fun `negative dom offset is ignored and anchor is used`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = -1,
            textAnchor = "我们决定去公园",
            anchorIsBefore = false,
            progression = 0.0,
            textLength = textLength,
        )

        start shouldBe 2
    }

    // ---------- 视口文本锚（降级路径） ----------

    @Test
    fun `anchor contains-hit returns hit index`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "今天天气很好",
            anchorIsBefore = false,
            progression = 0.5,
            textLength = textLength,
        )

        start shouldBe 1
    }

    @Test
    fun `before-anchor starts at hit plus one`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "今天天气很好。",
            anchorIsBefore = true,
            progression = 0.5,
            textLength = textLength,
        )

        start shouldBe 2
    }

    @Test
    fun `before-anchor at last sentence saturates at last index`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "小明带了风筝。",
            anchorIsBefore = true,
            progression = 0.5,
            textLength = textLength,
        )

        start shouldBe 3
    }

    @Test
    fun `too-short anchor is ignored instead of false-matching chapter start`() {
        val (sentences, textLength) = buildSentences(
            "保留好这一份。",
            "今天天气很好。",
            "我们决定去公园。",
            "也要保留下来。",
        )

        // 2 字符锚 "保留" 在句 0 和句 3 都出现。低于 MIN_ANCHOR_CHARS 必须跳过，
        // 不能误命中句 0 —— 这正是真机上"保留"把起播点拉回章节开头的根因。
        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "保留",
            anchorIsBefore = false,
            progression = 0.8,
            textLength = textLength,
        )

        start shouldBe 3
    }

    @Test
    fun `long anchor still wins over progression`() {
        val (sentences, textLength) = buildSentences(
            "保留好这一份。",
            "今天天气很好。",
            "我们决定去公园。",
            "也要保留下来。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "保留好这一份。",
            anchorIsBefore = false,
            progression = 0.8,
            textLength = textLength,
        )

        start shouldBe 0
    }

    @Test
    fun `prefix fallback matches sentence whose head prefixes the anchor`() {
        val (sentences, textLength) = buildSentences(
            "你好世界。",
            "今天天气很好我们去公园。",
            "然后回家做饭。",
        )

        // 探针不被任何句子 contains，但第 1 句句头是探针前缀 → 最长前缀逐步缩短后命中。
        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "今天天气很好我们去公园。然后回家做饭休息。",
            anchorIsBefore = false,
            progression = 0.5,
            textLength = textLength,
        )

        start shouldBe 1
    }

    @Test
    fun `unmatched anchor falls back to progression offset`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "这个锚完全不存在。",
            anchorIsBefore = false,
            progression = 0.2,
            textLength = textLength,
        )

        start shouldBe 0
    }

    @Test
    fun `empty anchor falls back to progression offset`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "",
            anchorIsBefore = false,
            progression = 0.5,
            textLength = textLength,
        )

        start shouldBe 2
    }

    @Test
    fun `whitespace in anchor and sentence collapses before matching`() {
        val (sentences, textLength) = buildSentences("One  two  three")

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "One\t\n two",
            anchorIsBefore = false,
            progression = 0.5,
            textLength = textLength,
        )

        start shouldBe 0
    }

    // ---------- 持久化句子文本 ----------

    @Test
    fun `persisted sentence text resolves to matching sentence index`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = null,
            anchorIsBefore = false,
            progression = 0.0,
            textLength = textLength,
            persistedSentenceText = "我们决定去公园。",
        )

        start shouldBe 2
    }

    @Test
    fun `anchor wins over persisted sentence`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = "今天天气很好",
            anchorIsBefore = false,
            progression = 0.0,
            textLength = textLength,
            persistedSentenceText = "我们决定去公园。",
        )

        start shouldBe 1
    }

    @Test
    fun `unmatched persisted sentence falls back to progression`() {
        val (sentences, textLength) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
            "小明带了风筝。",
        )

        val start = StartIndexResolver.resolve(
            sentences = sentences,
            startOffset = null,
            textAnchor = null,
            anchorIsBefore = false,
            progression = 0.2,
            textLength = textLength,
            persistedSentenceText = "完全不在章节里的句子。",
        )

        start shouldBe 0
    }

    // ---------- sentenceIndexForOffset 边界 ----------

    @Test
    fun `sentenceIndexForOffset handles boundaries`() {
        val (sentences, _) = buildSentences(
            "这是第一章。",
            "今天天气很好。",
            "我们决定去公园。",
        )

        StartIndexResolver.sentenceIndexForOffset(sentences, -5) shouldBe 0
        StartIndexResolver.sentenceIndexForOffset(sentences, 0) shouldBe 0
        StartIndexResolver.sentenceIndexForOffset(sentences, 7) shouldBe 1
        StartIndexResolver.sentenceIndexForOffset(sentences, 14) shouldBe 2
        StartIndexResolver.sentenceIndexForOffset(sentences, 9999) shouldBe 2
    }

    // ---------- helpers ----------

    private fun resolve(
        sentences: List<Sentence>,
        startOffset: Int?,
        textLength: Int,
    ): Int = StartIndexResolver.resolve(
        sentences = sentences,
        startOffset = startOffset,
        textAnchor = null,
        anchorIsBefore = false,
        progression = 0.0,
        textLength = textLength,
    )

    private fun buildSentences(vararg texts: String): Pair<List<Sentence>, Int> {
        val sentences = mutableListOf<Sentence>()
        var offset = 0
        texts.forEachIndexed { index, text ->
            sentences += Sentence(
                chapterHref = "ch1",
                index = index,
                startOffset = offset,
                endOffset = offset + text.length,
                text = text,
            )
            offset += text.length
        }
        return sentences to offset
    }
}
