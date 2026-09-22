package eu.kanade.tachiyomi.ui.reader.loader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionPdfFileAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChapterLoaderPdfTest {
    @Test
    fun `adjacent preload leaves an uncached PDF untouched`() = runBlocking {
        val source = PdfSource()
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf"))

        loader(source).loadChapter(chapter, allowPdfDownload = false)

        assertEquals(0, source.prepareCalls)
        assertEquals(ReaderChapter.State.Wait, chapter.state)
        assertNull(chapter.pageLoader)
    }

    @Test
    fun `opening an uncached PDF requests the complete file and propagates its failure`() = runBlocking {
        val source = PdfSource()
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf"))

        val result = runCatching { loader(source).loadChapter(chapter) }

        assertEquals(1, source.prepareCalls)
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(source.failure.message, result.exceptionOrNull()?.message)
        assertEquals(source.failure.message, (chapter.state as ReaderChapter.State.Error).error.message)
    }

    @Test
    fun `explicit PDF opening waits for background cache lookup before preparing the file`() = runBlocking {
        val cacheLookupStarted = CompletableDeferred<Unit>()
        val releaseCacheLookup = CountDownLatch(1)
        val source = PdfSource(onCacheLookup = { lookup ->
            if (lookup == 1) {
                cacheLookupStarted.complete(Unit)
                check(releaseCacheLookup.await(5, TimeUnit.SECONDS))
            }
        })
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf"))
        val chapterLoader = loader(source)
        val background = async { chapterLoader.loadChapter(chapter, allowPdfDownload = false) }

        try {
            withTimeout(5000) { cacheLookupStarted.await() }
            val explicit = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { chapterLoader.loadChapter(chapter, allowPdfDownload = true) }
            }

            assertNull(withTimeoutOrNull(100) { explicit.await() })
            assertEquals(0, source.prepareCalls)
            assertEquals(ReaderChapter.State.Loading, chapter.state)
            releaseCacheLookup.countDown()

            val result = withTimeout(5000) {
                background.await()
                explicit.await()
            }
            assertEquals(2, source.cacheLookups)
            assertEquals(1, source.prepareCalls)
            assertTrue(result.exceptionOrNull() is IOException)
            assertEquals(source.failure.message, result.exceptionOrNull()?.message)
            assertEquals(source.failure.message, (chapter.state as ReaderChapter.State.Error).error.message)
        } finally {
            releaseCacheLookup.countDown()
        }
    }

    @Test
    fun `concurrent explicit requests share failures and a later retry starts a new attempt`() = runBlocking {
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = PdfSource(onPrepare = {
            preparing.complete(Unit)
            release.await()
        })
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf"))
        val chapterLoader = loader(source)
        val first = async { runCatching { chapterLoader.loadChapter(chapter) } }
        withTimeout(5000) { preparing.await() }
        val concurrent = List(3) {
            async(start = CoroutineStart.UNDISPATCHED) { runCatching { chapterLoader.loadChapter(chapter) } }
        }
        release.complete(Unit)
        (listOf(first) + concurrent).forEach {
            assertTrue(withTimeout(5000) { it.await() }.exceptionOrNull() is IOException)
        }
        assertEquals(1, source.prepareCalls)

        assertTrue(runCatching { chapterLoader.loadChapter(chapter) }.exceptionOrNull() is IOException)
        assertEquals(2, source.prepareCalls)
    }

    @Test
    fun `manual downloads take priority over the provider PDF cache`() = runBlocking {
        val source = PdfSource()
        val chapter = ReaderChapter(Chapter.create().copy(id = 1, mangaId = 2, url = "chapter.pdf"))

        loader(source, downloaded = true).loadChapter(chapter, allowPdfDownload = false)

        assertEquals(0, source.prepareCalls)
        assertEquals(0, source.cacheLookups)
        assertTrue(chapter.pageLoader is DownloadPageLoader)
        assertTrue(chapter.state is ReaderChapter.State.Loaded)
    }

    private fun loader(source: PdfSource, downloaded: Boolean = false): ChapterLoader {
        val downloads = mockk<DownloadManager>()
        every { downloads.isChapterDownloaded(any(), any(), any(), any(), any(), any()) } returns downloaded
        every { downloads.buildPageList(any(), any(), any()) } returns listOf(Page(0))
        val provider = mockk<DownloadProvider>()
        every { provider.findChapterDir(any(), any(), any(), any(), any()) } returns null
        return ChapterLoader(
            context = mockk(),
            downloadManager = downloads,
            downloadProvider = provider,
            manga = Manga.create().copy(id = 2, source = source.id),
            source = source,
            epubCacheManager = mockk(),
        )
    }

    private class PdfSource(
        private val onCacheLookup: (Int) -> Unit = {},
        private val onPrepare: suspend () -> Unit = {},
    ) : Source, ConnectionPdfFileAdapter {
        override val id = 42L
        override val name = "PDF test"
        val failure = IOException("Incomplete PDF")
        var prepareCalls = 0
        var cacheLookups = 0
        override fun isPdfChapter(chapterUrl: String) = true
        override fun findCompletePdfFile(chapterUrl: String): UniFile? {
            cacheLookups++
            onCacheLookup(cacheLookups)
            return null
        }
        override suspend fun preparePdfFile(chapterUrl: String): UniFile {
            prepareCalls++
            onPrepare()
            throw failure
        }
    }
}
