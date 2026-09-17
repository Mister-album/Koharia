package koharia.tts.progress

/**
 * 句子 ↔ 阅读进度（0..1）的纯函数转换。
 *
 * 数据流：
 * - 阅读位置（pageStart..pageEnd）→ TtsStartIndexResolver 已映射到 sentenceIndex
 * - 句子下标 → 字符偏移 → 章节内 0..1 进度（用于阅读器跳转）
 * - 字符偏移 → 句子下标（用于 TTS 从指定位置继续读）
 *
 * 为什么是纯函数：单测覆盖率高；Phase 3 的设置项（语速/间隔）不影响
 * 转换正确性，只影响合成端。
 */
object SentenceProgressMapper {

    /**
     * 给定章节内句子列表 + 当前句子下标，返回章节内 0..1 进度。
     *
     * 进度定义 = 当前句 endOffset / 最后一句 endOffset；这是单调递增的近似。
     * 不使用 startOffset，因为朗读中正在发声的是 endOffset 之前的部分。
     *
     * @return 0.0 当 sentences 为空或 index 越界；否则 0..1
     */
    fun fractionAt(sentences: List<TtsProgressNotifier.SentenceRef>, currentIndex: Int): Double {
        if (sentences.isEmpty()) return 0.0
        val safeIndex = currentIndex.coerceIn(0, sentences.lastIndex)
        val last = sentences.last().endOffset
        if (last <= 0) return 0.0
        val here = sentences[safeIndex].endOffset.coerceAtLeast(0)
        return (here.toDouble() / last.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * 给定章节纯文本总长 + 字符偏移，返回该偏移对应的句子下标。
     *
     * - offset < 0 或 sentences 为空 → -1
     * - offset >= 总长 → 最后一句下标
     * - 否则：第一个 endOffset > offset 的句子
     */
    fun sentenceAtOffset(
        sentences: List<TtsProgressNotifier.SentenceRef>,
        offset: Int,
    ): Int {
        if (sentences.isEmpty() || offset < 0) return -1
        val lastEnd = sentences.last().endOffset
        if (offset >= lastEnd) return sentences.lastIndex
        // 二分查找：第一个 endOffset > offset
        var lo = 0
        var hi = sentences.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sentences[mid].endOffset > offset) {
                hi = mid
            } else {
                lo = mid + 1
            }
        }
        return lo
    }

    /**
     * 给定 0..1 章节进度，返回对应的句子下标。
     * 用 [fractionAt] 的反函数：找到 fractionAt(...) 第一个 >= target 的下标。
     */
    fun sentenceAtFraction(
        sentences: List<TtsProgressNotifier.SentenceRef>,
        fraction: Double,
    ): Int {
        if (sentences.isEmpty()) return -1
        val target = fraction.coerceIn(0.0, 1.0)
        // target=0 且句子非空：返回 0（章节首句）
        if (target <= 0.0) return 0
        if (target >= 1.0) return sentences.lastIndex
        var lo = 0
        var hi = sentences.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (fractionAt(sentences, mid) >= target) {
                hi = mid
            } else {
                lo = mid + 1
            }
        }
        return lo
    }
}
