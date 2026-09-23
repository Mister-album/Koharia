package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionPageAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class HttpPageLoaderPriorityTest {
    @Test
    fun `provider can retain prefetch on activation`() = runBlocking {
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(ConnectionPageAdapter::class))
        every { (source as ConnectionPageAdapter).pageLoadConcurrency } returns 2
        every { (source as ConnectionPageAdapter).pagePrefetchOnActivate } returns true
        every { (source as ConnectionPageAdapter).pagePrefetchSize } returns null
        val cache = mockk<ChapterCache>(relaxed = true)
        val chapter = ReaderChapter(Chapter.create().copy(id = 11, mangaId = 4))
        val pages = List(10) { ReaderPage(it, imageUrl = "http://localhost/$it").also { it.chapter = chapter } }
        chapter.state = ReaderChapter.State.Loaded(pages)
        val requested = List(10) { CompletableDeferred<Unit>() }
        coEvery { source.getImage(any()) } coAnswers {
            requested[firstArg<Page>().index].complete(Unit)
            awaitCancellation()
        }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            withTimeout(5000) {
                loader.setActivePage(pages[4])
                requested[4].await()
                requested[5].await()
            }
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `adjacent prefetch waits for visible page and yields to a new spread`() = runBlocking {
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(ConnectionPageAdapter::class))
        every { (source as ConnectionPageAdapter).pageLoadConcurrency } returns 2
        every { (source as ConnectionPageAdapter).pagePrefetchOnActivate } returns false
        every { (source as ConnectionPageAdapter).pagePrefetchSize } returns 2
        val cache = mockk<ChapterCache>(relaxed = true)
        val chapter = ReaderChapter(Chapter.create().copy(id = 12, mangaId = 4))
        val pages = List(104) { ReaderPage(it, imageUrl = "http://localhost/$it").also { it.chapter = chapter } }
        chapter.state = ReaderChapter.State.Loaded(pages)
        val requested = List(104) { CompletableDeferred<Unit>() }
        val cancelled = CompletableDeferred<Unit>()
        val activeCancelled = CompletableDeferred<Unit>()
        coEvery { source.getImage(any()) } coAnswers {
            val page = firstArg<Page>()
            requested[page.index].complete(Unit)
            try {
                awaitCancellation()
            } finally {
                if (page.index == 100) cancelled.complete(Unit)
                if (page.index == 99) activeCancelled.complete(Unit)
            }
        }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            // CI runners can stall the cancellation propagation past 5 s when the gradle JVM
            // daemon shares the runner's overloaded CPU; locally the test completes in ~1.7 s.
            // 10 s gives a 5x margin without changing any production scheduling logic.
            withTimeout(10_000) {
                loader.setActivePage(pages[99])
                requested[99].await()
                assertFalse(requested[100].isCompleted)
                loader.onPageDisplayed(pages[99])
                requested[100].await()
                loader.setActivePages(listOf(pages[98], pages[99]))
                cancelled.await()
                requested[98].await()
                assertFalse(activeCancelled.isCompleted)
            }
        } finally {
            loader.recycle()
        }
    }
}
