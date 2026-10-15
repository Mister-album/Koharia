package koharia.connection

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager

class ConnectionMetadataCommitTest {
    private abstract class OwnedSource : Source, ConnectionChapterSessionAdapter

    @Test
    fun `late metadata after replacement never reaches SQL or file effects`() = runTest {
        val fixture = Fixture()
        val source = mockk<OwnedSource>()
        every { source.id } returns fixture.local.source
        every { source.captureChapterOwner() } returns ConnectionChapterOwner("owned") {}
        var current: Source? = source
        val manager = mockk<SourceManager> { every { get(fixture.local.source) } answers { current } }
        val session = ConnectionSourceSession.capture(source, manager)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                session.checkCurrent()
                started.complete(Unit)
                release.await()
                fixture.commit(session::checkCurrent)
            }
        }
        started.await()
        current = mockk()
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { fixture.repository.updateFromSource(any(), any(), any()) }
        fixture.noFileEffects()
    }

    @Test
    fun `transaction cancellation cannot evict a cover or rename downloads`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.repository.updateFromSource(any(), any(), any()) } throws CancellationException("Retired")
        assertTrue(runCatching { fixture.commit {} }.exceptionOrNull() is CancellationException)
        fixture.noFileEffects()
    }

    @Test
    fun `missing row does not cause filesystem effects`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.repository.updateFromSource(any(), any(), any()) } returns false
        assertFalse(fixture.commit {})
        fixture.noFileEffects()
    }

    @Test
    fun `successful current commit precedes file effects`() = runTest {
        val fixture = Fixture()
        var committed = false
        coEvery { fixture.repository.updateFromSource(any(), any(), any()) } coAnswers {
            thirdArg<suspend () -> Unit>().invoke()
            committed = true
            true
        }
        every { fixture.covers.deleteFromCache(fixture.local) } answers {
            assertTrue(committed)
            1
        }
        coEvery { fixture.downloads.renameManga(fixture.local, "New title") } coAnswers { assertTrue(committed) }
        assertTrue(fixture.commit {})
        verify(exactly = 1) { fixture.covers.deleteFromCache(fixture.local) }
        coVerify(exactly = 1) { fixture.downloads.renameManga(fixture.local, "New title") }
    }

    private class Fixture {
        val repository = mockk<MangaRepository>()
        val covers = mockk<CoverCache>()
        val downloads = mockk<DownloadManager>()
        private val preferences = mockk<LibraryPreferences>()
        val local = Manga.create().copy(id = 1, source = 41, url = "owned", title = "Old title")
        private val remote = SManga.create().apply {
            title = "New title"
            url = "owned"
            thumbnail_url = "https://fixture.invalid/cover"
        }
        private val interactor = UpdateManga(repository, mockk())

        suspend fun commit(checkCurrent: suspend () -> Unit) = interactor.awaitUpdateFromSource(
            local,
            remote,
            true,
            checkCurrent,
            covers,
            preferences,
            downloads,
        )

        fun noFileEffects() {
            verify(exactly = 0) { covers.deleteFromCache(any()) }
            coVerify(exactly = 0) { downloads.renameManga(any(), any()) }
        }
    }
}
