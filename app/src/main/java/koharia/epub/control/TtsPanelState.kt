package koharia.epub.control

import koharia.tts.TtsAction
import koharia.tts.TtsPlaybackState

/**
 * Phase 3.3：阅读器内朗读控制 pill 的**纯状态模型**。
 *
 * 与 [TtsControlPanel] Composable 解耦：Composable 只负责渲染与手势回调，
 * 所有"播放→暂停图标"、"按下 play 按钮应派发的动作"等决策都集中在这一个不可变
 * data class 上 → JVM 单测可覆盖，Composable 测试只需断言它消费了正确的 props。
 *
 * 设计动机：把图标选择和 action 映射从 Composable 抽出后，play/pause 图标的反转
 * 与 STOPPED 状态下的兜底动作只需在一个文件里测一次，而不是每次 Composable 重构
 * 都重新跑 Compose UI 测试。
 */
data class TtsPanelState(
    /** 当前是否有活跃播放；false → 整条 pill 隐藏（`shouldRender = false`）。 */
    val active: Boolean,
    /** 来自 [koharia.tts.TtsService.playbackState]，决定图标与按下动作。 */
    val playbackState: TtsPlaybackState,
) {
    /** pill 是否应当渲染。`active=false`（无播放或章节自然播完兜底结束）时整条隐藏。 */
    val shouldRender: Boolean get() = active

    /** 当前是否正在发声（用于决定图标是 ▶ 还是 ⏸）。 */
    val isPlaying: Boolean get() = playbackState == TtsPlaybackState.PLAYING

    /**
     * 用户按下中间按钮时应派发的动作。
     *
     * - PLAYING → PAUSE（让用户暂停）
     * - PAUSED  → PLAY（让用户恢复）
     * - STOPPED → PLAY（兜底：理论上 pill 不会在 STOPPED 下渲染，但若 race 下渲染了，
     *   按 PLAY 应当被 Service 的幂等检查忽略，不会出问题）
     */
    val playPauseAction: TtsAction
        get() = when (playbackState) {
            TtsPlaybackState.PLAYING -> TtsAction.PAUSE
            TtsPlaybackState.PAUSED,
            TtsPlaybackState.STOPPED,
            -> TtsAction.PLAY
        }
}
