package koharia.tts.reader

/**
 * 阅读器 WebView 抽取出来的 TTS 文本模型。
 *
 * [text] 由 JS **按与高亮 tree walker 完全相同的顺序**拼接文本节点的原始 `nodeValue`
 * 得到（无分隔符）。因此 [koharia.tts.Sentence] 的字符偏移既能用于起播定位，
 * 也能被高亮 JS 精确定位到 DOM，不会因 `document.body.innerText` 的空白归一化
 * 而产生偏移漂移。
 *
 * @param text 章节可见文本（文本节点原始内容顺序拼接）
 * @param startOffset 视口顶部第一段可见文字在 [text] 中的字符偏移；-1 表示未找到
 * @param snippet 视口顶部文本片段（仅用于日志 / 降级锚）
 * @param nodeCount 参与拼接的文本节点数（诊断用，需与高亮 walker 一致）
 */
data class TtsTextModel(
    val text: String,
    val startOffset: Int,
    val snippet: String,
    val nodeCount: Int,
)
