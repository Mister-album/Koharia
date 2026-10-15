package koharia.epub.service

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import koharia.connection.ConnectionSource
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.RemotePublicationRef
import koharia.epub.session.EpubReaderSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class EpubPublicationResolverTest {

    private val localRequest = EpubOpenRequest(
        mangaId = 1L,
        chapterId = 2L,
        sourceId = 42L,
        title = "Book",
        remotePublication = RemotePublicationRef("komga", "resource-1"),
        localUri = "content://fixture/book.epub",
        openSource = EpubOpenRequest.OpenSource.LOCAL,
    )

    @Test
    fun `cancelled local open never attempts remote fallback`() = runTest {
        val local = mockk<LocalEpubPublicationService> {
            coEvery { open(any(), any()) } throws CancellationException("superseded")
        }
        val sources = mockk<SourceManager>()
        val failure = runCatching { EpubPublicationResolver(local, sources).open(localRequest, null) }

        assertTrue(failure.exceptionOrNull() is CancellationException)
        verify(exactly = 0) { sources.get(any()) }
    }

    @Test
    fun `cancelled resolver closes a session returned by a noncooperative opener`() = runTest {
        val session = mockk<EpubReaderSession> { every { close() } just runs }
        val local = mockk<LocalEpubPublicationService> {
            coEvery { open(any(), any()) } coAnswers {
                currentCoroutineContext().job.cancel()
                session
            }
        }
        val sources = mockk<SourceManager>()
        val request = async { EpubPublicationResolver(local, sources).open(localRequest, null) }
        request.join()

        assertTrue(request.isCancelled)
        verify(exactly = 1) { session.close() }
        verify(exactly = 0) { sources.get(any()) }
    }

    @Test
    fun `ordinary local failure still attempts remote fallback`() = runTest {
        val local = mockk<LocalEpubPublicationService> {
            coEvery { open(any(), any()) } throws IllegalStateException("cache unreadable")
        }
        val sources = mockk<SourceManager> { every { get(42L) } returns null }
        val failure = runCatching { EpubPublicationResolver(local, sources).open(localRequest, null) }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        verify(exactly = 1) { sources.get(42L) }
    }

    @Test
    fun `remote publication provider must match connection provider`() {
        val connectionSource = mockk<ConnectionSource> {
            every { providerId } returns "komga"
        }
        val sourceManager = mockk<SourceManager> {
            every { get(42L) } returns connectionSource
        }
        val resolver = EpubPublicationResolver(
            localService = mockk(),
            sourceManager = sourceManager,
        )
        val request = EpubOpenRequest(
            mangaId = 1L,
            chapterId = 2L,
            sourceId = 42L,
            title = "Book",
            remotePublication = RemotePublicationRef(
                providerId = "future-provider",
                resourceId = "resource-1",
            ),
            localUri = null,
            openSource = EpubOpenRequest.OpenSource.REMOTE,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runTest { resolver.open(request, initialLocator = null) }
        }
    }
}
