package koharia.connection

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.reader.model.ReaderProgressSession
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.source.service.SourceManager

class ReaderProgressSessionTest {
    private abstract class BoundSource : Source, ConnectionPageProgressSessionAdapter
    private val chapter = Chapter.create().copy(id = 3, mangaId = 2, url = "book")

    @Test
    fun `captured source is never replaced by a new source with the same id`() = runTest {
        val original = mockk<BoundSource>()
        var current: Source = original
        val manager = mockk<SourceManager> { every { get(1L) } answers { current } }
        val adapter = mockk<ConnectionReaderPageProgressAdapter>()
        coEvery { original.capturePageProgressSession(chapter) } returns ConnectionProgressSession(adapter) {}
        val session = ReaderProgressSession.capture(original, 1, chapter, manager)
        assertSame(adapter, session.pages)
        current = mockk<BoundSource>()
        assertTrue(runCatching { session.checkCurrent() }.exceptionOrNull() is CancellationException)
        assertTrue(
            runCatching { ReaderProgressSession.capture(original, 1, chapter, manager) }
                .exceptionOrNull() is CancellationException,
        )
        coVerify(exactly = 1) { original.capturePageProgressSession(chapter) }
    }

    @Test
    fun `local and remote actions reject a changed owner and a different chapter before delegation`() = runTest {
        val delegate = mockk<ConnectionReaderPageProgressAdapter>()
        var current = true
        val captured = CapturedPageProgress("book", delegate) {
            if (!current) throw CancellationException("Replaced")
        }
        assertTrue(runCatching { captured.beginReadingSession("other") }.exceptionOrNull() is IllegalArgumentException)
        current = false
        val operations: List<suspend () -> Unit> = listOf(
            { captured.beginReadingSession("book") },
            { captured.pullPageProgress("book", JsonObject(emptyMap())) },
            { captured.pushPageProgress("book", 1, 10) },
            { captured.recordLocalPageProgress("book", 1, 10, 100) },
            { captured.confirmLocalPageProgress("book", 1, 10, 100) },
            { captured.acceptRemotePageProgress("book", 1, 10, 100) },
        )
        operations.forEach { assertTrue(runCatching { it() }.exceptionOrNull() is CancellationException) }
        coVerify { delegate wasNot Called }
    }

    @Test
    fun `late response cannot escape the captured session after replacement`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var current = true
        val delegate = mockk<ConnectionReaderPageProgressAdapter>()
        coEvery { delegate.pullPageProgress(any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            null
        }
        val captured = CapturedPageProgress("book", delegate) {
            if (!current) throw CancellationException("Replaced")
        }
        supervisorScope {
            val result = async { captured.pullPageProgress("book", JsonObject(emptyMap())) }
            entered.await()
            current = false
            release.complete(Unit)
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
        }
    }
}
