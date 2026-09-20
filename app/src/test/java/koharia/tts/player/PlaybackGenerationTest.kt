package koharia.tts.player

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 播放会话代次语义回归（review P1 round 3 + ocr findings B/C）。
 *
 * TtsPlayer 依赖 AudioTrack/MediaCodec，纯 JVM 无法实例化，故把代次语义抽成
 * [PlaybackGeneration] 直接锁定。关键不变量：
 *
 *  1. 普通连续 enqueue **不推进**代次 —— 否则预取队列中下一句入队时，正在解码的上一句
 *     会被判为 stale 并丢弃 PCM（review P1 报告的"正常朗读漏句"回归）。
 *  2. 只有**会话边界**（startNewSession / skipTo / stop / release）推进代次，
 *     且推进后此前快照的代次全部失效（ocr finding C：换章不走 stop，必须显式作废）。
 */
class PlaybackGenerationTest {

    @Test
    fun `clips enqueued in the same session share one generation`() {
        // P1 回归：同一会话内连续入队（N、N+1、N+2）快照到的代次必须相同，
        // 否则解码中的 N 会被 N+1 的入队"顶掉"。
        val generation = PlaybackGeneration()
        val clipN = generation.current
        val clipN1 = generation.current
        val clipN2 = generation.current

        clipN shouldBe clipN1
        clipN1 shouldBe clipN2
        generation.isValid(clipN) shouldBe true
    }

    @Test
    fun `an in-flight clip stays valid for the whole session`() {
        val generation = PlaybackGeneration()
        val firstClip = generation.current

        // 模拟一段正常播放：后续句反复入队，但没有任何会话边界事件。
        repeat(10) { generation.current }

        generation.isValid(firstClip) shouldBe true
    }

    @Test
    fun `startNewSession invalidates in-flight clips from the previous chapter`() {
        // ocr finding C：换章时 TtsService.startPlayback 调 player.startNewSession()
        // （而不是 stop），必须让旧章在途 clip 失效。
        val generation = PlaybackGeneration()
        val previousChapterClip = generation.current

        generation.invalidate() // TtsPlayer.startNewSession()

        generation.isValid(previousChapterClip) shouldBe false
    }

    @Test
    fun `skipTo invalidates in-flight clips`() {
        val generation = PlaybackGeneration()
        val captured = generation.current

        generation.invalidate() // TtsPlayer.skipTo()

        generation.isValid(captured) shouldBe false
    }

    @Test
    fun `stop and release invalidate in-flight clips`() {
        val generation = PlaybackGeneration()

        val beforeStop = generation.current
        generation.invalidate() // stop()
        generation.isValid(beforeStop) shouldBe false

        val beforeRelease = generation.current
        generation.invalidate() // release()
        generation.isValid(beforeRelease) shouldBe false
    }

    @Test
    fun `clips stamped after a session boundary belong to the new session`() {
        val generation = PlaybackGeneration()
        val oldClip = generation.current

        generation.invalidate() // 会话边界
        val newClip = generation.current

        newClip shouldBeGreaterThan oldClip
        generation.isValid(oldClip) shouldBe false
        generation.isValid(newClip) shouldBe true
    }
}
