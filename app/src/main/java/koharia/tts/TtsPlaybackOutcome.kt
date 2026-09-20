package koharia.tts

/**
 * 一次章节朗读结束后的判定（review P1 round 2/3）。
 *
 * 把"章节是否真的播完"抽成纯函数，避免再次出现下面两次回归：
 *  - round 2：只看 `played == 0`，于是"第一句成功、后续全失败"被当成播完并跳章；
 *  - round 3：`failed` 只统计合成返回 null，覆盖不到"合成成功但解码/写入失败"的句，
 *    只要另有一句成功就仍被判为播完。
 *
 * 判据：**只有每一句都成功发声才算播完**。
 */
enum class TtsPlaybackOutcome {
    /** 每句都成功发声：可通知阅读器续播下一章。 */
    COMPLETE,

    /** 一句都没发声（无效 key / 断网 / 限流 / 全部解码失败）：提示失败并停止。 */
    NO_AUDIO,

    /** 部分句失败：不得跳章，提示失败并停在当前位置。 */
    PARTIAL_FAILURE,
}

/**
 * @param played 本轮**真正写出非空 PCM**的句数（合成成功 ∧ 解码成功 ∧ 未被代次丢弃）。
 * @param failed 本轮失败句数（合成返回 null ∧ 解码/写入失败；不含被 skipTo/stop 抢占的丢弃）。
 */
fun classifyTtsPlayback(played: Int, failed: Int): TtsPlaybackOutcome = when {
    played <= 0 -> TtsPlaybackOutcome.NO_AUDIO
    failed > 0 -> TtsPlaybackOutcome.PARTIAL_FAILURE
    else -> TtsPlaybackOutcome.COMPLETE
}
