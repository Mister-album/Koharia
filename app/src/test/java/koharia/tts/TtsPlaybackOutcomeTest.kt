package koharia.tts

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * 章节完成判据回归（review P1 round 2/3）。
 *
 * 两次线上反馈都命中同一处：把"至少有一句发声"当成"整章播完"，于是部分失败时
 * 阅读器跳过未听内容自动跳章。这里用纯函数锁定正确判据。
 */
class TtsPlaybackOutcomeTest {

    @Test
    fun `every sentence produced audio completes the chapter`() {
        classifyTtsPlayback(played = 10, failed = 0) shouldBe TtsPlaybackOutcome.COMPLETE
    }

    @Test
    fun `nothing produced audio reports no audio`() {
        classifyTtsPlayback(played = 0, failed = 0) shouldBe TtsPlaybackOutcome.NO_AUDIO
        classifyTtsPlayback(played = 0, failed = 8) shouldBe TtsPlaybackOutcome.NO_AUDIO
    }

    @Test
    fun `first sentence succeeded then the rest failed is a partial failure`() {
        // review P1 round 2 的原始场景：首句成功、后续因断网/限流全失败。
        // 旧逻辑 playedSentenceCount > 0 → 误判播完并跳章。
        classifyTtsPlayback(played = 1, failed = 4) shouldBe TtsPlaybackOutcome.PARTIAL_FAILURE
    }

    @Test
    fun `a single decode failure among successes is a partial failure`() {
        // review P1 round 3 的场景：合成成功但个别句播放失败（损坏 MP3 / 无 PCM）。
        classifyTtsPlayback(played = 9, failed = 1) shouldBe TtsPlaybackOutcome.PARTIAL_FAILURE
    }
}
