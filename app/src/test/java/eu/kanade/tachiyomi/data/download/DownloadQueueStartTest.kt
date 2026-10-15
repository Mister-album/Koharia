package eu.kanade.tachiyomi.data.download

import android.content.Context
import eu.kanade.tachiyomi.data.download.model.Download
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class DownloadQueueStartTest {
    @Test
    fun `pause all retains existing items when a later automatic batch starts`() {
        val running = download(1, Download.State.DOWNLOADING)
        val waiting = download(2, Download.State.QUEUE)
        val failed = download(3, Download.State.ERROR)
        val queue = MutableStateFlow(listOf(running, waiting, failed))
        val context = mockk<Context>()
        val downloader = mockk<Downloader>(relaxed = true) {
            every { isRunning } returns false
            every { queueState } returns queue
            every { pause() } answers { running.status = Download.State.QUEUE }
            every { pause(any()) } answers {
                val item = firstArg<Download>()
                if (item.status == Download.State.QUEUE || item.status == Download.State.DOWNLOADING) {
                    item.status = Download.State.PAUSED
                }
            }
        }
        val manager = DownloadManager(context, mockk(), mockk(), mockk(), mockk(), mockk(), downloader, mockk())
        mockkObject(DownloadJob.Companion)
        try {
            every { DownloadJob.isRunning(context) } returns false
            every { DownloadJob.start(context) } returns Unit
            manager.pauseDownloads()
            assertEquals(Download.State.PAUSED, running.status)
            assertEquals(Download.State.PAUSED, waiting.status)
            assertEquals(Download.State.ERROR, failed.status)
            queue.value += download(4, Download.State.QUEUE)
            manager.startQueuedDownloadsOnly()
            assertEquals(Download.State.PAUSED, running.status)
            assertEquals(Download.State.PAUSED, waiting.status)
            assertEquals(Download.State.ERROR, failed.status)
            verify(exactly = 0) { downloader.resumeAllPaused() }
            verify(exactly = 1) { DownloadJob.start(context) }
        } finally {
            unmockkObject(DownloadJob.Companion)
        }
    }

    @Test
    fun `only selected retry targets resume and worker starts once`() {
        val paused = download(1, Download.State.PAUSED)
        val failed = download(2, Download.State.ERROR)
        val selected = download(3, Download.State.ERROR)
        val selectedPaused = download(4, Download.State.PAUSED)
        val queue = MutableStateFlow(listOf(paused, failed, selected, selectedPaused))
        val context = mockk<Context>()
        val downloader = mockk<Downloader>(relaxed = true) {
            every { isRunning } returns false
            every { queueState } returns queue
            every { resume(any()) } answers {
                firstArg<Download>().status = Download.State.QUEUE
                true
            }
        }
        val manager = DownloadManager(context, mockk(), mockk(), mockk(), mockk(), mockk(), downloader, mockk())
        mockkObject(DownloadJob.Companion)
        try {
            every { DownloadJob.isRunning(context) } returns false
            every { DownloadJob.start(context) } returns Unit
            manager.resumeDownloads(listOf(3, 3, 4, 99))
            assertEquals(Download.State.PAUSED, paused.status)
            assertEquals(Download.State.ERROR, failed.status)
            assertEquals(Download.State.QUEUE, selected.status)
            assertEquals(Download.State.QUEUE, selectedPaused.status)
            verify(exactly = 1) { downloader.resume(selected) }
            verify(exactly = 1) { downloader.resume(selectedPaused) }
            verify(exactly = 0) { downloader.resumeAllPaused() }
            verify(exactly = 1) { DownloadJob.start(context) }
        } finally {
            unmockkObject(DownloadJob.Companion)
        }
    }

    @Test
    fun `reader queue return keeps paused state and current duplicate instead of stale object`() {
        val paused = download(1, Download.State.PAUSED)
        val failed = download(2, Download.State.ERROR)
        val returned = download(3, Download.State.NOT_DOWNLOADED)
        val staleDuplicate = download(1, Download.State.NOT_DOWNLOADED)
        val queue = MutableStateFlow(listOf(paused, failed))
        val context = mockk<Context>()
        val downloader = mockk<Downloader>(relaxed = true) {
            every { isRunning } returns false
            every { queueState } returns queue
            every { updateQueue(any()) } answers { queue.value = firstArg() }
        }
        val manager = DownloadManager(context, mockk(), mockk(), mockk(), mockk(), mockk(), downloader, mockk())
        mockkObject(DownloadJob.Companion)
        try {
            every { DownloadJob.isRunning(context) } returns false
            every { DownloadJob.start(context) } returns Unit
            manager.addDownloadsToStartOfQueue(listOf(returned, staleDuplicate, returned))
            assertEquals(listOf(3L, 1L, 2L), queue.value.map { it.chapter.id })
            assertSame(paused, queue.value[1])
            assertEquals(Download.State.PAUSED, paused.status)
            assertEquals(Download.State.ERROR, failed.status)
            verify(exactly = 0) { downloader.resumeAllPaused() }
            verify(exactly = 1) { DownloadJob.start(context) }
        } finally {
            unmockkObject(DownloadJob.Companion)
        }
    }

    @Test
    fun `returning only a paused item does not wake the worker`() {
        val paused = download(1, Download.State.PAUSED)
        val queue = MutableStateFlow(emptyList<Download>())
        val context = mockk<Context>()
        val downloader = mockk<Downloader>(relaxed = true) {
            every { isRunning } returns false
            every { queueState } returns queue
            every { updateQueue(any()) } answers { queue.value = firstArg() }
        }
        val manager = DownloadManager(context, mockk(), mockk(), mockk(), mockk(), mockk(), downloader, mockk())
        mockkObject(DownloadJob.Companion)
        try {
            manager.addDownloadsToStartOfQueue(listOf(paused))
            assertSame(paused, queue.value.single())
            assertEquals(Download.State.PAUSED, paused.status)
            verify(exactly = 0) { downloader.resumeAllPaused() }
            verify(exactly = 0) { DownloadJob.start(any()) }
        } finally {
            unmockkObject(DownloadJob.Companion)
        }
    }

    @Test
    fun `deferred automatic batch preserves paused and failed items with idle or running worker`() {
        for (runningWorker in listOf(false, true)) {
            val paused = download(1, Download.State.PAUSED)
            val failed = download(2, Download.State.ERROR)
            val queued = download(3, Download.State.QUEUE)
            val context = mockk<Context>()
            val downloader = mockk<Downloader>(relaxed = true) {
                every { isRunning } returns false
                every { queueState } returns MutableStateFlow(listOf(paused, failed, queued))
                every { resumeAllPaused() } answers {
                    paused.status = Download.State.QUEUE
                    failed.status = Download.State.QUEUE
                }
            }
            val manager = DownloadManager(
                context,
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                mockk(),
                downloader,
                mockk(),
            )
            mockkObject(DownloadJob.Companion)
            try {
                every { DownloadJob.isRunning(context) } returns runningWorker
                every { DownloadJob.start(context) } returns Unit
                manager.startQueuedDownloadsOnly()
                assertEquals(Download.State.PAUSED, paused.status)
                assertEquals(Download.State.ERROR, failed.status)
                assertEquals(Download.State.QUEUE, queued.status)
                verify(exactly = 0) { downloader.resumeAllPaused() }
                if (runningWorker) {
                    verify(exactly = 1) { downloader.start() }
                    verify(exactly = 0) { DownloadJob.start(context) }
                } else {
                    verify(exactly = 1) { DownloadJob.start(context) }
                }
                // Explicit Resume All retains its existing meaning.
                manager.startDownloads()
                assertEquals(Download.State.QUEUE, paused.status)
                assertEquals(Download.State.QUEUE, failed.status)
            } finally {
                unmockkObject(DownloadJob.Companion)
            }
        }
    }

    private fun download(id: Long, state: Download.State) = Download(
        mockk(),
        Manga.create(),
        Chapter.create().copy(id = id),
    ).apply { status = state }
}
