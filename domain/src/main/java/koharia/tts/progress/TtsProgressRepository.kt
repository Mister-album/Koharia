package koharia.tts.progress

/**
 * TTS 句子级朗读进度的持久化接口（Phase 2.5b）。
 *
 * 数据流：
 * - 用户朗读到第 N 句 → 服务定时 / 关闭时写入
 * - 用户下次点击"朗读" → 服务读取 → 从第 N 句附近继续（视口锚优先）
 *
 * 设计目标：
 * - 失败容忍：读 / 写失败不能阻塞朗读主流程（由实现负责 try/catch + logcat）
 * - 单表 CRUD：按 chapterId 主键 upsert；clear 仅在显式重置时调用
 *
 * 不依赖 SQLDelight / 任何持久化类型 — 业务接口层
 */
interface TtsProgressRepository {
    /**
     * 读取指定章节上次朗读到的句子下标。
     *
     * @param chapterId 章节主键
     * @return 上次保存的句子下标；无记录 / 读失败返回 null（调用方走锚定位回退）
     */
    suspend fun getSentenceIndex(chapterId: Long): Int?

    /**
     * 写入（或覆盖）指定章节当前朗读到的句子下标。
     *
     * 时间戳由实现自动生成（Date.now）。
     * 写入失败不应抛异常 — 实现内部 try/catch + logcat。
     */
    suspend fun saveSentenceIndex(chapterId: Long, mangaId: Long, sentenceIndex: Int)

    /**
     * 清除指定章节的持久化进度（章节重置 / 用户主动重置时调用）。
     * 不会清空阅读进度（由其他模块负责）。
     */
    suspend fun clear(chapterId: Long)
}
