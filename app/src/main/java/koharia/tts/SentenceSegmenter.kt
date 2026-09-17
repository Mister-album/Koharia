package koharia.tts

/**
 * 把章节纯文本切成句子。
 *
 * 设计原则：
 * 1. 段落分隔（双换行）必须切——视觉上是独立段落
 * 2. 单换行也切——EPUB 排版常用来表示对话/诗句分隔
 * 3. 中文句末标点：。！？… —— 切
 * 4. 英文句末标点 .!? — 切，但 `R.` / `Mr.` / `19.` / `121-122.` 这类缩写/数字/页码不切
 * 5. 逗号/分号/冒号 不切——MiMo 自己会停顿
 *
 * 英文缩写保护（Phase 1c → Phase 2 实测修复）：
 * - 单大写字母 + 句点：`R.` `M.` `A.` 这类脚注标记
 * - 数字 + 句点：`19. 世纪` `页码 1.` 这种编号/页码
 * - 数字范围 + 句点：`121-122.` `2.1.`
 * - 已知拉丁缩写：`Mr.` `Dr.` `Fig.` `i.e.` `e.g.` 等约 30 个高频词
 *
 * 已知未覆盖（避免过度工程）：
 * - 罕见缩写：`ibid.` `supra.` 等学术缩写暂不列——除非遇到真实误切再加
 * - URL / 邮箱中的句点——电子书文本通常不会出现，出现时归一化为整句
 * - 多层缩写叠加：`Ph.D.` 暂不处理
 */
object SentenceSegmenter {

    /**
     * 中文句末标点。包含中文省略号 ……（两个字符）
     */
    private val CHINESE_END = Regex("""[。！？…]+|[。！？…]+$""")

    /**
     * 英文句末标点。限制：必须是标点后跟非字母数字，避免误切小数 / 缩写。
     */
    private val ENGLISH_END = Regex("""[.!?]+(?=$|[^A-Za-z0-9])""")

    /**
     * 段落分隔：2+ 个换行
     */
    private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")

    /**
     * 高频拉丁缩写词（带末尾句点）。脚注 / 学术文本常见。
     *
     * 末尾无点版本用于 `i.e.` / `e.g.` 这种"内部带点"词——直接比较词+点。
     */
    private val ABBREVIATION_WORDS: Set<String> = setOf(
        "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.", "Sr.", "Jr.",
        "St.", "Co.", "Corp.", "Inc.", "Ltd.",
        "vs.", "etc.", "cf.", "approx.", "esp.", "viz.",
        "Fig.", "fig.", "No.", "no.", "Vol.", "vol.", "Ch.", "ch.",
        "Eq.", "eq.", "Ref.", "ref.", "cf.", "Cf.",
        "i.e.", "e.g.",
    )

    /**
     * 把章节纯文本切成句子列表。
     *
     * @param chapterHref 章节标识
     * @param text 章节纯文本（不含 HTML 标签，由 ChapterTextExtractor 提供）
     * @return 句子列表，按出现顺序
     */
    fun cut(chapterHref: String, text: String): List<Sentence> {
        if (text.isBlank()) return emptyList()

        val sentences = mutableListOf<Sentence>()

        // 按段落切分，并携带每段的**绝对起始偏移**。
        // 不能靠 `cursor += paragraph.length` 推算：段落之间的 `\n\s*\n` 分隔符既没有
        // 计入 cursor，空段落又被过滤掉，会导致每个段落断点都让后续句子偏移漂移
        // （真机症状：高亮整体前移若干字符）。
        for ((paragraph, paragraphStart) in splitParagraphs(text)) {
            val paraSentences = cutParagraph(paragraph)
            for (ps in paraSentences) {
                sentences += ps.copy(
                    chapterHref = chapterHref,
                    startOffset = paragraphStart + ps.startOffset,
                    endOffset = paragraphStart + ps.endOffset,
                )
            }
        }

        return sentences.mapIndexed { idx, s -> s.copy(index = idx) }
    }

    /**
     * 按段落分隔（2+ 换行）切分，返回 `(段落文本, 该段在原文中的绝对起始偏移)`。
     *
     * 保留空段落的偏移信息（直接丢弃空段落本身，不改变后续段的偏移），
     * 让调用方能拿到**真实**的段落起点，而不是靠累加长度猜测。
     */
    private fun splitParagraphs(text: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        val matcher = PARAGRAPH_BREAK.findAll(text)
        var lastEnd = 0
        for (m in matcher) {
            if (m.range.first > lastEnd) {
                result += text.substring(lastEnd, m.range.first) to lastEnd
            }
            lastEnd = m.range.last + 1
        }
        if (lastEnd < text.length) {
            result += text.substring(lastEnd) to lastEnd
        }
        return result.filter { (paragraph, _) -> paragraph.isNotBlank() }
    }

