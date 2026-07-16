package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.repository.HistoryRepository

class RemoveHistoryTest {

    private val repository = mockk<HistoryRepository>()
    private val removeHistory = RemoveHistory(repository)

    @Test
    fun `awaitAll forwards source id to repository`() = runTest {
        coEvery { repository.deleteAllHistory(42L) } returns true

        removeHistory.awaitAll(42L) shouldBe true

        coVerify(exactly = 1) { repository.deleteAllHistory(42L) }
    }

    @Test
    fun `awaitAll defaults to all sources`() = runTest {
        coEvery { repository.deleteAllHistory(null) } returns true

        removeHistory.awaitAll() shouldBe true

        coVerify(exactly = 1) { repository.deleteAllHistory(null) }
    }
}
