package tachiyomi.domain.updates.interactor

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Test
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import java.time.Instant

class GetUpdatesTest {

    private val repository = mockk<UpdatesRepository>()
    private val getUpdates = GetUpdates(repository)

    @Test
    fun `subscribe forwards source id and filters to repository`() {
        val expected = emptyFlow<List<UpdatesWithRelations>>()
        every {
            repository.subscribeAll(
                after = 123L,
                limit = 500,
                unread = true,
                started = false,
                bookmarked = true,
                hideExcludedScanlators = true,
                sourceId = 42L,
            )
        } returns expected

        getUpdates.subscribe(
            instant = Instant.ofEpochMilli(123L),
            unread = true,
            started = false,
            bookmarked = true,
            hideExcludedScanlators = true,
            sourceId = 42L,
        ) shouldBe expected

        verify(exactly = 1) {
            repository.subscribeAll(
                after = 123L,
                limit = 500,
                unread = true,
                started = false,
                bookmarked = true,
                hideExcludedScanlators = true,
                sourceId = 42L,
            )
        }
    }

    @Test
    fun `subscribe defaults to all sources`() {
        val expected = emptyFlow<List<UpdatesWithRelations>>()
        every {
            repository.subscribeAll(
                after = 123L,
                limit = 500,
                unread = null,
                started = null,
                bookmarked = null,
                hideExcludedScanlators = false,
                sourceId = null,
            )
        } returns expected

        getUpdates.subscribe(
            instant = Instant.ofEpochMilli(123L),
            unread = null,
            started = null,
            bookmarked = null,
            hideExcludedScanlators = false,
        ) shouldBe expected

        verify(exactly = 1) {
            repository.subscribeAll(
                after = 123L,
                limit = 500,
                unread = null,
                started = null,
                bookmarked = null,
                hideExcludedScanlators = false,
                sourceId = null,
            )
        }
    }
}