    /**
     * 切一个段落（不含段落分隔符）
     */
    private fun cutParagraph(paragraph: String): List<Sentence> {
        if (paragraph.isBlank()) return emptyList()

        val sentences = mutableListOf<Sentence>()
        var cursor = 0

        while (cursor < paragraph.length) {
            // 跳过前导空白
            val start = nextNonWhitespace(paragraph, cursor)
            if (start >= paragraph.length) break

            // 找下一个句末
            val end = findSentenceEnd(paragraph, start)
            val finalEnd = if (end > start) end else paragraph.length

            // 尾部空白必须连同偏移一起收缩，否则会破坏 Sentence 的
            // `text.length == endOffset - startOffset` 不变量（require 抛异常）。
            val raw = paragraph.substring(start, finalEnd).trimEnd()
            val textEnd = start + raw.length
            if (raw.isNotBlank()) {
                sentences += Sentence(
                    chapterHref = "", // 由外层填充
                    index = 0, // 由外层填充
                    startOffset = start,
                    endOffset = textEnd,
                    text = raw,
                )
            }
            cursor = finalEnd
        }

        return sentences
    }

    /**
     * 找句子结束位置（不含 endOffset 本身）
     *
     * 优先级：
     * 1. 中文标点 。！？…
     * 2. 英文标点 .!? （后跟非字母数字）
     * 3. 单换行
     * 4. 段末
     */
    private fun findSentenceEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            when {
                // 中文标点：含省略号（占 1 个 char，但 UTF-16 占 1 个 code unit）
                c == '。' || c == '！' || c == '？' || c == '…' -> {
                    return consumeTrailingPunctuation(text, i + 1)
                }
                // 英文句号：要求后跟非字母数字 + 不在缩写/数字保护名单
                c == '.' -> {
                    val next = if (i + 1 < text.length) text[i + 1] else ' '
                    if (!next.isLetterOrDigit() && !isProtectedPeriod(text, i)) {
                        return consumeTrailingPunctuation(text, i + 1)
                    }
                }
                // 英文问号/感叹号：保持旧规则（这两类几乎不出现缩写）
                c == '!' || c == '?' -> {
                    val next = if (i + 1 < text.length) text[i + 1] else ' '
                    if (!next.isLetterOrDigit()) {
                        return consumeTrailingPunctuation(text, i + 1)
                    }
                }
                // 单换行：表示对话/诗句分隔
                c == '\n' -> {
                    return i
                }
            }
            i++
        }
        return text.length
    }

    /**
     * 判断 [periodIndex] 处的英文句点是否属于缩写 / 数字 / 页码等"伪句末"。
     *
     * 规则（任一命中即保护）：
     * 1. 前一字符是数字 → 数字 + 句点（`19.` `2024.` `1.2.` `121-122.`）
     * 2. 前一字符是孤立大写字母（且前二字符非字母） → 单字母脚注标记（`R.` `M.` `A.`）
     * 3. 以该句点结尾的"单词"在已知缩写词表中（`Mr.` `Dr.` `Fig.` `i.e.` `e.g.` 等）
     *
     * 注意：连续的句点（如 `...`）只对第一个句点做保护判断；后续由
     * [consumeTrailingPunctuation] 处理。
     */
    private fun isProtectedPeriod(text: String, periodIndex: Int): Boolean {
        if (periodIndex <= 0) return false

        // 规则 1：数字 + 句点
        if (text[periodIndex - 1].isDigit()) return true

        // 规则 2：孤立大写字母 + 句点
        val prev = text[periodIndex - 1]
        if (prev.isUpperCase()) {
            val prevPrev = if (periodIndex >= 2) text[periodIndex - 2] else Char.MIN_VALUE
            if (!prevPrev.isLetter()) return true
        }

        // 规则 3：词表中完整词形
        val wordStart = findWordStart(text, periodIndex - 1)
        if (wordStart <= periodIndex - 1) {
            val token = text.substring(wordStart, periodIndex + 1)
            if (ABBREVIATION_WORDS.contains(token)) return true
        }

        return false
    }

    /**
     * 找到包含 [fromIndex] 在内的"词"起点（连续字母 / 数字 / `'` / `.`）。
     * 用于在缩写表中查找完整词形；`.` 允许出现在中间以支持 `i.e.` / `e.g.` 这类
     * "内点缩写"，但不允许出现在开头或结尾以外的连续多个。
     */
    private fun findWordStart(text: String, fromIndex: Int): Int {
        var i = fromIndex
        while (i >= 0 && (text[i].isLetterOrDigit() || text[i] == '\'' || text[i] == '.')) {
            i--
        }
        return i + 1
    }

    /**
     * 消费末尾的连续标点（"。。。" 或 "?!?!" 这种）
     */
    private fun consumeTrailingPunctuation(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] in "。！？….,!?;:") {
            i++
        }
        return i
    }

    private fun nextNonWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) {
            i++
        }
        return i
    }
}
