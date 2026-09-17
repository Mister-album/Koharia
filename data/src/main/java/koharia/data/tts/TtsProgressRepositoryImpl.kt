package koharia.data.tts

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import koharia.tts.progress.TtsProgressRepository
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import java.util.Date

/**
 * TTS 句子级进度的 SQLDelight 实现。
 *
 * 失败容忍策略：
 * - 读 / 写都用 try/catch；异常 logcat 但不抛（朗读主流程不能被 DB 错误打断）
 * - 写不需要事务：单条 UPSERT + 单条 DELETE 都是原子操作
 *
 * `getSentenceIndex` 返回 [Int] 但 SQLDelight `sentence_index` 列是 INTEGER → Long。
 * 句子下标理论上不会超过 Int.MAX_VALUE（章节文本长度上限），转换安全。
 */
class TtsProgressRepositoryImpl(
    private val database: Database,
) : TtsProgressRepository {

    override suspend fun getSentenceIndex(chapterId: Long): Int? {
        return try {
            withIOContext {
                database.tts_progressQueries
                    .getByChapterId(chapterId)
                    .awaitAsOneOrNull()
                    ?.sentence_index
                    ?.toInt()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "Failed to read TTS progress for chapterId=$chapterId"
            }
            null
        }
    }

    override suspend fun saveSentenceIndex(chapterId: Long, mangaId: Long, sentenceIndex: Int) {
        try {
            withIOContext {
                database.tts_progressQueries.upsert(
                    chapterId = chapterId,
                    mangaId = mangaId,
                    sentenceIndex = sentenceIndex.toLong(),
                    updatedAt = Date(),
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "Failed to save TTS progress chapterId=$chapterId index=$sentenceIndex"
            }
        }
    }

    override suspend fun clear(chapterId: Long) {
        try {
            withIOContext {
                database.tts_progressQueries.deleteByChapterId(chapterId)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "Failed to clear TTS progress for chapterId=$chapterId"
            }
        }
    }
}
