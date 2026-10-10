package koharia.komga.domain.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import koharia.connection.ConnectionOrganizationPage
import koharia.komga.api.KomgaOrganization
import koharia.komga.api.KomgaOrganizationApi
import koharia.komga.api.KomgaOrganizationKind
import koharia.komga.api.KomgaOrganizationQuery
import koharia.komga.api.KomgaOrganizationUpdate
import koharia.komga.api.KomgaOrganizationUpdates
import koharia.komga.api.dto.PageWrapperDto
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KomgaOrganizationDirectoryTest {
    private val api = mockk<KomgaOrganizationApi>()
    private val repository = mockk<KomgaOrganizationRepository> {
        every { namespace } returns "account-a"
        every { source.id } returns 42L
        every { checkActive() } returns Unit
    }.also { every { it.api } returns api }

    @Test
    fun `directory loads all names with typed identities and refreshes only when requested`() = runTest {
        coEvery { api.list(any(), any(), any()) } answers {
            PageWrapperDto(listOf(KomgaOrganization("same-id", "Same name")), totalPages = 1)
        }
        val directory = KomgaOrganizationDirectory(repository)
        val entries = directory.entries(ConnectionOrganizationPage.entries, refresh = false)
        assertEquals(ConnectionOrganizationPage.entries, entries.map { it.page })
        assertEquals(2, entries.map { it.key }.distinct().size)
        assertEquals(listOf("Same name", "Same name"), entries.map { it.name })
        directory.entries(listOf(ConnectionOrganizationPage.READ_LISTS), refresh = true)
        coVerify(exactly = 1) {
            api.list(KomgaOrganizationKind.COLLECTION, KomgaOrganizationQuery(unpaged = true), false)
        }
        coVerify(exactly = 1) {
            api.list(KomgaOrganizationKind.READ_LIST, KomgaOrganizationQuery(unpaged = true), false)
        }
        coVerify(exactly = 1) {
            api.list(KomgaOrganizationKind.READ_LIST, KomgaOrganizationQuery(unpaged = true), true)
        }
        verify(exactly = 4) { repository.checkActive() }
    }

    @Test
    fun `progress and another connection never invalidate the name directory`() = runTest {
        var changes = 0
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            KomgaOrganizationDirectory(repository).changes.collect { changes++ }
        }
        KomgaOrganizationUpdates.notify(KomgaOrganizationUpdate(43L))
        KomgaOrganizationUpdates.notify(KomgaOrganizationUpdate(42L, progressOnly = true))
        assertEquals(0, changes)
        KomgaOrganizationUpdates.notify(KomgaOrganizationUpdate(42L))
        assertEquals(1, changes)
        job.cancel()
    }

    @Test
    fun `account change during loading rejects the returned names`() = runTest {
        coEvery { api.list(any(), any(), any()) } answers {
            every { repository.checkActive() } throws KomgaOrganizationException(
                KomgaOrganizationFailure.ACCOUNT_CHANGED,
            )
            PageWrapperDto(emptyList(), totalPages = 1)
        }
        assertThrows(KomgaOrganizationException::class.java) {
            kotlinx.coroutines.runBlocking {
                KomgaOrganizationDirectory(repository).entries(ConnectionOrganizationPage.entries, false)
            }
        }
    }
}
