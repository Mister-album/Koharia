package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR

/** ViewPager item that renders one physical page or a two-page spread. */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val slot: PagerSlot.Pages,
) : ReaderPageImageView(
    context = readerThemedContext,
    basePreferences = viewer.activity.basePreferences,
),
    ViewPagerAdapter.PositionableView {

    val page: ReaderPage = slot.first
    private val extraPage: ReaderPage? = slot.second

    override val item: PagerSlot.Pages
        get() = slot

    private var progressIndicator: ReaderProgressIndicator? = null
    private var errorLayout: ReaderErrorBinding? = null
    private var failedPage: ReaderPage? = null
    private val scope = MainScope()
    private val loadJobs = mutableListOf<Job>()
    private var renderJob: Job? = null
    private var pairContainer: LinearLayout? = null
    private var pairViews: List<ReaderPageImageView> = emptyList()
    private var displayedPairViews = 0
    private var spreadDisplayed = false
    private var physicalSplitFraction = 0.5f

    init {
        slot.pages.forEach { physicalPage ->
            loadJobs += scope.launch { loadPageAndProcessStatus(physicalPage) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJobs.forEach(Job::cancel)
        loadJobs.clear()
        renderJob?.cancel()
        renderJob = null
        clearPairViews()
        scope.cancel()
    }

    fun pageAt(x: Float, y: Float): ReaderPage {
        val second = extraPage ?: return page
        val firstOnLeft = firstPageOnLeft()
        val isLeftPage = if (pairViews.isEmpty()) {
            sourceXFractionAt(x, y)?.let { it < physicalSplitFraction }
                ?: (x < width * physicalSplitFraction)
        } else {
            x < width * physicalSplitFraction
        }
        return if (isLeftPage == firstOnLeft) page else second
    }

    fun canNavigatePanLeft(): Boolean = if (pairViews.isEmpty()) {
        canPanLeft()
    } else {
        pairViews.any(ReaderPageImageView::canPanLeft)
    }

    fun canNavigatePanRight(): Boolean = if (pairViews.isEmpty()) {
        canPanRight()
    } else {
        pairViews.any(ReaderPageImageView::canPanRight)
    }

    fun navigatePanLeft() {
        if (pairViews.isEmpty()) panLeft() else pairViews.filter { it.canPanLeft() }.forEach { it.panLeft() }
    }

    fun navigatePanRight() {
        if (pairViews.isEmpty()) panRight() else pairViews.filter { it.canPanRight() }.forEach { it.panRight() }
    }

    override fun onPageSelected(forward: Boolean) {
        if (pairViews.isEmpty()) {
            super.onPageSelected(forward)
        } else {
            pairViews.forEach { it.onPageSelected(forward) }
        }
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    private suspend fun loadPageAndProcessStatus(physicalPage: ReaderPage) {
        val loader = physicalPage.chapter.pageLoader ?: return
        supervisorScope {
            launchIO { loader.loadPage(physicalPage) }
            physicalPage.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        physicalPage.progressFlow.collectLatest { progress ->
                            progressIndicator?.setProgress(progress)
                        }
                    }
                    Page.State.Ready -> scheduleRender()
                    is Page.State.Error -> setError(state.error, physicalPage)
                }
            }
        }
    }

    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    private fun scheduleRender() {
        if (slot.pages.any { it.stream == null }) return
        renderJob?.cancel()
        renderJob = scope.launch { renderPages() }
    }

    private suspend fun renderPages() {
        progressIndicator?.setProgress(0)
        failedPage = null
        var streamPage: ReaderPage? = null
        val pageBytes = try {
            withIOContext {
                slot.pages.associateWith { physicalPage ->
                    streamPage = physicalPage
                    val stream = checkNotNull(physicalPage.stream)
                    stream().use { Buffer().apply { readFrom(it) } }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logcat(LogPriority.ERROR, error)
            setError(error, streamPage ?: page)
            return
        }

        if (viewer.config.doublePages) {
            val classifications = withIOContext {
                pageBytes.mapValues { (_, source) ->
                    try {
                        classify(source)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ReaderPage.SpreadInfo.UNKNOWN
                    }
                }
            }
            val layoutChanged = withUIContext { viewer.onPagesClassified(classifications) }
            if (layoutChanged) return
            withUIContext { viewer.onPagesPrepared(slot) }
        }

        val second = extraPage
        if (second == null) {
            renderSingle(pageBytes.getValue(page))
            return
        }

        val firstBytes = pageBytes.getValue(page)
        val secondBytes = pageBytes.getValue(second)
        val composite = try {
            withIOContext { createComposite(firstBytes, secondBytes) }
        } catch (error: OutOfMemoryError) {
            logcat(LogPriority.WARN, error) { "Double-page composition ran out of memory; using tiled views" }
            null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logcat(LogPriority.WARN, error) { "Double-page composition failed; using tiled views" }
            null
        }

        if (composite != null) {
            renderComposite(composite)
        } else {
            renderTiledPair(firstBytes, secondBytes)
        }
    }

    private fun classify(source: BufferedSource): ReaderPage.SpreadInfo {
        val isAnimated = ImageUtil.isAnimated(source)
        val decoder = ImageDecoder.newInstance(source.peek().inputStream())
            ?: return if (isAnimated) {
                ReaderPage.SpreadInfo(ReaderPage.SpreadKind.ANIMATED)
            } else {
                ReaderPage.SpreadInfo.UNKNOWN
            }
        return try {
            val width = decoder.width
            val height = decoder.height
            val kind = when {
                isAnimated -> ReaderPage.SpreadKind.ANIMATED
                width > height -> ReaderPage.SpreadKind.WIDE
                else -> ReaderPage.SpreadKind.PAIRABLE
            }
            ReaderPage.SpreadInfo(kind, width, height)
        } finally {
            decoder.recycle()
        }
    }

    private suspend fun renderSingle(sourceBuffer: Buffer) {
        val source = withIOContext { process(page, sourceBuffer) }
        val isAnimated = ImageUtil.isAnimatedAndSupported(source)
        val background = if (!isAnimated && viewer.config.automaticBackground) {
            withIOContext { ImageUtil.chooseBackground(context, source.peek().inputStream()) }
        } else {
            null
        }
        withUIContext {
            clearPairViews()
            setImage(source, isAnimated, imageConfig(landscapeZoom = viewer.config.landscapeZoom))
            if (!isAnimated) pageBackground = background
            removeErrorLayout()
        }
    }

    private suspend fun renderComposite(source: BufferedSource) = withUIContext {
        clearPairViews()
        setImage(source, false, imageConfig(landscapeZoom = false))
        removeErrorLayout()
    }

    private suspend fun renderTiledPair(firstSource: Buffer, secondSource: Buffer) = withUIContext {
        recycle()
        clearPairViews()
        displayedPairViews = 0
        spreadDisplayed = false
        physicalSplitFraction = 0.5f

        val physicalPages = if (firstPageOnLeft()) {
            listOf(page to firstSource, checkNotNull(extraPage) to secondSource)
        } else {
            listOf(checkNotNull(extraPage) to secondSource, page to firstSource)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val children = physicalPages.map { (physicalPage, source) ->
            ReaderPageImageView(
                context = context,
                basePreferences = viewer.activity.basePreferences,
            ).apply {
                onImageLoaded = {
                    displayedPairViews++
                    if (displayedPairViews == physicalPages.size && !spreadDisplayed) {
                        spreadDisplayed = true
                        progressIndicator?.hide()
                        viewer.activity.onPagesDisplayed(slot.pages)
                    }
                }
                onImageLoadError = { error -> setError(error, physicalPage) }
                onScaleChanged = { viewer.activity.hideMenu() }
                setImage(source.clone(), false, imageConfig(landscapeZoom = false))
            }
        }
        children.forEach { child ->
            container.addView(child, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        pairViews = children
        pairContainer = container
        addView(container, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        removeErrorLayout()
    }

    private fun createComposite(firstSource: Buffer, secondSource: Buffer): BufferedSource? {
        val firstDecoder = ImageDecoder.newInstance(firstSource.peek().inputStream()) ?: return null
        try {
            val secondDecoder = ImageDecoder.newInstance(secondSource.peek().inputStream()) ?: return null
            try {
                val firstWidth = firstDecoder.width
                val firstHeight = firstDecoder.height
                val secondWidth = secondDecoder.width
                val secondHeight = secondDecoder.height
                if (minOf(firstWidth, firstHeight, secondWidth, secondHeight) <= 0) return null

                val runtime = Runtime.getRuntime()
                val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                val firstInfo = DoublePageCompositionPolicy.Image(firstWidth, firstHeight, firstSource.size)
                val secondInfo = DoublePageCompositionPolicy.Image(secondWidth, secondHeight, secondSource.size)
                val layout = DoublePageCompositionPolicy.compositionLayout(firstInfo, secondInfo) ?: return null
                if (!DoublePageCompositionPolicy.shouldCompose(firstInfo, secondInfo, available, runtime.maxMemory())) {
                    return null
                }

                var firstBitmap: Bitmap? = null
                var secondBitmap: Bitmap? = null
                var composite: Bitmap? = null
                try {
                    firstBitmap = firstDecoder.decode() ?: return null
                    secondBitmap = secondDecoder.decode() ?: return null
                    composite = Bitmap.createBitmap(layout.outputWidth, layout.height, Bitmap.Config.ARGB_8888)
                    val hasAlpha = firstBitmap.hasAlpha() || secondBitmap.hasAlpha()
                    composite.setHasAlpha(hasAlpha)
                    val canvas = Canvas(composite)
                    canvas.drawColor(readerCanvasColor())
                    val firstOnLeft = firstPageOnLeft()
                    val leftBitmap = if (firstOnLeft) firstBitmap else secondBitmap
                    val rightBitmap = if (firstOnLeft) secondBitmap else firstBitmap
                    val leftWidth = if (firstOnLeft) layout.firstWidth else layout.secondWidth
                    val rightWidth = if (firstOnLeft) layout.secondWidth else layout.firstWidth
                    physicalSplitFraction = leftWidth.toFloat() / layout.outputWidth
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(
                        leftBitmap,
                        null,
                        Rect(0, 0, leftWidth, layout.height),
                        paint,
                    )
                    canvas.drawBitmap(
                        rightBitmap,
                        null,
                        Rect(leftWidth, 0, leftWidth + rightWidth, layout.height),
                        paint,
                    )
                    return Buffer().also { buffer ->
                        val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                        check(composite.compress(format, 100, buffer.outputStream()))
                    }
                } finally {
                    firstBitmap?.recycle()
                    secondBitmap?.recycle()
                    composite?.recycle()
                }
            } finally {
                secondDecoder.recycle()
            }
        } finally {
            firstDecoder.recycle()
        }
    }

    private fun firstPageOnLeft(): Boolean = DoublePagePlacement.firstPageOnLeft(
        isRightToLeft = viewer is R2LPagerViewer,
        inverted = viewer.config.invertDoublePages,
    )

    private fun readerCanvasColor(): Int = when (viewer.config.theme) {
        0 -> Color.WHITE
        2 -> Color.rgb(0x20, 0x21, 0x25)
        3 -> if (
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ) {
            Color.rgb(0x20, 0x21, 0x25)
        } else {
            Color.WHITE
        }
        else -> Color.BLACK
    }

    private fun imageConfig(landscapeZoom: Boolean) = Config(
        zoomDuration = viewer.config.doubleTapAnimDuration,
        minimumScaleType = viewer.config.imageScaleType,
        cropBorders = viewer.config.imageCropBorders,
        zoomStartPosition = viewer.config.imageZoomType,
        landscapeZoom = landscapeZoom,
    )

    private fun process(physicalPage: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.automaticallySplitsWidePages) {
            if (physicalPage is InsertPage) return splitInHalf(physicalPage, imageSource)
            if (ImageUtil.isAnimated(imageSource) || !ImageUtil.isWideImage(imageSource)) return imageSource
            onPageSplit(physicalPage)
            return splitInHalf(physicalPage, imageSource)
        }
        if (viewer.config.dualPageRotateToFit) return rotateDualPage(imageSource)
        if (!viewer.config.dualPageSplit) return imageSource
        if (physicalPage is InsertPage) return splitInHalf(physicalPage, imageSource)
        if (!ImageUtil.isWideImage(imageSource)) return imageSource
        onPageSplit(physicalPage)
        return splitInHalf(physicalPage, imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        if (!ImageUtil.isWideImage(imageSource)) return imageSource
        val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
        return ImageUtil.rotateImage(imageSource, rotation)
    }

    private fun splitInHalf(physicalPage: ReaderPage, imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && physicalPage is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && physicalPage is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer -> ImageUtil.Side.LEFT
            else -> ImageUtil.Side.RIGHT
        }
        if (viewer.config.dualPageInvert) {
            side = if (side == ImageUtil.Side.RIGHT) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
        }
        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(physicalPage: ReaderPage) {
        viewer.onPageSplit(physicalPage, InsertPage(physicalPage))
    }

    private fun setError(error: Throwable?, physicalPage: ReaderPage) {
        failedPage = physicalPage
        progressIndicator?.hide()
        showErrorLayout(error, physicalPage)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        if (spreadDisplayed) return
        spreadDisplayed = true
        progressIndicator?.hide()
        viewer.activity.onPagesDisplayed(slot.pages)
    }

    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error, failedPage ?: page)
    }

    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?, physicalPage: ReaderPage): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                val retryPage = failedPage ?: page
                retryPage.chapter.pageLoader?.retryPage(retryPage)
            }
        }

        val imageUrl = physicalPage.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl?.startsWith("http", true) == true) {
            errorLayout?.actionOpenInWebView?.viewer = viewer
            errorLayout?.actionOpenInWebView?.setOnClickListener {
                val sourceId = viewer.activity.viewModel.manga?.source
                context.startActivity(WebViewActivity.newIntent(context, imageUrl, sourceId))
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)
        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }

    private fun clearPairViews() {
        pairViews.forEach(ReaderPageImageView::recycle)
        pairContainer?.let(::removeView)
        pairContainer = null
        pairViews = emptyList()
        displayedPairViews = 0
    }
}
