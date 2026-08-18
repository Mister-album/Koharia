package koharia.epub.service

import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionSource
import koharia.epub.model.EpubOpenRequest
import koharia.epub.model.RemotePublicationRef
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class EpubPublicationResolverTest {

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
