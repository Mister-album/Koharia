package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnCause
import eu.kanade.tachiyomi.ui.reader.transition.PageTurnOrigin
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

    private data class PendingCoverTurn(
        val target: Int,
        val slot: PagerSlot.Pages,
    )

    val downloadManager: DownloadManager by injectLazy()

    private val scope = MainScope()

    /**
     * View pager used by this viewer. It's abstract to implement L2R, R2L and vertical pagers on
     * top of this class.
     */
    val pager = createPager()

    /**
     * Configuration used by the pager, like allow taps, scale mode on images, page transitions...
     */
    val config = PagerConfig(this, scope, activity.readerPreferences)

    /**
     * Adapter of the pager.
     */
    private val adapter = PagerViewerAdapter(this)

    private val viewerContainer = FrameLayout(activity).apply {
        addView(
            pager,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private val pageFlipController = ComicPageFlipController(
        container = viewerContainer,
        pager = pager,
        canAnimateTarget = { target -> adapter.slots.getOrNull(target) is PagerSlot.Pages },
        isTargetReady = { target ->
            (adapter.slots.getOrNull(target) as? PagerSlot.Pages)
                ?.progressPage
                ?.let(::getPageHolder)
                ?.isTransitionTargetReady() == true
        },
        contentBoundsInWindow = { target ->
            (adapter.slots.getOrNull(target) as? PagerSlot.Pages)
                ?.progressPage
                ?.let(::getPageHolder)
                ?.visibleImageBoundsInWindow()
        },
    )

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentSlot: PagerSlot? = null

    /** Physical page that must remain visible while the current spread is rebuilt. */
    private var stableSlotAnchor: ReaderPage? = null

    private var awaitingSlotRebuildAnchor: ReaderPage? = null

    private var userDragSelectionPending = false

    private var awaitingPreparedSlot: PendingPreparedSlot? = null

    private var pendingPageMove: PendingPageMove? = null

    /** Keeps the reader controls visible while restoring a page after a document relayout. */
    private var suppressMenuHidingForNextSelection = false

    private var pendingProgressCommitAnchor: ReaderPage? = null

    private var awaitingImageRefresh = false

    private var awaitingPageTransitionUpdate = false

    private var activePageTurnOrigin: PageTurnOrigin? = null

    private var pageTransitionTransformer: PagerPageTransformer? = null

    private var pendingCoverTurn: PendingCoverTurn? = null

    private var pendingCoverTurnTimeout: Job? = null

    private val pendingCoverTurns = ArrayDeque<Int>(MAX_PENDING_COVER_TURNS)

    /**
     * Viewer chapters to set when the pager enters idle mode. Otherwise, if the view was settling
     * or dragging, there'd be a noticeable and annoying jump.
     */
    private var awaitingIdleViewerChapters: ViewerChapters? = null

    /**
     * Whether the view pager is currently in idle mode. It sets the awaiting chapters if setting
     * this field to true.
     */
    private var isIdle = true
        set(value) {
            field = value
            if (value) {
                awaitingSlotRebuildAnchor?.let { anchor ->
                    awaitingSlotRebuildAnchor = null
                    rebuildSlots(anchor)
                }
                if (awaitingImageRefresh) {
                    awaitingImageRefresh = false
                    refreshAdapter()
                }
                if (awaitingPageTransitionUpdate) {
                    awaitingPageTransitionUpdate = false
                    applyPageTransitionEffect()
                }
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
                drainPendingCoverTurn()
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            val pendingMove = pendingPageMove?.takeIf { it.position == position }
            if (!PagerSelectionPolicy.shouldHandle(position, pendingMove?.position, userDragSelectionPending)) {
                // DirectionalViewPager can emit an index derived from the old viewport while rotating.
                stableSlotAnchor
                    ?.let(adapter::positionOf)
                    ?.takeIf { it >= 0 && it != pager.currentItem }
                    ?.let { stablePosition ->
                        pager.removeOnPageChangeListener(this)
                        pager.setCurrentItem(stablePosition, false)
                        pager.addOnPageChangeListener(this)
                    }
                return
            }
            userDragSelectionPending = false
            val suppressMenuHiding = suppressMenuHidingForNextSelection
            suppressMenuHidingForNextSelection = false
            if (!suppressMenuHiding && !activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            if (pendingMove != null) {
                pendingPageMove = null
            }
            onPageChange(
                position = position,
                cause = pendingMove?.cause ?: PageChangeCause.USER_NAVIGATION,
                anchor = pendingMove?.anchor,
            )
        }

        override fun onPageScrollStateChanged(state: Int) {
            if (state == ViewPager.SCROLL_STATE_DRAGGING) {
                pendingPageMove = null
                userDragSelectionPending = true
                cancelPendingCoverTurn(reactivateCurrent = true)
            }
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
            if (isIdle) userDragSelectionPending = false
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.accessibilityPageChangeListener = ::markPendingUserNavigation
        pager.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            config.onViewportChanged(right - left, bottom - top)
        }
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            val turnOrigin = PageTurnOrigin(pos.x, pos.y, PageTurnCause.TAP).normalized()
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> withPageTurnOrigin(turnOrigin) { moveToNext() }
                NavigationRegion.PREV -> withPageTurnOrigin(turnOrigin) { moveToPrevious() }
                NavigationRegion.RIGHT -> withPageTurnOrigin(turnOrigin) { moveRight() }
                NavigationRegion.LEFT -> withPageTurnOrigin(turnOrigin) { moveLeft() }
            }
        }
        pager.canInterceptPageTurnSwipe = { delta -> canInterceptCurlSwipe(delta) }
        pager.pageTurnSwipeListener = { delta, xFraction, yFraction ->
            val origin = PageTurnOrigin(xFraction, yFraction, PageTurnCause.GESTURE).normalized()
            withPageTurnOrigin(origin) {
                setCurrentItemForPageTurn(pager.currentItem + delta)
            }
        }
        pager.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val holder = (adapter.slots.getOrNull(pager.currentItem) as? PagerSlot.Pages)
                    ?.let { getPageHolder(it.first) }
                val page = holder?.let {
                    val holderLocation = IntArray(2)
                    it.getLocationOnScreen(holderLocation)
                    it.pageAt(event.rawX - holderLocation[0], event.rawY - holderLocation[1])
                }
                if (page != null) {
                    activity.onPageLongTap(page)
                    return@f true
                }
            }
            false
        }

        config.dualPageSplitChangedListener = { enabled ->
            if (!enabled) {
                cleanupPageSplit()
            }
        }

        config.doublePageLayoutChangedListener = {
            pendingProgressCommitAnchor = null
            val anchor = stableSlotAnchor ?: (currentSlot as? PagerSlot.Pages)?.progressPage
            if (!config.dualPageSplit && !config.automaticallySplitsWidePages) {
                adapter.removePageSplitItems()
            }
            requestSlotRebuild(anchor)
        }

        config.imagePropertyChangedListener = {
            if (isIdle) {
                refreshAdapter()
            } else {
                awaitingImageRefresh = true
            }
        }

        config.pageTransitionEffectChangedListener = {
            if (isIdle) {
                applyPageTransitionEffect()
            } else {
                awaitingPageTransitionUpdate = true
            }
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        applyPageTransitionEffect()
    }

    override fun destroy() {
        cancelPendingCoverTurn(reactivateCurrent = false)
        pageFlipController.cancel()
        super.destroy()
        scope.cancel()
    }

    fun onConfigurationChanged() {
        config.onConfigurationChanged()
    }

    /**
     * Creates a new ViewPager.
     */
    abstract fun createPager(): Pager

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return viewerContainer
    }

    /**
     * Returns the PagerPageHolder for the provided page
     */
    private fun getPageHolder(page: ReaderPage): PagerPageHolder? =
        pager.children
            .filterIsInstance(PagerPageHolder::class.java)
            .firstOrNull { it.slot.contains(page) }

    /**
     * Called when a new page (either a [ReaderPage] or [ChapterTransition]) is marked as active
     */
    private fun onPageChange(
        position: Int,
        cause: PageChangeCause = PageChangeCause.USER_NAVIGATION,
        anchor: ReaderPage? = null,
        force: Boolean = false,
    ) {
        val slot = adapter.slots.getOrNull(position)
        if (slot != null && (force || currentSlot != slot)) {
            val page = (slot as? PagerSlot.Pages)?.progressPage
            val previousPage = (currentSlot as? PagerSlot.Pages)?.progressPage
            val allowPreload = checkAllowPreload(page)
            val forward = when {
                previousPage != null && page != null -> {
                    // if both pages have the same number, it's a split page with an InsertPage
                    if (page.number == previousPage.number) {
                        // the InsertPage is always the second in the reading direction
                        page is InsertPage
                    } else {
                        page.number > previousPage.number
                    }
                }
                (currentSlot as? PagerSlot.Transition)?.transition is ChapterTransition.Prev && page != null ->
                    false
                else -> true
            }
            if (cause == PageChangeCause.USER_NAVIGATION &&
                slot is PagerSlot.Transition &&
                slot.transition is ChapterTransition.Next
            ) {
                (currentSlot as? PagerSlot.Pages)?.let { previousSlot ->
                    activity.onPagesSelected(previousSlot.pages)
                    pendingProgressCommitAnchor = null
                }
            }
            currentSlot = slot
            when (slot) {
                is PagerSlot.Pages -> {
                    val selectionAnchor = when (cause) {
                        PageChangeCause.USER_NAVIGATION -> slot.progressPage.also {
                            pendingProgressCommitAnchor = it
                        }
                        PageChangeCause.RESTORE,
                        PageChangeCause.LAYOUT_REBUILD,
                        -> anchor?.takeIf(slot::contains)
                            ?: stableSlotAnchor?.takeIf(slot::contains)
                            ?: slot.first
                    }
                    stableSlotAnchor = selectionAnchor
                    val commitProgress = !config.doublePages ||
                        cause == PageChangeCause.USER_NAVIGATION ||
                        pendingProgressCommitAnchor?.let(slot::contains) == true
                    onReaderPagesSelected(
                        slot = slot,
                        allowPreload = allowPreload,
                        forward = forward,
                        commitProgress = commitProgress,
                        anchor = selectionAnchor,
                    )
                }
                is PagerSlot.Transition -> onTransitionSelected(slot.transition)
            }
        }
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentSlot ?: return true

        // Allow preload for
        // 1. Going to next chapter from chapter transition
        // 2. Going between pages of same chapter
        // 3. Next chapter page
        return when (page.chapter) {
            ((currentSlot as? PagerSlot.Transition)?.transition as? ChapterTransition.Next)?.to -> true
            (currentSlot as? PagerSlot.Pages)?.progressPage?.chapter -> true
            adapter.nextTransition?.to -> true
            else -> false
        }
    }

    /**
     * Called when a [ReaderPage] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onReaderPagesSelected(
        slot: PagerSlot.Pages,
        allowPreload: Boolean,
        forward: Boolean,
        commitProgress: Boolean,
        anchor: ReaderPage,
    ) {
        if (config.doublePages && slot.pages.any { it.spreadKind == ReaderPage.SpreadKind.UNKNOWN }) {
            awaitingPreparedSlot = PendingPreparedSlot(slot, allowPreload, forward, commitProgress, anchor)
            activity.onPagesActivated(slot.pages, anchor)
            getPageHolder(slot.progressPage)?.onPageSelected(forward)
            return
        }
        awaitingPreparedSlot = null
        commitReaderPagesSelected(slot, allowPreload, forward, commitProgress, anchor)
    }

    private fun commitReaderPagesSelected(
        slot: PagerSlot.Pages,
        allowPreload: Boolean,
        forward: Boolean,
        commitProgress: Boolean,
        anchor: ReaderPage,
    ) {
        val page = slot.progressPage
        val pages = page.chapter.pages ?: return
        logcat {
            "onReaderPagesSelected: ${slot.pages.joinToString { it.number.toString() }}/${pages.size} " +
                "commitProgress=$commitProgress anchor=${anchor.number}"
        }
        if (commitProgress) {
            activity.onPagesSelected(slot.pages)
            pendingProgressCommitAnchor = null
        } else {
            activity.onPagesActivated(slot.pages, anchor)
        }

        // Notify holder of page change
        getPageHolder(page)?.onPageSelected(forward)

        // Skip preload on inserts it causes unwanted page jumping
        if (page is InsertPage) {
            return
        }

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
        }
    }

    /**
     * Called when a [ChapterTransition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        } else if (transition is ChapterTransition.Next) {
            // No more chapters, show menu because the user is probably going to close the reader
            activity.showMenu()
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active. If the pager is currently idle,
     * it sets the chapters immediately, otherwise they are saved and set when it becomes idle.
     */
    override fun setChapters(chapters: ViewerChapters) {
        if (isIdle) {
            setChaptersInternal(chapters)
        } else {
            awaitingIdleViewerChapters = chapters
        }
    }

    /**
     * Sets the active [chapters] on this pager.
     */
    private fun setChaptersInternal(chapters: ViewerChapters) {
        cancelPendingCoverTurn(reactivateCurrent = false)
        pageFlipController.cancel()
        // Remove listener so the change in item doesn't trigger it
        pager.removeOnPageChangeListener(pagerListener)

        val forceTransition = config.alwaysShowChapterTransition ||
            adapter.slots.getOrNull(pager.currentItem) is PagerSlot.Transition
        adapter.setChapters(chapters, forceTransition, stableSlotAnchor)

        // Layout the pager once a chapter is being set
        val firstLayout = pager.isGone
        if (pager.isGone) {
            logcat { "Pager first layout" }
            val pages = chapters.currChapter.pages ?: return
            val openingPage = pages[min(chapters.currChapter.requestedPage, pages.lastIndex)]
            pendingProgressCommitAnchor = null
            stableSlotAnchor = openingPage
            adapter.positionOf(openingPage)
                .takeIf { it >= 0 }
                ?.let { pager.setCurrentItem(it, false) }
            pager.isVisible = true
        }

        pager.addOnPageChangeListener(pagerListener)
        // Manually call onPageChange to update the UI
        pendingPageMove = null
        onPageChange(
            position = pager.currentItem,
            cause = if (firstLayout) PageChangeCause.RESTORE else PageChangeCause.LAYOUT_REBUILD,
            anchor = stableSlotAnchor,
        )
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        moveToPage(page, PageChangeCause.USER_NAVIGATION)
    }

    override fun restorePage(page: ReaderPage) {
        pendingProgressCommitAnchor = null
        val targetPosition = adapter.positionOf(page)
        suppressMenuHidingForNextSelection = targetPosition >= 0 && targetPosition != pager.currentItem
        moveToPage(page, PageChangeCause.RESTORE)
        if (targetPosition == pager.currentItem) {
            suppressMenuHidingForNextSelection = false
        }
    }

    private fun moveToPage(page: ReaderPage, cause: PageChangeCause) {
        val position = adapter.positionOf(page)
        if (position != -1) {
            pageFlipController.cancel()
            val currentPosition = pager.currentItem
            stableSlotAnchor = page
            pendingPageMove = PendingPageMove(position, page, cause)
            // Slider jumps, chapter changes, restores, and layout rebuilds are programmatic
            // navigation. Only direct page turns should run the selected transition.
            pager.setCurrentItem(position, false)
            // manually call onPageChange since ViewPager listener is not triggered in this case
            if (currentPosition == position) {
                pendingPageMove = null
                onPageChange(position, cause, page, force = true)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    /**
     * Moves to the next page.
     */
    open fun moveToNext() {
        moveRight()
    }

    /**
     * Moves to the previous page.
     */
    open fun moveToPrevious() {
        moveLeft()
    }

    /**
     * Moves to the page at the right.
     */
    protected open fun moveRight() {
        if (pager.currentItem != adapter.count - 1) {
            val holder = (currentSlot as? PagerSlot.Pages)?.progressPage?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canNavigatePanRight()) {
                holder.navigatePanRight()
            } else {
                setCurrentItemForPageTurn(pager.currentItem + 1)
            }
        }
    }

    /**
     * Moves to the page at the left.
     */
    protected open fun moveLeft() {
        if (pager.currentItem != 0) {
            val holder = (currentSlot as? PagerSlot.Pages)?.progressPage?.let(::getPageHolder)
            if (holder != null && config.navigateToPan && holder.canNavigatePanLeft()) {
                holder.navigatePanLeft()
            } else {
                setCurrentItemForPageTurn(pager.currentItem - 1)
            }
        }
    }

    /**
     * Moves to the page at the top (or previous).
     */
    protected open fun moveUp() {
        moveToPrevious()
    }

    /**
     * Moves to the page at the bottom (or next).
     */
    protected open fun moveDown() {
        moveToNext()
    }

    /**
     * Resets the adapter in order to recreate all the views. Used when a image configuration is
     * changed.
     */
    private fun refreshAdapter() {
        cancelPendingCoverTurn(reactivateCurrent = false)
        pageFlipController.cancel()
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
    }

    private fun shouldAnimatePageTurn(): Boolean =
        config.pageTransitionEffect != PageTransitionEffect.NONE && ValueAnimator.areAnimatorsEnabled()

    private fun setCurrentItemForPageTurn(target: Int) {
        // Mark app-driven page turns so they are distinguishable from viewport resize callbacks.
        markPendingUserNavigation(target)
        if (config.pageTransitionEffect == PageTransitionEffect.COVER &&
            ValueAnimator.areAnimatorsEnabled() &&
            prepareCoverPageTurn(target)
        ) {
            return
        }
        if (config.pageTransitionEffect == PageTransitionEffect.CURL && ValueAnimator.areAnimatorsEnabled()) {
            val origin = activePageTurnOrigin ?: PageTurnOrigin.center(PageTurnCause.KEY)
            val sourceIsPage = adapter.slots.getOrNull(pager.currentItem) is PagerSlot.Pages
            val targetIsPage = adapter.slots.getOrNull(target) is PagerSlot.Pages
            if (sourceIsPage && targetIsPage && pageFlipController.start(target, origin)) {
                return
            }
        }
        pager.setCurrentItem(target, shouldAnimatePageTurn())
    }

    private fun markPendingUserNavigation(target: Int) {
        if (target in adapter.slots.indices) {
            pendingPageMove = PendingPageMove(
                position = target,
                anchor = (adapter.slots[target] as? PagerSlot.Pages)?.progressPage,
                cause = PageChangeCause.USER_NAVIGATION,
            )
        }
    }

    private fun canInterceptCurlSwipe(delta: Int): Boolean {
        if (!pager.horizontalPaging ||
            config.pageTransitionEffect != PageTransitionEffect.CURL ||
            !ValueAnimator.areAnimatorsEnabled() ||
            pageFlipController.isRunning
        ) {
            return false
        }
        val sourceSlot = adapter.slots.getOrNull(pager.currentItem) as? PagerSlot.Pages ?: return false
        val target = pager.currentItem + delta
        if (adapter.slots.getOrNull(target) !is PagerSlot.Pages) return false
        val holder = getPageHolder(sourceSlot.progressPage) ?: return false
        val canPanTowardSwipe = if (delta > 0) {
            holder.canNavigatePanRight()
        } else {
            holder.canNavigatePanLeft()
        }
        return !canPanTowardSwipe
    }

    private fun prepareCoverPageTurn(target: Int): Boolean {
        val delta = target.compareTo(pager.currentItem)
        if (delta == 0) return false
        if (pendingCoverTurn != null || !isIdle) {
            enqueueCoverTurn(delta)
            return true
        }
        val targetSlot = adapter.slots.getOrNull(target) as? PagerSlot.Pages ?: return false
        if (adapter.slots.getOrNull(pager.currentItem) !is PagerSlot.Pages) return false
        if (getPageHolder(targetSlot.progressPage)?.isTransitionTargetReady() == true) {
            cancelPendingCoverTurn(reactivateCurrent = false, clearQueuedTurns = false)
            pager.setCurrentItem(target, true)
            return true
        }

        cancelPendingCoverTurn(reactivateCurrent = false, clearQueuedTurns = false)
        if (!activateSlotForTransition(targetSlot)) {
            pager.setCurrentItem(target, false)
            pager.post(::drainPendingCoverTurn)
            return true
        }
        pendingCoverTurn = PendingCoverTurn(target, targetSlot)
        pendingCoverTurnTimeout = scope.launch {
            delay(COVER_TARGET_READY_TIMEOUT_MS)
            val pending = pendingCoverTurn?.takeIf { it.target == target && it.slot == targetSlot }
                ?: return@launch
            pendingCoverTurn = null
            pendingCoverTurnTimeout = null
            pager.setCurrentItem(pending.target, false)
            pager.post(::drainPendingCoverTurn)
        }
        return true
    }

    internal fun onTransitionTargetReady(slot: PagerSlot.Pages) {
        val pending = pendingCoverTurn?.takeIf { it.slot == slot } ?: return
        if (config.pageTransitionEffect != PageTransitionEffect.COVER ||
            adapter.slots.getOrNull(pending.target) != slot
        ) {
            cancelPendingCoverTurn(reactivateCurrent = true)
            return
        }
        pendingCoverTurnTimeout?.cancel()
        pendingCoverTurnTimeout = null
        pager.post {
            val committed = pendingCoverTurn?.takeIf { it == pending } ?: return@post
            pendingCoverTurn = null
            if (config.pageTransitionEffect == PageTransitionEffect.COVER &&
                adapter.slots.getOrNull(pending.target) == slot &&
                pager.currentItem != pending.target
            ) {
                pager.setCurrentItem(committed.target, true)
            } else {
                pendingCoverTurns.clear()
            }
        }
    }

    internal fun onTransitionTargetFailed(slot: PagerSlot.Pages) {
        val pending = pendingCoverTurn?.takeIf { it.slot == slot } ?: return
        pendingCoverTurn = null
        pendingCoverTurnTimeout?.cancel()
        pendingCoverTurnTimeout = null
        pager.setCurrentItem(pending.target, false)
        pager.post(::drainPendingCoverTurn)
    }

    private fun activateSlotForTransition(slot: PagerSlot.Pages): Boolean {
        val physicalPages = slot.pages.filterNot { it is InsertPage }.distinctBy { it.index }
        val chapter = physicalPages.firstOrNull()?.chapter ?: return false
        if (physicalPages.any { it.chapter !== chapter }) return false
        val loader = chapter.pageLoader ?: return false
        loader.setActivePages(physicalPages)
        return true
    }

    private fun enqueueCoverTurn(delta: Int) {
        if (pendingCoverTurns.size >= MAX_PENDING_COVER_TURNS) return
        pendingCoverTurns.addLast(delta)
    }

    private fun drainPendingCoverTurn() {
        if (!isIdle || pendingCoverTurn != null) return
        if (config.pageTransitionEffect != PageTransitionEffect.COVER || !ValueAnimator.areAnimatorsEnabled()) {
            pendingCoverTurns.clear()
            return
        }
        val delta = pendingCoverTurns.removeFirstOrNull() ?: return
        val target = pager.currentItem + delta
        if (target !in 0 until adapter.count) {
            pendingCoverTurns.clear()
            return
        }
        setCurrentItemForPageTurn(target)
    }

    private fun cancelPendingCoverTurn(
        reactivateCurrent: Boolean,
        clearQueuedTurns: Boolean = true,
    ) {
        val hadPendingTarget = pendingCoverTurn != null || pendingCoverTurnTimeout != null
        if (clearQueuedTurns) pendingCoverTurns.clear()
        if (!hadPendingTarget) return
        pendingCoverTurn = null
        pendingCoverTurnTimeout?.cancel()
        pendingCoverTurnTimeout = null
        if (reactivateCurrent) {
            (adapter.slots.getOrNull(pager.currentItem) as? PagerSlot.Pages)
                ?.let(::activateSlotForTransition)
        }
    }

    private inline fun withPageTurnOrigin(origin: PageTurnOrigin, action: () -> Unit) {
        val previous = activePageTurnOrigin
        activePageTurnOrigin = origin
        try {
            action()
        } finally {
            activePageTurnOrigin = previous
        }
    }

    private fun applyPageTransitionEffect() {
        cancelPendingCoverTurn(reactivateCurrent = true)
        pageFlipController.cancel()
        pageTransitionTransformer?.clear(pager.children.asIterable())
        val effect = config.pageTransitionEffect.takeIf { ValueAnimator.areAnimatorsEnabled() }
            ?: PageTransitionEffect.NONE
        val transformer = effect
            .takeUnless {
                it == PageTransitionEffect.SLIDE ||
                    it == PageTransitionEffect.NONE ||
                    (it == PageTransitionEffect.CURL && pager.horizontalPaging)
            }
            ?.let {
                PagerPageTransformer(
                    effect = it,
                    horizontalPager = pager.horizontalPaging,
                    rightToLeft = this is R2LPagerViewer,
                    readerBackgroundColor = ::currentReaderBackgroundColor,
                )
            }
        pageTransitionTransformer = transformer
        pager.setPageTransformer(false, transformer)
        pager.children.forEach { child ->
            child.alpha = 1f
            child.translationX = 0f
            child.translationY = 0f
            child.translationZ = 0f
            child.rotationX = 0f
            child.rotationY = 0f
            child.scaleX = 1f
            child.scaleY = 1f
            child.cameraDistance = child.resources.displayMetrics.density * 1_280f
            child.pivotX = child.width / 2f
            child.pivotY = child.height / 2f
        }
    }

    private fun currentReaderBackgroundColor(): Int {
        return (activity.binding.readerContainer.background as? ColorDrawable)?.color
            ?: when (config.theme) {
                0 -> Color.WHITE
                2 -> Color.rgb(0x20, 0x21, 0x25)
                else -> Color.BLACK
            }
    }

    private companion object {
        const val COVER_TARGET_READY_TIMEOUT_MS = 1_200L
        const val MAX_PENDING_COVER_TURNS = 3
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP
        val ctrlPressed = event.metaState.and(KeyEvent.META_CTRL_ON) > 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveDown() else moveUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) moveUp() else moveDown()
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isUp) {
                    if (ctrlPressed) moveToNext() else moveRight()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isUp) {
                    if (ctrlPressed) moveToPrevious() else moveLeft()
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_DPAD_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (isUp) moveDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (isUp) moveUp()
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
            when (event.action) {
                MotionEvent.ACTION_SCROLL -> {
                    if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0.0f) {
                        moveDown()
                    } else {
                        moveUp()
                    }
                    return true
                }
            }
        }
        return false
    }

    fun onPageSplit(currentPage: ReaderPage, newPage: InsertPage) {
        activity.runOnUiThread {
            if (!config.dualPageSplit && !config.automaticallySplitsWidePages) return@runOnUiThread
            // Need to insert on UI thread else images will go blank
            adapter.onPageSplit(currentPage, newPage)
        }
    }

    private fun cleanupPageSplit() {
        adapter.removePageSplitItems()
    }

    internal fun requestSlotRebuild(anchor: ReaderPage?) {
        if (isIdle) {
            rebuildSlots(anchor)
        } else {
            awaitingSlotRebuildAnchor = anchor
        }
    }

    internal fun onPagesClassified(classifications: Map<ReaderPage, ReaderPage.SpreadInfo>): Boolean {
        val selection = DoublePageProgressPolicy.classificationAnchor(
            pendingCommitAnchor = pendingProgressCommitAnchor,
            stableAnchor = stableSlotAnchor,
            classifiedPages = classifications.keys.toList(),
        ) ?: return false
        val previousPendingCommitAnchor = pendingProgressCommitAnchor
        if (selection.transfersPendingCommit) {
            // requestSlotRebuild() can rebuild synchronously while the pager is idle.
            pendingProgressCommitAnchor = selection.page
        }
        val layoutChanged = adapter.onPagesClassified(classifications, selection.page)
        if (!layoutChanged && selection.transfersPendingCommit) {
            pendingProgressCommitAnchor = previousPendingCommitAnchor
        }
        return layoutChanged
    }

    internal fun onPagesPrepared(slot: PagerSlot.Pages) {
        val pending = awaitingPreparedSlot ?: return
        if (currentSlot != slot || pending.slot != slot) return
        awaitingPreparedSlot = null
        commitReaderPagesSelected(
            slot = slot,
            allowPreload = pending.allowPreload,
            forward = pending.forward,
            commitProgress = pending.commitProgress,
            anchor = pending.anchor,
        )
    }

    private fun rebuildSlots(anchor: ReaderPage?) {
        cancelPendingCoverTurn(reactivateCurrent = false)
        pageFlipController.cancel()
        val resolvedAnchor = anchor ?: stableSlotAnchor
        stableSlotAnchor = resolvedAnchor
        pager.removeOnPageChangeListener(pagerListener)
        adapter.rebuildSlots(resolvedAnchor)
        currentSlot = null
        pager.addOnPageChangeListener(pagerListener)
        onPageChange(
            position = pager.currentItem,
            cause = PageChangeCause.LAYOUT_REBUILD,
            anchor = resolvedAnchor,
        )
    }

    private data class PendingPreparedSlot(
        val slot: PagerSlot.Pages,
        val allowPreload: Boolean,
        val forward: Boolean,
        val commitProgress: Boolean,
        val anchor: ReaderPage,
    )

    private data class PendingPageMove(
        val position: Int,
        val anchor: ReaderPage?,
        val cause: PageChangeCause,
    )

    private enum class PageChangeCause {
        USER_NAVIGATION,
        RESTORE,
        LAYOUT_REBUILD,
    }
}
