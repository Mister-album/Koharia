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
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

class HttpPageLoaderCacheLeaseTest {
    @Test
    fun `retry retains one lease and repeated recycle releases it once`() = runBlocking {
        val cache = mockk<ChapterCache>(relaxed = true)
        val source = mockk<HttpSource>()
        every { source.id } returns 701L
        val chapter = ReaderChapter(Chapter.create().copy(id = 103, mangaId = 4))
        val url = "https://fixture.invalid/page"
        val page = ReaderPage(0, imageUrl = url).also { it.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val releases = AtomicInteger()
        every { cache.retainImage(701L, url) } returns Closeable { releases.incrementAndGet() }
        every { cache.isImageInCache(701L, url) } returns true
        coEvery { source.getImage(page) } answers {
            Response.Builder().request(Request.Builder().url(url).build()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("image".toResponseBody()).build()
        }
        every { cache.putImageToCache(701L, url, any()) } answers { thirdArg<Response>().close() }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            withTimeout(5000) {
                loader.setActivePage(page)
                page.statusFlow.first { it == Page.State.Ready }
                loader.retryPage(page)
                page.statusFlow.first { it == Page.State.Ready }
                loader.retryPage(page)
                page.statusFlow.first { it == Page.State.Ready }
            }
            verify(exactly = 1) { cache.retainImage(701L, url) }
            assertEquals(0, releases.get())
        } finally {
            loader.recycle()
            loader.recycle()
        }
        assertEquals(1, releases.get())
    }

    @Test
    fun `late URL resolution after recycle cannot acquire an image lease`() = runBlocking {
        val cache = mockk<ChapterCache>(relaxed = true)
        val source = mockk<HttpSource>()
        every { source.id } returns 701L
        val chapter = ReaderChapter(Chapter.create().copy(id = 104, mangaId = 4))
        val page = ReaderPage(0).also { it.chapter = chapter }
        chapter.state = ReaderChapter.State.Loaded(listOf(page))
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { source.getImageUrl(page) } coAnswers {
            withContext(NonCancellable) {
                requested.complete(Unit)
                finish.await()
            }
            "https://fixture.invalid/late"
        }
        val loader = HttpPageLoader(chapter, source, cache)
        try {
            withTimeout(5000) {
                loader.setActivePage(page)
                requested.await()
                loader.recycle()
                finish.complete(Unit)
                page.statusFlow.first { it == Page.State.Queue }
            }
            verify(exactly = 0) { cache.retainImage(701L, any()) }
            coVerify(exactly = 0) { source.getImage(any()) }
        } finally {
            loader.recycle()
            finish.complete(Unit)
        }
    }
}
