package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.SystemClock
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.system.logcat
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Loader used to render pages from a PDF file without an encoded-image round trip. */
internal class PdfPageLoader(
    context: Context,
    file: UniFile,
) : PageLoader() {

    private val renderLock = Any()
    private val stateLock = Any()
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pageLoadGate = PageLoadGate(preloadSize = 2)
    private val allowedPageIndexes = MutableStateFlow<Set<Int>>(emptySet())
    private val renderJobs = mutableMapOf<Int, Deferred<Unit>>()
    private val renderGenerations = mutableMapOf<Int, Long>()
    private val renderedPages = mutableMapOf<Int, Bitmap>()
    private var desiredPageIndexes: Set<Int> = emptySet()
    private var pages: List<ReaderPage> = emptyList()

    private val viewportWidth = context.resources.displayMetrics.widthPixels
    private val viewportHeight = context.resources.displayMetrics.heightPixels
    private val fileDescriptor: android.os.ParcelFileDescriptor
    private val renderer: PdfRenderer

    init {
        val fd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("Failed to open pdf file descriptor: ${file.uri}")

        try {
            fileDescriptor = fd
            renderer = PdfRenderer(fd)
        } catch (e: Throwable) {
            fd.close()
            throw e
        }
    }

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val loadedPages = List(renderer.pageCount) { index ->
            ReaderPage(index).apply {
                bitmap = { obtainBitmap(index) }
                status = Page.State.Queue
            }
        }
        pages = loadedPages
        return loadedPages
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
        if (page.status == Page.State.Ready) return

        allowedPageIndexes.first { isRecycled || page.index in it }
        check(!isRecycled)
        scheduleRender(page.index).await()
    }

    override fun setActivePage(page: ReaderPage) {
        setActivePages(listOf(page))
    }

    override fun setActivePages(pages: List<ReaderPage>) {
        if (isRecycled || this.pages.isEmpty()) return
        val activeIndexes = pages.mapTo(linkedSetOf()) { it.index }
        val logicalIndex = activeIndexes.maxOrNull() ?: return
        val activation = pageLoadGate.activate(activeIndexes, logicalIndex, this.pages.size)
        updateDesiredPages(activeIndexes + activation.prefetchIndexes)
        activeIndexes.forEach(::scheduleRender)
        activation.prefetchIndexes.forEach(::scheduleRender)
    }

    override fun onPageDisplayed(page: ReaderPage) {
        onPagesDisplayed(listOf(page))
    }

    override fun onPagesDisplayed(pages: List<ReaderPage>) {
        if (isRecycled || this.pages.isEmpty()) return
        val activeIndexes = pages.mapTo(linkedSetOf()) { it.index }
        val prefetchIndexes = pageLoadGate.onPagesDisplayed(activeIndexes, this.pages.size)
        updateDesiredPages(activeIndexes + prefetchIndexes)
        prefetchIndexes.forEach(::scheduleRender)
    }

    private fun updateDesiredPages(indexes: Set<Int>) {
        val staleBitmaps = synchronized(stateLock) {
            desiredPageIndexes = indexes
            allowedPageIndexes.value = indexes
            renderJobs.filterKeys { it !in indexes }.values.forEach { it.cancel() }
            renderedPages.keys
                .filter { it !in indexes }
                .mapNotNull(renderedPages::remove)
        }
        staleBitmaps.forEach(Bitmap::recycle)
    }

    private fun scheduleRender(index: Int): Deferred<Unit> {
        return synchronized(stateLock) {
            if (pages.getOrNull(index)?.status == Page.State.Ready) {
                return@synchronized CompletableDeferred(Unit)
            }
            renderJobs[index]?.takeIf { it.isActive } ?: run {
                val generation = renderGenerations.getOrDefault(index, 0L) + 1
                renderGenerations[index] = generation
                renderScope.async {
                    renderAndCache(index, generation)
                }
            }.also { job ->
                renderJobs[index] = job
                job.invokeOnCompletion {
                    synchronized(stateLock) {
                        if (renderJobs[index] === job) {
                            renderJobs.remove(index)
                        }
                    }
                }
            }
        }
    }

    private fun renderAndCache(index: Int, generation: Long) {
        val page = pages.getOrNull(index) ?: return
        page.status = Page.State.LoadPage
        try {
            val bitmap = renderPage(index)
            val keepBitmap = synchronized(stateLock) {
                if (
                    !isRecycled &&
                    index in desiredPageIndexes &&
                    renderGenerations[index] == generation
                ) {
                    renderedPages.put(index, bitmap)?.recycle()
                    true
                } else {
                    false
                }
            }
            if (keepBitmap) {
                page.status = Page.State.Ready
            } else {
                bitmap.recycle()
            }
        } catch (error: Throwable) {
            if (
                error !is CancellationException &&
                !isRecycled &&
                index in desiredPageIndexes &&
                renderGenerations[index] == generation
            ) {
                page.status = Page.State.Error(error)
            }
            throw error
        }
    }

    private fun obtainBitmap(index: Int): Bitmap {
        synchronized(stateLock) {
            renderedPages.remove(index)?.let { return it }
        }
        return renderPage(index)
    }

    private fun renderPage(index: Int): Bitmap = synchronized(renderLock) {
        check(!isRecycled)
        renderer.openPage(index).use { page ->
            pages.getOrNull(index)?.spreadInfo = ReaderPage.SpreadInfo(
                kind = if (page.width > page.height) {
                    ReaderPage.SpreadKind.WIDE
                } else {
                    ReaderPage.SpreadKind.PAIRABLE
                },
                width = page.width,
                height = page.height,
            )
            val size = calculatePdfRenderSize(
                pageWidth = page.width,
                pageHeight = page.height,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
            val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            val startedAt = SystemClock.elapsedRealtime()
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
            logcat {
                "PdfPerformance: rendered page=${index + 1} size=${size.width}x${size.height} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            }
            bitmap
        }
    }

    override fun recycle() {
        super.recycle()
        renderScope.cancel()
        val cachedBitmaps = synchronized(stateLock) {
            allowedPageIndexes.value = emptySet()
            desiredPageIndexes = emptySet()
            renderJobs.values.forEach { it.cancel() }
            renderJobs.clear()
            renderGenerations.clear()
            renderedPages.values.toList().also { renderedPages.clear() }
        }
        cachedBitmaps.forEach(Bitmap::recycle)
        synchronized(renderLock) {
            renderer.close()
            fileDescriptor.close()
        }
    }
}

internal data class PdfRenderSize(
    val width: Int,
    val height: Int,
)

internal fun calculatePdfRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    viewportWidth: Int,
    viewportHeight: Int,
): PdfRenderSize {
    val safePageWidth = pageWidth.coerceAtLeast(1)
    val safePageHeight = pageHeight.coerceAtLeast(1)
    val viewportPixels = viewportWidth.coerceAtLeast(1).toLong() * viewportHeight.coerceAtLeast(1)
    val targetPixels = (viewportPixels * RENDER_PIXEL_MULTIPLIER)
        .roundToInt()
        .coerceAtMost(MAX_RENDER_PIXELS)
        .coerceAtLeast(1)
    val pagePixels = safePageWidth.toLong() * safePageHeight
    val scale = sqrt(targetPixels.toDouble() / pagePixels.toDouble())
    val width = (safePageWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (safePageHeight * scale).roundToInt().coerceAtLeast(1)
    return PdfRenderSize(width, height)
}

private const val RENDER_PIXEL_MULTIPLIER = 1.5
private const val MAX_RENDER_PIXELS = 6_000_000
