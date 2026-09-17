package koharia.tts.progress

import koharia.tts.Sentence

/**
 * 把 TTS 当前朗读句子转成章节内 0..1 进度（用于阅读 → 听书切换时的 UI 显示）。
 *
 * 这是纯函数 UseCase：输入当前句子列表 + 当前下标，输出章节进度。
 * 不依赖任何 Android 类型，方便单测覆盖。
 */
class SwitchToListeningUseCase {
    /**
     * 输入 [TtsProgressNotifier.SentenceRef] 列表 + 当前下标，返回章节内 0..1 进度。
     */
    operator fun invoke(sentences: List<TtsProgressNotifier.SentenceRef>, currentIndex: Int): Double =
        SentenceProgressMapper.fractionAt(sentences, currentIndex)

    /**
     * 便捷重载：接收完整 [Sentence] 列表（与 SentenceSegmenter 输出对齐），
     * 内部转成 [TtsProgressNotifier.SentenceRef]，再调核心逻辑。
     */
    fun fromSentences(sentences: List<Sentence>, currentIndex: Int): Double =
        invoke(
            sentences.map { TtsProgressNotifier.SentenceRef(it.index, it.startOffset, it.endOffset) },
            currentIndex,
        )
}
