package koharia.tts

/**
 * 一个 EPUB 章节里的单句。
 *
 * @param chapterHref EPUB 章节的资源标识（Readium Link.href）
 * @param index 章节内序号，从 0 开始
 * @param startOffset 该句在章节纯文本中的起始字符偏移（包含）
 * @param endOffset 该句在章节纯文本中的结束字符偏移（不包含）
 * @param text 句子内容（保留原文标点）
 */
data class Sentence(
    val chapterHref: String,
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
) {
    init {
        require(startOffset >= 0) { "startOffset must be >= 0, was $startOffset" }
        require(endOffset > startOffset) {
            "endOffset ($endOffset) must be > startOffset ($startOffset)"
        }
        require(text.isNotBlank()) { "sentence text must not be blank" }
        require(text.length == endOffset - startOffset) {
            "text length (${text.length}) does not match offset span (${endOffset - startOffset})"
        }
    }

    /**
     * 缓存键：voice + style + text 的指纹。
     *
     * 用于本地缓存和去重。语音克隆/设计音色的 voiceFingerprint
     * 暂未纳入（Phase 3 范围）。
     */
    fun cacheKey(voice: String, style: String?): String {
        val stylePart = style?.takeIf { it.isNotBlank() } ?: ""
        return "$voice|$stylePart|$text"
    }
}
