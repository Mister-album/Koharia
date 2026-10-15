package koharia.connection

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class ConnectionChapterFetchTest {
    private class SessionSource : Source, ConnectionChapterSessionAdapter {
        override val id = 41L
        override val name = "Fixture"
        var epoch = 0
        override fun captureChapterOwner(): ConnectionChapterOwner {
            val captured = epoch
            return ConnectionChapterOwner("fixture-$captured") {
                if (captured != epoch) throw CancellationException("Changed")
            }
        }
    }

    @Test
    fun `same source changed while request is suspended cannot publish its response`() = runTest {
        val source = SessionSource()
        val manager = mockk<SourceManager> { every { get(source.id) } returns source }
        val session = ChapterFetchSession.capture(source, ChapterFetchOrigin.EXPLICIT_REFRESH, manager)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val result = async {
            runCatching {
                session.fetch {
                    started.complete(Unit)
                    release.await()
                    listOf(SChapter.create())
                }
            }
        }
        started.await()
        source.epoch++
        release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is CancellationException)
    }

    @Test
    fun `owner is still checked after a successful response for SQL and download commits`() = runTest {
        val source = SessionSource()
        val manager = mockk<SourceManager> { every { get(source.id) } returns source }
        val fetched = ChapterFetchSession.capture(source, ChapterFetchOrigin.LEGACY_UPDATE, manager).fetch {
            emptyList()
        }
        fetched.checkCurrent()
        source.epoch++
        assertTrue(runCatching { fetched.checkCurrent() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `replacement source and registration after temporary capture invalidate old work`() = runTest {
        val source = SessionSource()
        for (initial in listOf<Source?>(null, source)) {
            var current = initial
            val manager = mockk<SourceManager> { every { get(source.id) } answers { current } }
            val session = ChapterFetchSession.capture(source, ChapterFetchOrigin.OPEN, manager)
            current = SessionSource()
            var requested = false
            assertTrue(
                runCatching {
                    session.fetch {
                        requested = true
                        emptyList()
                    }
                }.exceptionOrNull() is CancellationException,
            )
            assertTrue(!requested)
            assertTrue(
                runCatching {
                    ChapterFetchSession.capture(source, ChapterFetchOrigin.OPEN, manager)
                }.exceptionOrNull() is CancellationException,
            )
        }
    }

    @Test
    fun `all origins retain exact source owner and successful empty response`() = runTest {
        val source = SessionSource()
        val manager = mockk<SourceManager> { every { get(source.id) } returns source }
        for (origin in ChapterFetchOrigin.entries) {
            val fetched = ChapterFetchSession.capture(source, origin, manager).fetch { emptyList() }
            assertSame(source, fetched.source)
            assertEquals(origin, fetched.origin)
            assertEquals("fixture-0", fetched.ownerKey)
            assertTrue(fetched.chapters.isEmpty())
        }
    }

    @Test
    fun `a connection cannot silently omit its session adapter`() {
        val source = mockk<ConnectionSource> { every { id } returns 41 }
        val manager = mockk<SourceManager> { every { get(41) } returns source }
        assertTrue(
            runCatching {
                ChapterFetchSession.capture(source, ChapterFetchOrigin.OPEN, manager)
            }.exceptionOrNull() is IllegalStateException,
        )
    }
}
