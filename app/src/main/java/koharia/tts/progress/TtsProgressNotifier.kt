package koharia.tts.progress

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * 句子级同步广播中心（Phase 2 核心）。
 *
 * 设计动机：
 * - Phase 1c 朗读只能在通知栏显示进度，无法在阅读器里高亮当前句
 * - 阅读器需要实时知道"正在读第几句"，但 `TtsService` 是独立进程内服务
 * - 通过进程内单例 StateFlow 暴露，避免 AIDL / Broadcast 的复杂度（同一应用内）
 *
 * 生命周期：
 * - 朗读启动时 `bind(chapterId, sentences)` 设置基准
 * - 朗读中 `setCurrent(index)` 更新当前位置
 * - 朗读停止 / 切换章节时 `clear()` 重置
 *
 * 线程模型：StateFlow 自身线程安全；setCurrent 从 TtsService 协程调用，
 * collectAsState 从阅读器 Composable 调用。
 */
class TtsProgressNotifier {

    /**
     * 当前朗读的句子上下文。
     *
     * @param chapterHref 章节资源标识
     * @param sentences 当前章节的句子列表（与 SentenceSegmenter 输出对齐）
     * @param currentIndex 正在朗读的句子下标；-1 表示未开始
     */
    data class Progress(
        val chapterHref: String,
        val sentences: List<SentenceRef>,
        val currentIndex: Int,
    ) {
        /** 当前句的起始字符偏移（章节纯文本内），无当前句时返回 0。 */
        val currentStartOffset: Int
            get() = sentences.getOrNull(currentIndex)?.startOffset ?: 0

        /** 当前句的结束字符偏移（不含），无当前句时返回 0。 */
        val currentEndOffset: Int
            get() = sentences.getOrNull(currentIndex)?.endOffset ?: 0

        /** 当前句进度（0..1），无当前句或章节为空时返回 0。 */
        val fraction: Double
            get() {
                if (currentIndex < 0 || sentences.isEmpty()) return 0.0
                val totalChars = sentences.last().endOffset.coerceAtLeast(1)
                val cur = currentEndOffset.coerceAtMost(totalChars)
                return (cur.toDouble() / totalChars).coerceIn(0.0, 1.0)
            }
    }

    /**
     * 句子的最小引用（避免广播整句文本，省内存/GC）。
     * 端到端 ID = chapterHref + ":" + index，在阅读器内可还原为原始句。
     */
    data class SentenceRef(
        val index: Int,
        val startOffset: Int,
        val endOffset: Int,
    )

    private val _progress = MutableStateFlow(Progress("", emptyList(), -1))

    /** 当前进度的只读视图。 */
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Phase 3：章节**自然播完**（不是被 skip/stop 打断）事件。
     *
     * 由 [TtsService] 在 `playback complete` 路径发出，阅读器监听后自动续播下一章。
     * 用 [MutableSharedFlow]（无 replay）而非 StateFlow：这是"一次性事件"，
     * 不应在阅读器重新订阅时被重放。buffer=1 + [tryEmit]，发送方永不挂起。
     */
    private val _chapterCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 章节自然播完事件流。 */
    val chapterCompleted: SharedFlow<Unit> = _chapterCompleted.asSharedFlow()

    /** 发出"章节自然播完"事件；无人订阅时静默丢弃。 */
    fun notifyChapterCompleted() {
        logcat(LogPriority.INFO) { "[TtsProgressNotifier] notifyChapterCompleted" }
        _chapterCompleted.tryEmit(Unit)
    }

    /**
     * PR review P1：本次播放**一句都没能发声**（无效 API key / 断网 / 限流 / 超长句全被跳过）。
     *
     * 与 [chapterCompleted] 互斥：失败时**绝不**发 chapterCompleted，避免阅读器在没有任何
     * 声音的情况下连续跳章。阅读器订阅后提示用户（具体文案由 UI 层从资源解析）。
     */
    private val _playbackFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 播放失败（未产生任何音频）事件流。 */
    val playbackFailed: SharedFlow<Unit> = _playbackFailed.asSharedFlow()

    /** 发出"本章未产生任何音频"事件；无人订阅时静默丢弃。 */
    fun notifyPlaybackFailed() {
        logcat(LogPriority.WARN) { "[TtsProgressNotifier] notifyPlaybackFailed" }
        _playbackFailed.tryEmit(Unit)
    }

    /**
     * 绑定一个新章节的句子列表。
     * 重置 currentIndex 为 0；TtsService 在确定真实起点后会调用 [setCurrent] 覆盖。
     */
    @Synchronized
    fun bind(chapterHref: String, sentences: List<SentenceRef>) {
        logcat(LogPriority.INFO) {
            "[TtsProgressNotifier] bind chapterHref='$chapterHref' size=${sentences.size}"
        }
        _progress.value = Progress(
            chapterHref = chapterHref,
            sentences = sentences,
            currentIndex = if (sentences.isEmpty()) -1 else 0,
        )
    }

    /**
     * 更新当前朗读的下标；超出范围或负数表示清空。
     *
     * `@Synchronized` 与 [bind]/[clear] 共用同一把 monitor：这里的读-改-写（`snapshot.copy`）
     * 必须相对它们原子，否则并发 [clear]（停止朗读 / 销毁 Service）落在读与写之间时，
     * 会把已经清空的旧章节绑定**写回去** —— 表现就是停止朗读后阅读器仍显示陈旧的句子高亮。
     * [TtsService] 的代次检查只挡住"旧会话的回调"，挡不住锁外并发执行的 [clear]。
     *
     * 加锁顺序安全：调用方要么不持 [TtsService] 的 `sessionLock`（`startPlayback` 路径），
     * 要么是 `sessionLock` → 本 monitor（worker 回调路径），不存在反向获取。
     */
    @Synchronized
    fun setCurrent(index: Int) {
        val snapshot = _progress.value
        if (snapshot.sentences.isEmpty()) return
        val clamped = index.coerceIn(-1, snapshot.sentences.lastIndex)
        if (clamped == snapshot.currentIndex) return
        logcat(LogPriority.INFO) { "[TtsProgressNotifier] setCurrent $clamped" }
        _progress.value = snapshot.copy(currentIndex = clamped)
    }

    /** 重置为空状态（朗读停止 / 章节切换）。 */
    @Synchronized
    fun clear() {
        _progress.value = Progress("", emptyList(), -1)
    }

    /** 当前是否已绑定到某章节（用于阅读器判断是否要展示高亮层）。 */
    val isBound: Boolean
        get() = _progress.value.sentences.isNotEmpty()
}
