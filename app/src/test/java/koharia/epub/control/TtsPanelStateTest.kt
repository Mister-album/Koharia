package koharia.epub.control

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import koharia.tts.TtsAction
import koharia.tts.TtsPlaybackState
import org.junit.jupiter.api.Test

/**
 * Phase 3.3: [TtsPanelState] 纯 JVM 单测。
 *
 * Composable 测试需要 androidTest/ + Compose UI 测试 runtime;单测只覆盖决策逻辑:
 * - `shouldRender` 跟随 `active`
 * - `isPlaying` 跟随 `playbackState`
 * - `playPauseAction` 是 PLAYING→PAUSE / PAUSED→PLAY / STOPPED→PLAY 的可逆映射
 *
 * Composable 自身的渲染、动画、点击分发留给真机回归 (详见 `docs/tts/phase-3-control-panel.md` 真机验证清单)。
 *
 * 框架约定:项目使用 Kotest + JUnit Jupiter (与 TtsPreferencesTest 等既有测试一致),不是 JUnit 4。
 */
class TtsPanelStateTest {

    @Test
    fun `shouldRender is false when inactive regardless of playback state`() {
        listOf(
            TtsPanelState(active = false, playbackState = TtsPlaybackState.STOPPED),
            TtsPanelState(active = false, playbackState = TtsPlaybackState.PAUSED),
            TtsPanelState(active = false, playbackState = TtsPlaybackState.PLAYING),
        ).forEach { state ->
            state.shouldRender.shouldBeFalse()
        }
    }

    @Test
    fun `shouldRender is true when active and PLAYING`() {
        TtsPanelState(active = true, playbackState = TtsPlaybackState.PLAYING)
            .shouldRender
            .shouldBeTrue()
    }

    @Test
    fun `shouldRender is true when active and PAUSED (resume control surface)`() {
        TtsPanelState(active = true, playbackState = TtsPlaybackState.PAUSED)
            .shouldRender
            .shouldBeTrue()
    }

    @Test
    fun `isPlaying only true in PLAYING state`() {
        TtsPanelState(active = true, playbackState = TtsPlaybackState.PLAYING).isPlaying.shouldBeTrue()
        TtsPanelState(active = true, playbackState = TtsPlaybackState.PAUSED).isPlaying.shouldBeFalse()
        TtsPanelState(active = true, playbackState = TtsPlaybackState.STOPPED).isPlaying.shouldBeFalse()
    }

    @Test
    fun `playPauseAction PLAYING dispatches PAUSE`() {
        TtsPanelState(active = true, playbackState = TtsPlaybackState.PLAYING)
            .playPauseAction shouldBe TtsAction.PAUSE
    }

    @Test
    fun `playPauseAction PAUSED dispatches PLAY`() {
        TtsPanelState(active = true, playbackState = TtsPlaybackState.PAUSED)
            .playPauseAction shouldBe TtsAction.PLAY
    }

    @Test
    fun `playPauseAction STOPPED defaults to PLAY for defensive UX`() {
        // 正常情况下 pill 在 STOPPED 下不渲染 (shouldRender=false),但 race 下若渲染了,
        // 用户按按钮希望开始播放,而非 NoOp。这是行为契约,不是 bug。
        TtsPanelState(active = true, playbackState = TtsPlaybackState.STOPPED)
            .playPauseAction shouldBe TtsAction.PLAY
    }
}
