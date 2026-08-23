package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
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
import kotlin.math.ceil
import kotlin.math.floor

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
    private var pairContainer: DoublePageLayout? = null
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
        recycle()
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

    internal fun isTransitionTargetReady(): Boolean = spreadDisplayed

    override fun visibleImageBounds(): RectF? {
        if (pairViews.isEmpty()) return super.visibleImageBounds()
        val container = pairContainer ?: return null
        var combined: RectF? = null
        pairViews.forEach { child ->
            val childBounds = child.visibleImageBounds() ?: return@forEach
            childBounds.offset(container.x + child.x, container.y + child.y)
            combined = combined?.apply { union(childBounds) } ?: childBounds
        }
        val bounds = combined ?: return null
        if (!bounds.intersect(0f, 0f, width.toFloat(), height.toFloat())) return null
        return bounds
    }

    internal fun visibleImageBoundsInWindow(): Rect? {
        val bounds = visibleImageBounds() ?: return null
        val location = IntArray(2)
        getLocationInWindow(location)
        return Rect(
            floor(bounds.left + location[0]).toInt(),
            floor(bounds.top + location[1]).toInt(),
            ceil(bounds.right + location[0]).toInt(),
            ceil(bounds.bottom + location[1]).toInt(),
        )
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
        if (slot.pages.any { it.status != Page.State.Ready || (it.stream == null && it.bitmap == null) }) return
        renderJob?.cancel()
        renderJob = scope.launch { renderPages() }
    }

    private suspend fun renderPages() {
        progressIndicator?.setProgress(0)
        failedPage = null
        var contentPage: ReaderPage? = null
        val pageContents = linkedMapOf<ReaderPage, PageContent>()
        try {
            withIOContext {
                slot.pages.forEach { physicalPage ->
                    contentPage = physicalPage
                    pageContents[physicalPage] = when {
                        physicalPage is InsertPage -> {
                            val parentContent = pageContents[physicalPage.parent]
                            if (parentContent is PageContent.Rendered) {
                                PageContent.Rendered(
                                    checkNotNull(parentContent.peek().copy(Bitmap.Config.ARGB_8888, true)),
                                )
                            } else {
                                loadContent(physicalPage)
                            }
                        }
                        else -> loadContent(physicalPage)
                    }
                }
            }

            if (viewer.config.doublePages) {
                val classifications = withIOContext {
                    pageContents.mapValues { (_, content) ->
                        try {
                            classify(content)
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
                renderSingle(pageContents.getValue(page))
                return
            }

            renderTiledPair(pageContents.getValue(page), pageContents.getValue(second))
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            logcat(LogPriority.ERROR, error)
            setError(error, contentPage ?: page)
        } finally {
            pageContents.values.forEach(PageContent::recycle)
        }
    }

    private fun loadContent(physicalPage: ReaderPage): PageContent {
        return physicalPage.bitmap?.invoke()?.let(PageContent::Rendered)
            ?: physicalPage.stream?.invoke()?.use { PageContent.Encoded(Buffer().apply { readFrom(it) }) }
            ?: error("Reader page has no content")
    }

    private fun classify(content: PageContent): ReaderPage.SpreadInfo {
        return when (content) {
            is PageContent.Rendered -> {
                val bitmap = content.peek()
                ReaderPage.SpreadInfo(
                    kind = if (bitmap.width > bitmap.height) {
                        ReaderPage.SpreadKind.WIDE
                    } else {
                        ReaderPage.SpreadKind.PAIRABLE
                    },
                    width = bitmap.width,
                    height = bitmap.height,
                )
            }
            is PageContent.Encoded -> {
                val isAnimated = ImageUtil.isAnimated(content.source)
                val decoder = ImageDecoder.newInstance(content.source.peek().inputStream())
                    ?: return if (isAnimated) {
                        ReaderPage.SpreadInfo(ReaderPage.SpreadKind.ANIMATED)
                    } else {
                        ReaderPage.SpreadInfo.UNKNOWN
                    }
                try {
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
        }
    }

    private suspend fun renderSingle(content: PageContent) {
        val processed = withIOContext { process(page, content) }
        try {
            val isAnimated = processed is PageContent.Encoded && ImageUtil.isAnimatedAndSupported(processed.source)
            val background = if (!isAnimated) automaticBackground(processed) else null
            withUIContext {
                clearPairViews()
                displayContent(
                    view = this@PagerPageHolder,
                    content = processed,
                    isAnimated = isAnimated,
                    config = imageConfig(landscapeZoom = viewer.config.landscapeZoom),
                )
                if (!isAnimated) pageBackground = background
                removeErrorLayout()
            }
        } finally {
            processed.recycle()
        }
    }

    private suspend fun renderTiledPair(firstContent: PageContent, secondContent: PageContent) {
        val firstBackground = automaticBackground(firstContent)
        val secondBackground = automaticBackground(secondContent)
        withUIContext {
            recycle()
            clearPairViews()
            displayedPairViews = 0
            spreadDisplayed = false
            physicalSplitFraction = 0.5f

            val physicalPages = if (firstPageOnLeft()) {
                listOf(
                    Triple(page, firstContent, firstBackground),
                    Triple(checkNotNull(extraPage), secondContent, secondBackground),
                )
            } else {
                listOf(
                    Triple(checkNotNull(extraPage), secondContent, secondBackground),
                    Triple(page, firstContent, firstBackground),
                )
            }
            val pageSizes = physicalPages.map { (physicalPage) ->
                DoublePageCompositionPolicy.Image(
                    width = physicalPage.spreadInfo.width ?: 0,
                    height = physicalPage.spreadInfo.height ?: 0,
                    compressedBytes = 0,
                )
            }
            val container = DoublePageLayout(
                context = context,
                firstPage = pageSizes[0],
                secondPage = pageSizes[1],
                onSplitFractionChanged = { physicalSplitFraction = it },
            )
            val children = mutableListOf<ReaderPageImageView>()
            try {
                physicalPages.forEach { (physicalPage, content, background) ->
                    children += ReaderPageImageView(
                        context = context,
                        basePreferences = viewer.activity.basePreferences,
                    ).apply {
                        onImageLoaded = {
                            displayedPairViews++
                            if (displayedPairViews == physicalPages.size && !spreadDisplayed) {
                                spreadDisplayed = true
                                progressIndicator?.hide()
                                viewer.onTransitionTargetReady(slot)
                                viewer.activity.onPagesDisplayed(slot.pages)
                            }
                        }
                        onImageLoadError = { error -> setError(error, physicalPage) }
                        onScaleChanged = { viewer.activity.hideMenu() }
                        pageBackground = background
                        displayContent(
                            view = this,
                            content = content,
                            isAnimated = false,
                            config = imageConfig(landscapeZoom = false),
                            cloneEncodedSource = true,
                        )
                    }
                }
            } catch (error: Throwable) {
                children.forEach(ReaderPageImageView::recycle)
                throw error
            }
            children.forEach { child -> container.addView(child) }
            pairViews = children
            pairContainer = container
            addView(container, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            removeErrorLayout()
        }
    }

    private suspend fun automaticBackground(content: PageContent) = if (!viewer.config.automaticBackground) {
        null
    } else {
        withIOContext {
            when (content) {
                is PageContent.Encoded -> ImageUtil.chooseBackground(context, content.source.peek().inputStream())
                is PageContent.Rendered -> ImageUtil.chooseBackground(context, content.peek())
            }
        }
    }

    private fun firstPageOnLeft(): Boolean = DoublePagePlacement.firstPageOnLeft(
        isRightToLeft = viewer is R2LPagerViewer,
        inverted = viewer.config.invertDoublePages,
    )

    private fun imageConfig(landscapeZoom: Boolean) = Config(
        zoomDuration = viewer.config.doubleTapAnimDuration,
        minimumScaleType = viewer.config.imageScaleType,
        cropBorders = viewer.config.imageCropBorders,
        zoomStartPosition = viewer.config.imageZoomType,
        landscapeZoom = landscapeZoom,
    )

    private fun displayContent(
        view: ReaderPageImageView,
        content: PageContent,
        isAnimated: Boolean,
        config: Config,
        cloneEncodedSource: Boolean = false,
    ) {
        when (content) {
            is PageContent.Encoded -> {
                val source = if (cloneEncodedSource) content.source.peek() else content.source
                view.setImage(source, isAnimated, config)
            }
            is PageContent.Rendered -> {
                view.setImage(BitmapDrawable(view.resources, content.take()), config)
            }
        }
    }

    private fun process(physicalPage: ReaderPage, content: PageContent): PageContent {
        return when (content) {
            is PageContent.Encoded -> PageContent.Encoded(process(physicalPage, content.source))
            is PageContent.Rendered -> PageContent.Rendered(process(physicalPage, content.take()))
        }
    }

    private fun process(physicalPage: ReaderPage, source: Bitmap): Bitmap {
        var ownedBitmap = source
        try {
            val processed = when {
                viewer.config.automaticallySplitsWidePages && physicalPage is InsertPage -> {
                    ImageUtil.splitInHalf(ownedBitmap, splitSide(physicalPage))
                }
                viewer.config.automaticallySplitsWidePages && ownedBitmap.width > ownedBitmap.height -> {
                    onPageSplit(physicalPage)
                    ImageUtil.splitInHalf(ownedBitmap, splitSide(physicalPage))
                }
                viewer.config.dualPageRotateToFit && ownedBitmap.width > ownedBitmap.height -> {
                    val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
                    ImageUtil.rotateImage(ownedBitmap, rotation)
                }
                viewer.config.dualPageSplit && physicalPage is InsertPage -> {
                    ImageUtil.splitInHalf(ownedBitmap, splitSide(physicalPage))
                }
                viewer.config.dualPageSplit && ownedBitmap.width > ownedBitmap.height -> {
                    onPageSplit(physicalPage)
                    ImageUtil.splitInHalf(ownedBitmap, splitSide(physicalPage))
                }
                else -> ownedBitmap
            }
            if (processed !== ownedBitmap) {
                ownedBitmap.recycle()
                ownedBitmap = processed
            }
            return ownedBitmap
        } catch (error: Throwable) {
            ownedBitmap.recycle()
            throw error
        }
    }

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
        return ImageUtil.splitInHalf(imageSource, splitSide(physicalPage))
    }

    private fun splitSide(physicalPage: ReaderPage): ImageUtil.Side {
        var side = when {
            viewer is L2RPagerViewer && physicalPage is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && physicalPage is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer -> ImageUtil.Side.LEFT
            else -> ImageUtil.Side.RIGHT
        }
        if (viewer.config.dualPageInvert) {
            side = if (side == ImageUtil.Side.RIGHT) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
        }
        return side
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
        viewer.onTransitionTargetReady(slot)
        viewer.activity.onPagesDisplayed(slot.pages)
    }

    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        viewer.onTransitionTargetFailed(slot)
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

    private sealed interface PageContent {
        fun recycle()

        class Encoded(val source: BufferedSource) : PageContent {
            override fun recycle() = Unit
        }

        class Rendered(bitmap: Bitmap) : PageContent {
            private var bitmap: Bitmap? = bitmap

            fun peek(): Bitmap = checkNotNull(bitmap)

            fun take(): Bitmap = checkNotNull(bitmap).also { bitmap = null }

            override fun recycle() {
                bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                bitmap = null
            }
        }
    }
}
