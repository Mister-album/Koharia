package tachiyomi.domain.history.interactor

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

class GetHistoryTest {

    private val repository = mockk<HistoryRepository>()
    private val getHistory = GetHistory(repository)

    @Test
    fun `subscribe forwards source id to repository`() {
        val expected = emptyFlow<List<HistoryWithRelations>>()
        every { repository.getHistory("query", 42L) } returns expected

        getHistory.subscribe("query", 42L) shouldBe expected

        verify(exactly = 1) { repository.getHistory("query", 42L) }
    }

    @Test
    fun `subscribe defaults to all sources`() {
        val expected = emptyFlow<List<HistoryWithRelations>>()
        every { repository.getHistory("", null) } returns expected

        getHistory.subscribe("") shouldBe expected

        verify(exactly = 1) { repository.getHistory("", null) }
    }
}
