package koharia.epub.session

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Publication

class EpubReaderSessionRepositoryTest {

    @Test
    fun `session close is idempotent`() {
        val publication = mockk<Publication> {
            every { close() } just runs
        }
        val session = EpubReaderSession(
            chapterId = 7L,
            title = "Test",
            publication = publication,
            navigatorFactory = mockk(),
            initialLocator = null,
            positionsController = mockk(),
        )

        session.close()
        session.close()

        verify(exactly = 1) { publication.close() }
    }

    @Test
    fun `removed session closes before cache lease is released`() {
        val releaseEvents = mutableListOf<String>()
        val session = mockk<EpubReaderSession> {
            every { chapterId } returns 7L
            every { close() } answers { releaseEvents += "session" }
        }
        var cacheLeaseReleased = false
        val repository = EpubReaderSessionRepository()
        repository.put(session)

        assertFalse(cacheLeaseReleased)
        repository.remove(7L) {
            releaseEvents += "cache"
            cacheLeaseReleased = true
        }

        verify(exactly = 1) { session.close() }
        assertTrue(cacheLeaseReleased)
        assertEquals(listOf("session", "cache"), releaseEvents)
    }
}
