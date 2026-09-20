package koharia.tts.player

import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放**会话**代次（review P1/P2 round 3）。
 *
 * 代次标识一次"播放会话"，而不是单个 clip。同一会话内的所有 clip 共享同一代次
 * （在 `TtsPlayer.enqueue` 时快照 [current] 并随 clip 记录），因此**普通连续 enqueue
 * 不会推进代次** —— 否则预取队列中下一句入队时，正在解码的上一句会被判为 stale 并
 * 丢弃 PCM，造成正常朗读漏句（review P1 报告的回归）。
 *
 * 推进代次的唯一入口是 [invalidate]，由**会话边界**触发：
 *  - `TtsPlayer.startNewSession()` —— 换章 / 新播放会话（`TtsService.startPlayback`）；
 *  - `TtsPlayer.skipTo()` —— 同章跳句；
 *  - `TtsPlayer.stop()` / `TtsPlayer.release()`。
 *
 * 不变量：`invalidate()` 之后，此前快照出的所有代次都 `isValid == false`。
 *
 * 纯 JVM 实现（不依赖 Android），便于单元测试锁定上述语义。
 */
class PlaybackGeneration {

    private val value = AtomicInteger(0)

    /** 当前会话代次。入队时快照它并随 clip 记录。 */
    val current: Int get() = value.get()

    /** 作废当前会话（并进入新代次）。返回新代次。 */
    fun invalidate(): Int = value.incrementAndGet()

    /** 快照出的代次是否仍属于当前会话。 */
    fun isValid(captured: Int): Boolean = value.get() == captured
}
