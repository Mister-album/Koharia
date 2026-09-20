package koharia.tts.player

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 播放会话代次语义回归（review P1/P2 round 3）。
 *
 * TtsPlayer 依赖 AudioTrack/MediaCodec，纯 JVM 无法实例化，故把代次语义抽成
 * [PlaybackGeneration] 直接锁定。关键不变量：
 *
 *  同一播放会话内普通连续 enqueue **不得**推进代次 ——
 *  否则预取队列中下一句入队时，正在解码的上一句会被判为 stale 并丢弃 PCM
 *  （review P1 报告的正常朗读漏句回归）。
 */
class PlaybackGenerationTest {

    @Test
    fun `normal consecutive enqueue keeps the same generation`() {
        // P1 round 3 回归：同一会话内连 N 句入队，代次必须保持不变。
        val generation = PlaybackGeneration()
        val first = generation.forEnqueue(wasStopped = false) // 首个 clip 前 stopped=false（默认）
        val second = generation.forEnqueue(wasStopped = false)
        val third = generation.forEnqueue(wasStopped = false)

        first shouldBe second
        second shouldBe third
        // 解码中的 clip 在后续 enqueue 之后仍然有效。
        generation.isValid(first) shouldBe true
    }

    @Test
    fun `enqueue after a stop begins a new generation`() {
        val generation = PlaybackGeneration()
        val sessionA = generation.forEnqueue(wasStopped = false)

        generation.invalidate() // stop()
        val sessionB = generation.forEnqueue(wasStopped = true)

        sessionB shouldBeGreaterThan sessionA
        generation.isValid(sessionA) shouldBe false
        generation.isValid(sessionB) shouldBe true
    }

    @Test
    fun `skipTo invalidates the captured generation`() {
        // P2 round 3：skipTo 后旧 worker 捕获的代次必须失效，
        // writePcm 循环 / 回调据此放弃旧 PCM。
        val generation = PlaybackGeneration()
        val captured = generation.forEnqueue(wasStopped = false)

        generation.invalidate() // skipTo()

        generation.isValid(captured) shouldBe false
    }

    @Test
    fun `release invalidates the captured generation`() {
        val generation = PlaybackGeneration()
        val captured = generation.forEnqueue(wasStopped = false)

        generation.invalidate() // release()

        generation.isValid(captured) shouldBe false
    }

    @Test
    fun `stopped flag decides whether enqueue starts a new session`() {
        // 语义要点：是否推进代次完全由"上一会话是否已停止"决定，
        // 与入队次数无关（避免再次引入"每次 enqueue 都 ++" 的回归）。
        val generation = PlaybackGeneration()

        val a = generation.forEnqueue(wasStopped = true) // 从停止态启动 → 新会话
        val b = generation.forEnqueue(wasStopped = false) // 会话进行中 → 保持
        val c = generation.forEnqueue(wasStopped = false) // 会话进行中 → 保持
        val d = generation.forEnqueue(wasStopped = true) // 又停了再启动 → 新会话

        b shouldBe a
        c shouldBe a
        d shouldBeGreaterThan a
        generation.isValid(a) shouldBe false
        generation.isValid(d) shouldBe true
    }
}
