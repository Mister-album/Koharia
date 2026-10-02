package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionPageAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class HttpPageLoaderPriorityTest {
    @Test
    fun `cache miss does not block cached spread page while network is deferred`() = runBlocking {
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(ConnectionPageAdapter::class))
        every { (source as ConnectionPageAdapter).pageLoadConcurrency } returns 1
        every { (source as ConnectionPageAdapter).pagePrefetchOnActivate } returns true
        every { (source as ConnectionPageAdapter).pagePrefetchSize } returns 2
        val cache = mockk<ChapterCache>(relaxed = true)
        val chapter = ReaderChapter(Chapter.create().copy(id = 13, mangaId = 4))
        val pages = List(4) { ReaderPage(it, imageUrl = "http://localhost/$it").also { it.chapter = chapter } }
        chapter.state = ReaderChapter.State.Loaded(pages)
        every { cache.isImageInCache("http://localhost/1") } returns true
        every { cache.inspectImageCache("http://localhost/1") } returns mockk(relaxed = true) {
            every { entryPresent } returns true
        }
        val requested = CompletableDeferred<Unit>()
        coEvery { source.getImage(pages[0]) } coAnswers {
            requested.complete(Unit)
            awaitCancellation()
        }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            withTimeout(5000) {
                loader.setNetworkRequestsDeferred(true)
                loader.setActivePages(listOf(pages[0], pages[1]))
                pages[1].statusFlow.first { it == Page.State.Ready }
                assertEquals(Page.State.Queue, pages[0].status)
                coVerify(exactly = 0) { source.getImage(any()) }
                coVerify(exactly = 0) { source.getImageUrl(any()) }
                loader.setNetworkRequestsDeferred(false)
                requested.await()
                coVerify(exactly = 1) { source.getImage(pages[0]) }
                coVerify(exactly = 0) { source.getImage(pages[1]) }
            }
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `cached preview suppresses prefetch until network is released`() = runBlocking {
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(ConnectionPageAdapter::class))
        every { (source as ConnectionPageAdapter).pageLoadConcurrency } returns 1
        every { (source as ConnectionPageAdapter).pagePrefetchOnActivate } returns true
        every { (source as ConnectionPageAdapter).pagePrefetchSize } returns 1
        val cache = mockk<ChapterCache>(relaxed = true)
        val chapter = ReaderChapter(Chapter.create().copy(id = 14, mangaId = 4))
        val pages = List(3) { ReaderPage(it, imageUrl = "http://localhost/$it").also { it.chapter = chapter } }
        chapter.state = ReaderChapter.State.Loaded(pages)
        every { cache.isImageInCache("http://localhost/0") } returns true
        every { cache.inspectImageCache("http://localhost/0") } returns mockk(relaxed = true) {
            every { entryPresent } returns true
        }
        val requested = CompletableDeferred<Unit>()
        coEvery { source.getImage(pages[1]) } coAnswers {
            requested.complete(Unit)
            awaitCancellation()
        }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            withTimeout(5000) {
                loader.setNetworkRequestsDeferred(true)
                loader.setActivePage(pages[0])
                pages[0].statusFlow.first { it == Page.State.Ready }
                loader.onPageDisplayed(pages[0])
                coVerify(exactly = 0) { source.getImage(any()) }
                loader.setNetworkRequestsDeferred(false)
                requested.await()
            }
        } finally {
            loader.recycle()
        }
    }

    @Test
    fun `uncached preview with unknown image URL is skipped after selecting another page`() = runBlocking {
        val source = mockk<HttpSource>(moreInterfaces = arrayOf(ConnectionPageAdapter::class))
        every { (source as ConnectionPageAdapter).pageLoadConcurrency } returns 1
        every { (source as ConnectionPageAdapter).pagePrefetchOnActivate } returns false
        every { (source as ConnectionPageAdapter).pagePrefetchSize } returns 1
        val cache = mockk<ChapterCache>(relaxed = true)
        val chapter = ReaderChapter(Chapter.create().copy(id = 15, mangaId = 4))
        val pages = List(3) { ReaderPage(it, imageUrl = "http://localhost/$it").also { it.chapter = chapter } }
        pages[0].imageUrl = null
        chapter.state = ReaderChapter.State.Loaded(pages)
        val requested = CompletableDeferred<Unit>()
        coEvery { source.getImage(pages[2]) } coAnswers {
            requested.complete(Unit)
            awaitCancellation()
        }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            withTimeout(5000) {
                loader.setNetworkRequestsDeferred(true)
                loader.setActivePage(pages[0])
                loader.setActivePage(pages[2])
                loader.setNetworkRequestsDeferred(false)
                requested.await()
                coVerify(exactly = 0) { source.getImageUrl(any()) }
                coVerify(exactly = 0) { source.getImage(pages[0]) }
            }
        } finally {
            loader.recycle()
        }
    }

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
