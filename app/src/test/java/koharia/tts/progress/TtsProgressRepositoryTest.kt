package koharia.tts.progress

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.data.tts.TtsProgressRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.Tts_progressQueries
import java.util.Date

/**
 * TtsProgressRepositoryImpl 单测。
 *
 * 设计决策：mockk 模拟 SQLDelight [Database] / [Tts_progressQueries]。
 * 实际 SQL 语义由 SQLDelight 编译期保证；本测试聚焦于 Repository 的
 * 委托契约与异常吞咽逻辑（写失败不阻塞朗读主流程）。
 *
 * 注：读路径（getSentenceIndex）端到端测试受限于 `Query<Tts_progress>` mock 复杂度
 * （relaxed mock 无法可靠模拟 `awaitAsOneOrNull` 的 suspend 行为）；
 * SQL 语义由 SQLDelight 在 androidTest 层 [LegacyDatabaseSchemaBridgeTest] 端到端覆盖。
 *
 * 覆盖：
 * 1. save 委托给 upsert（参数正确）
 * 2. save 底层抛异常时 Repository 不再抛
 * 3. 同一 chapterId 多次 save 都触发 upsert（DB 端最后写入由 upsert 语义保证）
 * 4. clear 委托给 deleteByChapterId
 * 5. clear 底层抛异常时 Repository 不再抛
 * 6. save 使用 `Date()` 时间戳（落在调用前后时间窗内）
 */
class TtsProgressRepositoryTest {

    private fun newRepo(): Pair<Tts_progressQueries, TtsProgressRepositoryImpl> {
        val queries = mockk<Tts_progressQueries>(relaxed = true)
        val database = mockk<Database>(relaxed = true)
        every { database.tts_progressQueries } returns queries
        return queries to TtsProgressRepositoryImpl(database)
    }

    @Test
    fun `save delegates to upsert with correct arguments`() = runBlocking {
        val (queries, repo) = newRepo()
        val chapterId = 42L
        val mangaId = 1L
        val sentenceIndex = 7

        repo.saveSentenceIndex(chapterId, mangaId, sentenceIndex)

        coVerify {
            queries.upsert(
                chapterId = chapterId,
                mangaId = mangaId,
                sentenceIndex = sentenceIndex.toLong(),
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `save swallows underlying exception and does not propagate`() = runBlocking {
        val (queries, repo) = newRepo()
        val chapterId = 42L

        coEvery {
            queries.upsert(
                chapterId = chapterId,
                mangaId = any(),
                sentenceIndex = any(),
                updatedAt = any(),
            )
        } throws RuntimeException("simulated DB failure")

        // 不抛异常（即便 SQLDelight 抛了）— 调用本身不 throw 即视为通过
        repo.saveSentenceIndex(chapterId, 1L, 5)
    }

    @Test
    fun `save twice for same chapterId both writes are delegated to upsert`() = runBlocking {
        val (queries, repo) = newRepo()
        val chapterId = 42L
        val mangaId = 1L

        repo.saveSentenceIndex(chapterId, mangaId, 5)
        repo.saveSentenceIndex(chapterId, mangaId, 9)

        // 两次 upsert 都被调用（最后写入赢由 SQLDelight upsert 语义保证）
        coVerify(exactly = 2) {
            queries.upsert(
                chapterId = chapterId,
                mangaId = mangaId,
                sentenceIndex = any(),
                updatedAt = any(),
            )
        }
    }

    @Test
    fun `clear delegates to deleteByChapterId`() = runBlocking {
        val (queries, repo) = newRepo()
        val chapterId = 42L

        coEvery { queries.deleteByChapterId(chapterId) } returns 1
        repo.clear(chapterId)

        coVerify { queries.deleteByChapterId(chapterId) }
    }

    @Test
    fun `clear swallows underlying exception and does not propagate`() = runBlocking {
        val (queries, repo) = newRepo()
        val chapterId = 42L

        coEvery { queries.deleteByChapterId(chapterId) } throws RuntimeException("simulated DB failure")

        // 不抛
        repo.clear(chapterId)
    }

    @Test
    fun `save calls upsert with Date() timestamp within current wall-clock window`() = runBlocking {
        val (queries, repo) = newRepo()
        val before = System.currentTimeMillis()

        repo.saveSentenceIndex(42L, 1L, 5)

        val after = System.currentTimeMillis()

        coVerify {
            queries.upsert(
                chapterId = 42L,
                mangaId = 1L,
                sentenceIndex = 5L,
                updatedAt = match { date: Date ->
                    date.time in before..after
                },
            )
        }
    }
}
