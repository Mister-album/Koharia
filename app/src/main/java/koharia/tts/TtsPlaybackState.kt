package koharia.tts

/**
 * Phase 3.3：TTS 播放 UI 状态（与 [TtsService] 内部 `PlaybackUiState` 合并的公共版本）。
 *
 * 状态机：
 * - [STOPPED]（初始）：从未开始 / 已 `stopSelf()` / 章节自然播完 + 兜底超时未接管。
 * - [PLAYING]：正在播放；用户点暂停 / 通知栏 pause / 锁屏 pause / 蓝牙 pause 切到 [PAUSED]。
 * - [PAUSED]：队列保留，按 resume 续播。
 *
 * 暴露在 [TtsService] 的 companion（进程级 `MutableStateFlow`），阅读器可直接
 * `TtsService.playbackState.collectAsState()` 观察，以决定 `TtsControlPanel` 的图标与动作。
 */
enum class TtsPlaybackState {
    PLAYING,
    PAUSED,
    STOPPED,
}
