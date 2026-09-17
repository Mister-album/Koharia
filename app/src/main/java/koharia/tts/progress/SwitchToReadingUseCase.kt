package koharia.tts.progress

import koharia.tts.Sentence

/**
 * 把阅读位置（章节 0..1 进度 / 字符偏移）转成对应句子下标，
 * 用于听书 → 阅读切换时的精确跳转。
 */
class SwitchToReadingUseCase {
    /** 由 0..1 章节进度反查句子下标。 */
    operator fun invoke(sentences: List<TtsProgressNotifier.SentenceRef>, fraction: Double): Int =
        SentenceProgressMapper.sentenceAtFraction(sentences, fraction)

    /** 由字符偏移反查句子下标。 */
    operator fun invoke(sentences: List<TtsProgressNotifier.SentenceRef>, offset: Int): Int =
        SentenceProgressMapper.sentenceAtOffset(sentences, offset)

    /**
     * 便捷重载：接收完整 [Sentence] 列表。
     */
    fun byFraction(sentences: List<Sentence>, fraction: Double): Int =
        invoke(
            sentences.map { TtsProgressNotifier.SentenceRef(it.index, it.startOffset, it.endOffset) },
            fraction,
        )

    fun byOffset(sentences: List<Sentence>, offset: Int): Int =
        invoke(
            sentences.map { TtsProgressNotifier.SentenceRef(it.index, it.startOffset, it.endOffset) },
            offset,
        )
}
