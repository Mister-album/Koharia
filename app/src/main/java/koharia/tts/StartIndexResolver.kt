package koharia.tts

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * 定位 TTS 播放起始句的纯函数。
 *
 * 优先级：
 * 1. **DOM 起始偏移（[startOffset]）** — 阅读器 WebView 直接给出"视口顶部第一段可见文字"
 *    在章节纯文本中的字符偏移，与 [Sentence] 的偏移空间**完全一致**（同一个文本节点拼接模型），
 *    也与高亮 JS tree walker 一致。这是最可靠的信号：它描述"用户此刻看到的内容"，
 *    不依赖 Readium `locations.progression` 是否已经更新到位。
 * 2. 视口文本锚（[textAnchor]）— [startOffset] 不可用时的降级路径。要求长度
 *    ≥ [MIN_ANCHOR_CHARS]，并按"最长前缀优先"匹配；避免 2 字符锚（如"保留"）
 *    误命中章节开头。
 * 3. 持久化句子文本（[persistedSentenceText]）— 上次朗读到的句子文本。
 * 4. 进度回退（[progression]）— 全部不可用时的最后兜底。
 *
 * ## 为什么删除了"页窗口校验"
 * 早期版本要求锚命中必须落在 `[pageStart, pageEnd)` 内，否则钳到窗口起点。
 * Readium 的 `locations.progression`（= `webView.scrollX / horizontalScrollRange`）
 * 只在资源页切换（`onPageChanged`）时更新，且会在 WebView 尚未滚动到位时上报 `0.0`
 * （例如章节加载后的首次 `onPageLoaded` 通知）。据此构造的窗口会把**正确的锚点否决**，
 * 并把起播点错误地钳回章节开头。DOM 偏移已经锚定在真实视口上，无需再用窗口交叉校验。
 */
object StartIndexResolver {

    /** 文本锚做探针时截取的最大字符数。 */
    private const val ANCHOR_PROBE_CHARS = 24

    /** 文本锚 / 持久化探针的最小长度；低于此值视为歧义，跳过。 */
    private const val MIN_ANCHOR_CHARS = 6

    private val ANSI_WHITESPACE = Regex("\\s+")

    /**
     * @param startOffset 视口顶部文本在章节纯文本中的字符偏移；null / 负值表示不可用
     * @param textAnchor 视口顶部文本片段（[startOffset] 不可用时的降级锚）
     * @param anchorIsBefore true=锚文本位于阅读位置之前（before），从命中句的下一句开始
     * @param progression 当前显示页在**本章**内的进度（0..1），最后兜底
     * @param persistedSentenceText 上次朗读到的句子文本（来自 [koharia.tts.progress.TtsProgressRepository]）
     */
    fun resolve(
        sentences: List<Sentence>,
        startOffset: Int?,
        textAnchor: String?,
        anchorIsBefore: Boolean,
        progression: Double,
        textLength: Int,
        persistedSentenceText: String? = null,
    ): Int {
        if (sentences.isEmpty()) return 0

        // 第一优先级：DOM 起始偏移（与句子/高亮同一偏移空间）
        if (startOffset != null && startOffset >= 0) {
            val hit = sentenceIndexForOffset(sentences, startOffset)
            logcat(LogPriority.INFO) {
                "[StartIndexResolver] dom offset=$startOffset -> sentence $hit/${sentences.size}"
            }
            return hit
        }

        // 第二优先级：视口文本锚（降级；需要足够长以避免误命中）
        val anchor = textAnchor?.collapseWhitespace()?.let {
            if (anchorIsBefore) it.takeLast(ANCHOR_PROBE_CHARS) else it.take(ANCHOR_PROBE_CHARS)
        }
        if (!anchor.isNullOrEmpty() && anchor.length >= MIN_ANCHOR_CHARS) {
            val hit = matchByAnchor(sentences, anchor, anchorIsBefore)
            if (hit >= 0) {
                logcat(LogPriority.INFO) { "[StartIndexResolver] anchor='$anchor' -> sentence $hit" }
                return hit
            }
            logcat(LogPriority.INFO) {
                "[StartIndexResolver] anchor='$anchor' not matched, falling back"
            }
        }

        // 第三优先级：持久化句子文本（绝对位置，不受视口约束）
        if (!persistedSentenceText.isNullOrBlank()) {
            val probe = persistedSentenceText.collapseWhitespace().take(ANCHOR_PROBE_CHARS)
            if (probe.length >= MIN_ANCHOR_CHARS) {
                val hit = matchByAnchor(sentences, probe, anchorIsBefore = false)
                if (hit >= 0) {
                    logcat(LogPriority.INFO) { "[StartIndexResolver] persisted matched at sentence $hit" }
                    return hit
                }
                logcat(LogPriority.INFO) {
                    "[StartIndexResolver] persisted sentence text not matched, falling back to progression"
                }
            }
        }

        // 第四优先级：进度回退
        val offset = (progression.coerceIn(0.0, 1.0) * textLength).toInt()
        val hit = sentenceIndexForOffset(sentences, offset)
        logcat(LogPriority.INFO) {
            "[StartIndexResolver] progression=$progression (offset=$offset) -> sentence $hit"
        }
        return hit
    }

    /**
     * 第一条满足 `endOffset > offset` 的句子（二分查找）。
     *
     * - `offset <= 0` → 第 0 句
     * - `offset >= 末句.endOffset` → 末句（避免越界回跳到开头）
     */
    internal fun sentenceIndexForOffset(sentences: List<Sentence>, offset: Int): Int {
        if (sentences.isEmpty()) return 0
        if (offset <= 0) return 0
        if (offset >= sentences.last().endOffset) return sentences.lastIndex
        var lo = 0
        var hi = sentences.lastIndex
        var ans = sentences.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sentences[mid].endOffset > offset) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    /**
     * 最长前缀优先的锚匹配：从最长探针逐步缩短到 [MIN_ANCHOR_CHARS]，返回首个命中。
     * `anchorIsBefore` 时返回命中句的**下一句**（饱和到末句）。
     * 无命中返回 -1。
     */
    private fun matchByAnchor(
        sentences: List<Sentence>,
        anchor: String,
        anchorIsBefore: Boolean,
    ): Int {
        var len = minOf(anchor.length, ANCHOR_PROBE_CHARS)
        while (len >= MIN_ANCHOR_CHARS) {
            val probe = anchor.take(len)
            val hit = sentences.indexOfFirst { it.text.collapseWhitespace().contains(probe) }
            if (hit >= 0) {
                return if (anchorIsBefore) (hit + 1).coerceAtMost(sentences.lastIndex) else hit
            }
            len--
        }
        return -1
    }

    private fun String.collapseWhitespace(): String = replace(ANSI_WHITESPACE, " ").trim()
}
