package koharia.tts

/**
 * Phase 3.3：阅读器内朗读面板的派单指令。
 *
 * 由 `EpubReaderViewModel.onTtsAction` 构造并通过 [TtsService.dispatch] 派到
 * [TtsService]；[TtsService] 内部仍走 `ACTION_*` Intent + `dispatchControl` 串行队列，
 * 与通知栏 / 锁屏 / 蓝牙耳机共用同一执行路径 —— **in-app 与 wire 协议分层**。
 *
 * 注意：与 [TtsService] 内部 `ACTION_*` 常量是**不同层**的抽象：
 * - `TtsAction` 是给应用层 Composable / ViewModel 用的强类型 API；
 * - `ACTION_*` 是 `Context.startService(Intent)` 的 wire 协议。
 *
 * 新增动作：扩这个 enum + [TtsService.dispatch] 的 `when` 即可，不要直接调用 `startService`。
 */
enum class TtsAction {
    /** 从暂停恢复；非 PAUSED 时被 Service 忽略（幂等）。 */
    PLAY,

    /** 暂停当前播放；非 PLAYING 时被 Service 忽略（幂等）。 */
    PAUSE,

    /** 跳到上一句（下标 -1，下限 0）。 */
    PREV,

    /** 跳到下一句（下标 +1）。 */
    NEXT,

    /** 停止并 stopSelf。 */
    STOP,
}
