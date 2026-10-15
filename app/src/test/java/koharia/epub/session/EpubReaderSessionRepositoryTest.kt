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
            every { close(any()) } answers {
                releaseEvents += "session"
                firstArg<() -> Unit>().invoke()
            }
        }
        var cacheLeaseReleased = false
        val repository = EpubReaderSessionRepository()
        repository.put(session)

        assertFalse(cacheLeaseReleased)
        repository.remove(7L) {
            releaseEvents += "cache"
            cacheLeaseReleased = true
        }

        verify(exactly = 1) { session.close(any()) }
        assertTrue(cacheLeaseReleased)
        assertEquals(listOf("session", "cache"), releaseEvents)
    }

    @Test
    fun `obsolete initialization cleanup retains replacement and its pagination session`() {
        fun session() = mockk<EpubReaderSession> {
            every { chapterId } returns 7L
            every { close(any()) } answers { firstArg<() -> Unit>().invoke() }
        }
        val old = session()
        val replacement = session()
        val pagination = session()
        val repository = EpubReaderSessionRepository()
        repository.put(old)
        repository.put(replacement)
        repository.putForPagination(pagination)

        repository.removeIfCurrent(old)

        assertTrue(repository.get(7L) === replacement)
        assertTrue(repository.getForPagination(7L) === pagination)
        verify(exactly = 1) { old.close(any()) }
        verify(exactly = 0) { replacement.close(any()) }
        verify(exactly = 0) { pagination.close(any()) }

        repository.removeIfCurrent(replacement)
        assertEquals(null, repository.get(7L))
        assertEquals(null, repository.getForPagination(7L))
        verify(exactly = 1) { replacement.close(any()) }
        verify(exactly = 1) { pagination.close(any()) }
    }

    @Test
    fun `publication and cache stay leased until its navigator is destroyed`() {
        val events = mutableListOf<String>()
        val publication = mockk<Publication> { every { close() } answers { events += "publication" } }
        val session = EpubReaderSession(7L, "Test", publication, mockk(), null, mockk())
        val repository = EpubReaderSessionRepository()
        repository.put(session)
        assertTrue(session.retain())
        repository.remove(7L) { events += "cache" }
        assertTrue(events.isEmpty())
        assertFalse(session.retain())
        assertEquals(null, repository.get(7L))
        session.release()
        assertEquals(listOf("publication", "cache"), events)
        session.close()
        verify(exactly = 1) { publication.close() }
    }

    @Test
    fun `callback registered during publication close waits for close to finish`() {
        val events = mutableListOf<String>()
        lateinit var session: EpubReaderSession
        val publication = mockk<Publication> {
            every { close() } answers {
                session.close { events += "late callback" }
                assertTrue(events.isEmpty())
                events += "publication"
            }
        }
        session = EpubReaderSession(7L, "Test", publication, mockk(), null, mockk())
        session.close { events += "first callback" }
        assertEquals(listOf("publication", "first callback", "late callback"), events)
    }

    @Test
    fun `cache release waits for both visible and pagination navigators`() {
        val events = mutableListOf<String>()
        fun session(label: String) = EpubReaderSession(
            7L,
            label,
            mockk { every { close() } answers { events += label } },
            mockk(),
            null,
            mockk(),
        )
        val visible = session("visible")
        val pagination = session("pagination")
        val repository = EpubReaderSessionRepository()
        repository.put(visible)
        repository.putForPagination(pagination)
        assertTrue(visible.retain())
        assertTrue(pagination.retain())
        repository.removeIfCurrent(visible) { events += "cache" }
        visible.release()
        assertEquals(listOf("visible"), events)
        pagination.release()
        assertEquals(listOf("visible", "pagination", "cache"), events)
    }

    @Test
    fun `failed publication close still releases every registered lease`() {
        val events = mutableListOf<String>()
        val session = EpubReaderSession(
            7L,
            "Test",
            mockk { every { close() } throws IllegalStateException("close failed") },
            mockk(),
            null,
            mockk(),
        )
        assertTrue(session.retain())
        session.close { events += "first" }
        session.close { events += "second" }
        session.release()
        assertEquals(listOf("first", "second"), events)
    }
}
