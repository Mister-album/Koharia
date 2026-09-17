package koharia.tts

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SentenceSegmenterTest {

    @Test
    fun `cuts simple Chinese paragraph by periods`() {
        val text = "今天天气很好。我们决定去公园。小明带了风筝。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 3
        sentences[0].chapterHref shouldBe "ch1"
        sentences[0].index shouldBe 0
        sentences[0].startOffset shouldBe 0
        sentences[0].endOffset shouldBe 7
        sentences[0].text shouldBe "今天天气很好。"

        sentences[1].startOffset shouldBe 7
        sentences[1].endOffset shouldBe 15
        sentences[1].text shouldBe "我们决定去公园。"

        sentences[2].startOffset shouldBe 15
        sentences[2].endOffset shouldBe 22
        sentences[2].text shouldBe "小明带了风筝。"
    }

    @Test
    fun `splits by double newline as paragraph break`() {
        val text = "第一段内容。\n\n第二段内容。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "第一段内容。"
        sentences[1].text shouldBe "第二段内容。"
    }

    @Test
    fun `offsets stay correct across a paragraph break`() {
        // 回归：早期实现用 `cursor += paragraph.length` 推算段落起点，漏掉 `\n\n`
        // 分隔符长度 → 每过一个段落断点，后续句子偏移就前移（真机高亮整体错位）。
        val text = "第一段内容。\n\n第二段内容。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[1].startOffset shouldBe 8
        sentences[1].endOffset shouldBe 14
        text.substring(sentences[1].startOffset, sentences[1].endOffset) shouldBe sentences[1].text
    }

    @Test
    fun `every sentence slice matches its text across multiple and blank paragraphs`() {
        val text = "A段。\n\n\n\nB段内容。\n\n\nC段内容。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences.shouldHaveSize(3)
        sentences.forEach { sentence ->
            // 起播与高亮都依赖 `text[startOffset, endOffset) == sentence.text`
            text.substring(sentence.startOffset, sentence.endOffset) shouldBe sentence.text
        }
    }

    @Test
    fun `offsets ignore trailing whitespace before a paragraph break`() {
        val text = "前句。   \n\n后句。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences.forEach { sentence ->
            text.substring(sentence.startOffset, sentence.endOffset) shouldBe sentence.text
        }
    }

    @Test
    fun `handles English sentences with period and space`() {
        val text = "Hello world. How are you? I am fine."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 3
        sentences[0].text shouldBe "Hello world."
        sentences[1].text shouldBe "How are you?"
        sentences[2].text shouldBe "I am fine."
    }

    @Test
    fun `does not split decimal numbers`() {
        val text = "It costs 3.14 dollars."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "It costs 3.14 dollars."
    }

    @Test
    fun `splits mixed Chinese-English`() {
        val text = "他说：Hello. 然后走了。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "他说：Hello."
        sentences[1].text shouldBe "然后走了。"
    }

    @Test
    fun `blank text returns empty list`() {
        SentenceSegmenter.cut("ch1", "") shouldHaveSize 0
        SentenceSegmenter.cut("ch1", "   \n\n  ") shouldHaveSize 0
    }

    @Test
    fun `chinese exclamation and question marks split too`() {
        val text = "这是什么？这是测试！"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "这是什么？"
        sentences[1].text shouldBe "这是测试！"
    }

    // ===== Phase 1c → Phase 2 实测修复：缩写 / 数字 / 页码保护 =====

    @Test
    fun `single uppercase letter before period is treated as abbreviation, not split`() {
        val text = "见 R. 史密斯所述。R. Smith 的论文很重要。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "见 R. 史密斯所述。"
        sentences[1].text shouldBe "R. Smith 的论文很重要。"
    }

    @Test
    fun `multiple single-letter footnote markers do not split`() {
        val text = "R. 与 M. 的讨论。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "R. 与 M. 的讨论。"
    }

    @Test
    fun `digit before period is not split`() {
        val text = "这是 19. 世纪初的事件。后续还有讨论。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "这是 19. 世纪初的事件。"
        sentences[1].text shouldBe "后续还有讨论。"
    }

    @Test
    fun `digit range like 121-122 before period is not split`() {
        val text = "页 121-122. 引用详见参考。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "页 121-122. 引用详见参考。"
    }

    @Test
    fun `numbered version like 2_1 before period is not split`() {
        val text = "在 2.1. 节中讨论。下一段另说。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "在 2.1. 节中讨论。"
        sentences[1].text shouldBe "下一段另说。"
    }

    @Test
    fun `english abbreviations like Mr, Dr, Fig, etc are not split`() {
        val text = "Mr. Smith 与 Dr. Jones 讨论 Fig. 3 的结论。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "Mr. Smith 与 Dr. Jones 讨论 Fig. 3 的结论。"
    }

    @Test
    fun `i_e and e_g abbreviations are not split`() {
        // i.e. / e.g. 是内点缩写，连同 "ones." 这个真句末一起，整段切成两句
        val text = "There are several reasons, i.e. three major ones. And e.g. this one."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "There are several reasons, i.e. three major ones."
        sentences[1].text shouldBe "And e.g. this one."
    }

    @Test
    fun `i_e followed by sentence end - the sentence end splits`() {
        // i.e. 后面紧跟真句末（句号+字母），需要切
        val text = "Reasons i.e. three are listed. They follow."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "Reasons i.e. three are listed."
        sentences[1].text shouldBe "They follow."
    }

    @Test
    fun `abbreviation followed by number is not split`() {
        val text = "见 Fig. 3 的说明。其他段落继续。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "见 Fig. 3 的说明。"
        sentences[1].text shouldBe "其他段落继续。"
    }

    @Test
    fun `lowercase letter period still splits as sentence end`() {
        val text = "He went home. Then he slept."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "He went home."
        sentences[1].text shouldBe "Then he slept."
    }

    @Test
    fun `mixed - abbreviation protected, but real sentence ends still split`() {
        val text = "Mr. Smith 住在伦敦。他每天喝咖啡。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "Mr. Smith 住在伦敦。"
        sentences[1].text shouldBe "他每天喝咖啡。"
    }

    @Test
    fun `trailing abbreviation at end of paragraph is not split`() {
        val text = "比较 A. 与 B. 的差异"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "比较 A. 与 B. 的差异"
    }

    @Test
    fun `consecutive abbreviations like etc_etc are not split`() {
        val text = "等等 etc. 等等 etc. 还有等等。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "等等 etc. 等等 etc. 还有等等。"
    }

    @Test
    fun `parenthetical abbreviation inside sentence is not split`() {
        val text = "结果 (cf. Fig. 5) 显示趋势明显。其他段落继续。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "结果 (cf. Fig. 5) 显示趋势明显。"
        sentences[1].text shouldBe "其他段落继续。"
    }

    @Test
    fun `three-letter all-caps abbreviation before period still splits as sentence`() {
        // "USA." 这种全大写多字母，规则 2 不命中（前一字符也是字母），规则 3 也无 USA
        // 因此仍按英文句末切。这是已知限制：常见国家名/全大写词暂不保护。
        val text = "USA. The end."
        val sentences = SentenceSegmenter.cut("ch1", text)

        // USA. 切出 → ["USA.", "The end."]，符合当前规则
        sentences shouldHaveSize 2
        sentences[0].text shouldBe "USA."
        sentences[1].text shouldBe "The end."
    }

    @Test
    fun `regression - decimal numbers are not split`() {
        val text = "It costs 3.14 dollars."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 1
        sentences[0].text shouldBe "It costs 3.14 dollars."
    }

    @Test
    fun `regression - mixed chinese-english still splits on chinese period`() {
        val text = "他说：Hello. 然后走了。"
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "他说：Hello."
        sentences[1].text shouldBe "然后走了。"
    }

    @Test
    fun `regression - blank text returns empty list`() {
        SentenceSegmenter.cut("ch1", "") shouldHaveSize 0
        SentenceSegmenter.cut("ch1", "   \n\n  ") shouldHaveSize 0
    }

    @Test
    fun `regression - paragraph break splits regardless of abbreviation`() {
        val text = "第一段含 Dr. Smith。\n\n第二段含 Fig. 3."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "第一段含 Dr. Smith。"
        sentences[1].text shouldBe "第二段含 Fig. 3."
    }

    @Test
    fun `single newline still splits even after abbreviation`() {
        val text = "Mr. Smith said hi.\nThen he left."
        val sentences = SentenceSegmenter.cut("ch1", text)

        sentences shouldHaveSize 2
        sentences[0].text shouldBe "Mr. Smith said hi."
        sentences[1].text shouldBe "Then he left."
    }

    @Test
    fun `real-world footnote style paragraph segments correctly`() {
        val text = """
            关于该方法的详细讨论见 R. Smith (2010).
            他指出 19. 世纪早期的工作存在 3 个主要问题.
            具体来说包括页 121-122. 中提到的 Fig. 3 等.
        """.trimIndent()
        val sentences = SentenceSegmenter.cut("ch1", text)

        // 真实样例：保护 R./19./121-122./Fig. 3 等，但段落末尾的英文句点仍切
        sentences.size shouldBeGreaterThanOrEqual 2
        // 第一句必须包含 "R. Smith (2010)"，不能被切成碎句
        sentences[0].text.shouldContain("R. Smith (2010)")
    }
}
