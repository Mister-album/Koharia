package koharia.smanga

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.setting.PageLayout
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.SharedAppPreferences
import koharia.source.smanga.SmangaConnectionProvider
import koharia.source.smanga.SmangaPreferences
import koharia.source.smanga.SmangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SmangaPdfTransitionTest {
    @Test
    fun pagerDownloadsAdjacentPdfOnlyWhenEnteringTransition() = verify(ReadingMode.LEFT_TO_RIGHT)

    @Test
    fun webtoonDownloadsAdjacentPdfOnlyWhenEnteringTransition() = verify(ReadingMode.WEBTOON)

    @Test
    fun webtoonDragAtRestoredBottomLoadsAdjacentPdfWithoutConsumedScroll() = verify(
        ReadingMode.WEBTOON,
        atBottom = true,
    )

    private fun verify(mode: ReadingMode, atBottom: Boolean = false): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val manager = Injekt.get<ConnectionProfileManager>()
        val connections = Injekt.get<ConnectionPreferences>()
        val mangas = Injekt.get<MangaRepository>()
        val base = Injekt.get<BasePreferences>()
        val reader = Injekt.get<SharedAppPreferences>().readerPreferences()
        val restores = mutableListOf<() -> Unit>()
        fun <T> override(preference: Preference<T>, value: T) {
            val wasSet = preference.isSet()
            val previous = preference.get()
            restores += { if (wasSet) preference.set(previous) else preference.delete() }
            preference.set(value)
        }
        override(connections.activeConnectionId, connections.activeConnectionId.get())
        val server = PdfServer(createPdf())
        var connectionId: Long? = null
        try {
            override(base.incognitoMode, true)
            override(base.shownOnboardingFlow, true)
            override(base.downloadedOnly, false)
            override(reader.showNavigationOverlayNewUser, false)
            override(reader.showNavigationOverlayOnStart, false)
            override(reader.pageLayout, PageLayout.SINGLE_PAGE.value)
            override(reader.defaultReadingMode, mode.flagValue)
            override(reader.dualPageSplitPaged, false)
            override(reader.dualPageSplitWebtoon, false)
            override(reader.webtoonSmoothScroll, false)
            override(reader.alwaysShowChapterTransition, true)
            override(reader.skipRead, false)
            override(reader.skipFiltered, false)
            val profile = manager.add(SmangaConnectionProvider.ID, "PDF transition fixture")
            connectionId = profile.id
            SmangaPreferences(profile.id).save(server.address, "fixture", "fixture-password", 1)
            val source = withTimeout(10_000) {
                var registered: SmangaSource?
                do {
                    registered = Injekt.get<SourceManager>().get(profile.id) as? SmangaSource
                    if (registered == null) delay(25)
                } while (registered == null)
                registered
            }.also { it.reload() }
            connections.activeConnectionId.set(profile.id)
            val manga = mangas.insertNetworkManga(
                listOf(
                    source.toManga(SmangaManga(1, 1, "PDF transition fixture")).copy(
                        initialized = true,
                        viewerFlags = (mode.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue).toLong(),
                        chapterFlags = Manga.CHAPTER_SORTING_NUMBER,
                    ),
                ),
            ).single()
            val chapters = Injekt.get<ChapterRepository>().addAll(
                (1L..2L).map { id ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = source.session().chapterUrl(SmangaChapter(id, 1, 1, "PDF $id", format = "pdf")),
                        name = "PDF $id",
                        chapterNumber = id.toDouble(),
                        sourceOrder = 2 - id,
                    )
                },
            ).sortedBy { it.chapterNumber }
            source.preparePdfFile(chapters[0].url)
            assertNull(source.findCompletePdfFile(chapters[1].url))
            ActivityScenario.launch<ReaderActivity>(
                ReaderActivity.newIntent(context, manga.id, chapters[0].id, source.id, pageIndex = 0),
            ).use { scenario ->
                awaitPage(scenario, chapters[0].id)
                delay(1000)
                assertEquals("Initial layout must not download the adjacent PDF", 0, server.requests(2))
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    assertEquals(chapters[1].id, state.viewerChapters?.nextChapter?.chapter?.id)
                    state.viewer!!.moveToPage(state.currentChapter!!.pages!!.last())
                }
                delay(1000)
                assertEquals("End-of-chapter background preloading must use cache only", 0, server.requests(2))
                if (atBottom) {
                    scenario.onActivity { activity ->
                        (activity.viewModel.state.value.viewer as WebtoonViewer).recycler.scrollBy(0, 100_000)
                    }
                    delay(1000)
                    scenario.onActivity { activity ->
                        val recycler = (activity.viewModel.state.value.viewer as WebtoonViewer).recycler
                        assertFalse(
                            "Fixture must begin at the non-scrollable boundary",
                            recycler.canScrollVertically(1),
                        )
                    }
                    assertEquals("Restoring the bottom position must not download the next PDF", 0, server.requests(2))
                }
                withTimeout(20_000) {
                    var entered = false
                    while (!entered) {
                        scenario.onActivity { activity ->
                            entered = activity.viewModel.state.value.currentChapter?.chapter?.id == chapters[1].id
                            if (!entered) {
                                when (val viewer = activity.viewModel.state.value.viewer) {
                                    is PagerViewer -> viewer.moveToNext()
                                    is WebtoonViewer -> if (atBottom) {
                                        dragForward(viewer)
                                    } else {
                                        viewer.handleKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN))
                                    }
                                    else -> error("Expected a comic viewer")
                                }
                            }
                        }
                        if (!entered) delay(250)
                    }
                }
                awaitPage(scenario, chapters[1].id)
                assertEquals(1, server.requests(1))
                assertEquals("Repeated transition requests must reuse one complete file", 1, server.requests(2))
                assertNotNull(source.findCompletePdfFile(chapters[1].url))
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    connectionId?.let { id ->
                        try {
                            manager.remove(id).getOrThrow()
                        } finally {
                            mangas.deleteMangaBySourceId(id)
                        }
                    }
                } finally {
                    restores.asReversed().forEach { it() }
                    server.close()
                }
            }
        }
    }

    private fun dragForward(viewer: WebtoonViewer) {
        val recycler = viewer.recycler
        val downTime = SystemClock.uptimeMillis()
        val x = recycler.width / 2f
        fun dispatch(action: Int, step: Int) {
            val event = MotionEvent.obtain(
                downTime,
                downTime + step * 16,
                action,
                x,
                recycler.height * (0.8f - step * 0.08f),
                0,
            )
            try {
                recycler.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
        dispatch(MotionEvent.ACTION_DOWN, 0)
        (1..5).forEach { dispatch(MotionEvent.ACTION_MOVE, it) }
        dispatch(MotionEvent.ACTION_UP, 6)
    }

    private suspend fun awaitPage(scenario: ActivityScenario<ReaderActivity>, chapterId: Long) {
        withTimeout(20_000) {
            var ready = false
            while (!ready) {
                scenario.onActivity { activity ->
                    val state = activity.viewModel.state.value
                    ready = state.currentChapter?.chapter?.id == chapterId &&
                        state.currentChapter?.pages?.getOrNull(state.currentPage - 1)?.status == Page.State.Ready
                }
                if (!ready) delay(50)
            }
        }
    }

    private fun createPdf(): ByteArray {
        val document = PdfDocument()
        return try {
            listOf(Color.RED, Color.BLUE).forEachIndexed { index, color ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(160, 300, index + 1).create())
                page.canvas.drawColor(color)
                document.finishPage(page)
            }
            ByteArrayOutputStream().use { output ->
                document.writeTo(output)
                output.toByteArray()
            }
        } finally {
            document.close()
        }
    }

    private class PdfServer(private val pdf: ByteArray) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val counts = ConcurrentHashMap<Long, AtomicInteger>()
        val address get() = "http://127.0.0.1:${listener.localPort}/"
        fun requests(chapterId: Long) = counts[chapterId]?.get() ?: 0

        init {
            executor.execute {
                while (!listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: IOException) {
                        break
                    }
                    sockets += socket
                    executor.execute {
                        try {
                            socket.use { connection ->
                                connection.soTimeout = 5000
                                val reader = connection.getInputStream().bufferedReader(Charsets.US_ASCII)
                                val target = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                                while (!reader.readLine().isNullOrEmpty()) Unit
                                val chapterId = Regex("/api/opds/chapter/([12])/download(?:\\?.*)?")
                                    .matchEntire(target)?.groupValues?.get(1)?.toLong()
                                if (chapterId != null) counts.getOrPut(chapterId) { AtomicInteger() }.incrementAndGet()
                                val bytes = if (chapterId != null) pdf else ByteArray(0)
                                val status = if (chapterId != null) "200 OK" else "404 Not Found"
                                val output = connection.getOutputStream()
                                output.write(
                                    (
                                        "HTTP/1.1 $status\r\nContent-Type: application/pdf\r\n" +
                                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                        )
                                        .toByteArray(Charsets.US_ASCII),
                                )
                                output.write(bytes)
                                output.flush()
                            }
                        } catch (_: IOException) {
                            // Closing the reader or fixture can cancel an in-flight request.
                        } finally {
                            sockets -= socket
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
