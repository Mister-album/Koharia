package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.PointF
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
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
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [ViewPager].
 */
@Suppress("LeakingThis")
abstract class PagerViewer(val activity: ReaderActivity) : Viewer {

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

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentSlot: PagerSlot? = null

    /** Physical page that must remain visible while the current spread is rebuilt. */
    private var stableSlotAnchor: ReaderPage? = null

    private var awaitingSlotRebuildAnchor: ReaderPage? = null

    private var awaitingPreparedSlot: PendingPreparedSlot? = null

    private var pendingPageMove: PendingPageMove? = null

    private var pendingProgressCommitAnchor: ReaderPage? = null

    private var awaitingImageRefresh = false

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
                awaitingIdleViewerChapters?.let { viewerChapters ->
                    setChaptersInternal(viewerChapters)
                    awaitingIdleViewerChapters = null
                    if (viewerChapters.currChapter.pages?.size == 1) {
                        adapter.nextTransition?.to?.let(activity::requestPreloadChapter)
                    }
                }
            }
        }

    private val pagerListener = object : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            if (!activity.isScrollingThroughPages) {
                activity.hideMenu()
            }
            val pendingMove = pendingPageMove?.takeIf { it.position == position }
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
            isIdle = state == ViewPager.SCROLL_STATE_IDLE
        }
    }

    init {
        pager.isVisible = false // Don't layout the pager yet
        pager.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        pager.isFocusable = false
        pager.offscreenPageLimit = 1
        pager.id = R.id.reader_pager
        pager.adapter = adapter
        pager.addOnPageChangeListener(pagerListener)
        pager.tapListener = { event ->
            val viewPosition = IntArray(2)
            pager.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            pager.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / pager.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / pager.height,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT -> moveToNext()
                NavigationRegion.PREV -> moveToPrevious()
                NavigationRegion.RIGHT -> moveRight()
                NavigationRegion.LEFT -> moveLeft()
            }
        }
        pager.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val holder = (adapter.slots.getOrNull(pager.currentItem) as? PagerSlot.Pages)
                    ?.let { getPageHolder(it.first) }
                val page = holder?.pageAt(event.x, event.y)
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
                adapter.cleanupPageSplit()
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

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }
    }

    override fun destroy() {
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
        return pager
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
        moveToPage(page, PageChangeCause.RESTORE)
    }

    private fun moveToPage(page: ReaderPage, cause: PageChangeCause) {
        val position = adapter.positionOf(page)
        if (position != -1) {
            val currentPosition = pager.currentItem
            stableSlotAnchor = page
            pendingPageMove = PendingPageMove(position, page, cause)
            pager.setCurrentItem(position, true)
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
                pager.setCurrentItem(pager.currentItem + 1, config.usePageTransitions)
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
                pager.setCurrentItem(pager.currentItem - 1, config.usePageTransitions)
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
        val currentItem = pager.currentItem
        adapter.refresh()
        pager.adapter = adapter
        pager.setCurrentItem(currentItem, false)
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
        adapter.cleanupPageSplit()
    }

    internal fun requestSlotRebuild(anchor: ReaderPage?) {
        if (isIdle) {
            rebuildSlots(anchor)
        } else {
            awaitingSlotRebuildAnchor = anchor
        }
    }

    internal fun onPagesClassified(classifications: Map<ReaderPage, ReaderPage.SpreadInfo>): Boolean {
        val stableAnchor = stableSlotAnchor
        val anchor = if (
            pendingProgressCommitAnchor != null &&
            stableAnchor != null &&
            stableAnchor in classifications
        ) {
            classifications.keys.first()
        } else {
            stableAnchor ?: classifications.keys.firstOrNull() ?: return false
        }
        return adapter.onPagesClassified(classifications, anchor)
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
        val anchor: ReaderPage,
        val cause: PageChangeCause,
    )

    private enum class PageChangeCause {
        USER_NAVIGATION,
        RESTORE,
        LAYOUT_REBUILD,
    }
}
