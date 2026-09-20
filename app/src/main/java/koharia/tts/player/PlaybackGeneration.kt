package koharia.tts.player

import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放**会话**代次（review P1/P2 round 3）。
 *
 * 语义：代次标识一次"播放会话"，而不是单个 clip。同一会话内的所有 clip 共享同一代次，
 * 普通连续 `enqueue` **不得**推进代次 —— 否则预取队列中下一句入队时，正在解码的上一句
 * 会被误判为 stale 而丢弃 PCM，造成**正常朗读漏句**（review P1 报告的回归）。
 *
 * 推进代次的时机（即"会话语义被打破"）：
 *  - [invalidate]：`skipTo()` / `stop()` / `release()` 显式作废当前会话；
 *  - [forEnqueue]：仅当上一会话已停止（`stopped == true`）时才开新会话。
 *
 * 纯 JVM 实现（不依赖 Android），便于单元测试锁定上述语义。
 */
class PlaybackGeneration {

    private val value = AtomicInteger(0)

    /** 当前代次。 */
    val current: Int get() = value.get()

    /** 作废当前会话并返回新代次。用于 `skipTo` / `stop` / `release`。 */
    fun invalidate(): Int = value.incrementAndGet()

    /**
     * 一次 `enqueue` 调用对应的代次：
     *  - 上一会话已停止（[wasStopped] == `true`）⟹ 开始新会话，推进代次；
     *  - 否则属于同一会话，返回当前代次，**不**推进。
     */
    fun forEnqueue(wasStopped: Boolean): Int =
        if (wasStopped) value.incrementAndGet() else value.get()

    /** 捕获的代次（[current] 在入队/取 clip 时的快照）是否仍属于当前会话。 */
    fun isValid(captured: Int): Boolean = value.get() == captured
}
