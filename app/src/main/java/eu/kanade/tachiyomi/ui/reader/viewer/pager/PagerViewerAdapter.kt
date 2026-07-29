package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
import eu.kanade.tachiyomi.util.system.createReaderThemeContext
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import tachiyomi.core.common.util.system.logcat

class PagerViewerAdapter(private val viewer: PagerViewer) : ViewPagerAdapter() {

    var slots: MutableList<PagerSlot> = mutableListOf()
        private set

    private var sourceItems: MutableList<SourceItem> = mutableListOf()
    private var preprocessed: MutableMap<Int, InsertPage> = mutableMapOf()

    var nextTransition: ChapterTransition.Next? = null
        private set

    var currentChapter: ReaderChapter? = null

    private var readerThemedContext = viewer.activity.createReaderThemeContext()

    fun setChapters(
        chapters: ViewerChapters,
        forceTransition: Boolean,
        anchor: ReaderPage?,
    ) {
        val newItems = mutableListOf<SourceItem>()

        val prevHasMissingChapters = calculateChapterGap(chapters.currChapter, chapters.prevChapter) > 0
        val nextHasMissingChapters = calculateChapterGap(chapters.nextChapter, chapters.currChapter) > 0

        chapters.prevChapter?.pages?.mapTo(newItems) { SourceItem.Page(it) }
        if (prevHasMissingChapters || forceTransition || chapters.prevChapter?.state !is ReaderChapter.State.Loaded) {
            newItems.add(SourceItem.Transition(ChapterTransition.Prev(chapters.currChapter, chapters.prevChapter)))
        }

        var insertPageLastPage: InsertPage? = null
        chapters.currChapter.pages?.let { chapterPages ->
            val pages = chapterPages.toMutableList()
            val lastPage = pages.lastOrNull()
            preprocessed.keys.sortedDescending().forEach { key ->
                if (lastPage?.index == key) insertPageLastPage = preprocessed[key]
                preprocessed[key]?.let { pages.add(key + 1, it) }
            }
            pages.mapTo(newItems) { SourceItem.Page(it) }
        }

        currentChapter = chapters.currChapter
        nextTransition = ChapterTransition.Next(chapters.currChapter, chapters.nextChapter).also { transition ->
            if (nextHasMissingChapters || forceTransition ||
                chapters.nextChapter?.state !is ReaderChapter.State.Loaded
            ) {
                newItems.add(SourceItem.Transition(transition))
            }
        }
        chapters.nextChapter?.pages?.mapTo(newItems) { SourceItem.Page(it) }

        preprocessed = mutableMapOf()
        sourceItems = newItems
        rebuildSlots(anchor)

        insertPageLastPage?.let(viewer::moveToPage)
    }

    override fun getCount(): Int = slots.size

    override fun createView(container: ViewGroup, position: Int): View {
        return when (val slot = slots[position]) {
            is PagerSlot.Pages -> PagerPageHolder(readerThemedContext, viewer, slot)
            is PagerSlot.Transition -> PagerTransitionHolder(readerThemedContext, viewer, slot.transition)
        }
    }

    override fun getItemPosition(view: Any): Int {
        if (view is PositionableView) {
            val position = when (val item = view.item) {
                is PagerSlot -> slots.indexOf(item)
                is ChapterTransition -> slots.indexOfFirst { it is PagerSlot.Transition && it.transition == item }
                else -> -1
            }
            if (position != -1) return position
            logcat { "Position for ${view.item} not found" }
        }
        return POSITION_NONE
    }

    fun currentSlot(): PagerSlot? = slots.getOrNull(viewer.pager.currentItem)

    fun positionOf(page: ReaderPage): Int = slots.indexOfFirst { slot ->
        slot is PagerSlot.Pages && slot.contains(page)
    }

    fun rebuildSlots(anchor: ReaderPage? = currentSlot()?.let(::anchorPage)) {
        slots = resolvedSlots().toMutableList()
        notifyDataSetChanged()

        anchor?.let(::positionOf)
            ?.takeIf { it >= 0 }
            ?.let { viewer.pager.setCurrentItem(it, false) }
    }

    fun onPagesClassified(
        classifications: Map<ReaderPage, ReaderPage.SpreadInfo>,
        anchor: ReaderPage,
    ): Boolean {
        var changed = false
        classifications.forEach { (page, info) ->
            if (page.spreadInfo != info) {
                page.spreadInfo = info
                changed = true
            }
        }
        if (!changed || resolvedSlots() == slots) return false
        viewer.requestSlotRebuild(anchor)
        return true
    }

    fun onPageSplit(currentPage: Any?, newPage: InsertPage) {
        if (currentPage !is ReaderPage) return
        if (currentPage.chapter.chapter.id != currentChapter?.chapter?.id) {
            preprocessed[newPage.index] = newPage
            return
        }

        val currentIndex = sourceItems.indexOfFirst { it is SourceItem.Page && it.page === currentPage }
        if (currentIndex < 0) return
        if ((sourceItems.getOrNull(currentIndex + 1) as? SourceItem.Page)?.page is InsertPage) return
        sourceItems.add(currentIndex + 1, SourceItem.Page(newPage))
        rebuildSlots(currentPage)
    }

    fun cleanupPageSplit() {
        val changed = sourceItems.removeAll { it is SourceItem.Page && it.page is InsertPage } ||
            preprocessed.isNotEmpty()
        preprocessed.clear()
        if (changed) rebuildSlots()
    }

    fun refresh() {
        readerThemedContext = viewer.activity.createReaderThemeContext()
    }

    private fun buildSinglePageSlots(): List<PagerSlot> = sourceItems.map { item ->
        when (item) {
            is SourceItem.Page -> PagerSlot.Pages(item.page)
            is SourceItem.Transition -> PagerSlot.Transition(item.transition)
        }
    }

    private fun buildDoublePageSlots(): List<PagerSlot> {
        val result = mutableListOf<PagerSlot>()
        val segment = mutableListOf<ReaderPage>()

        fun flushSegment() {
            if (segment.isEmpty()) return
            val layout = DoublePagePairer.pair(
                soloPages = segment.map { it.spreadKind.occupiesFullSlot },
                shift = viewer.config.shiftDoublePages,
                canPair = { first, second ->
                    !viewer.config.usesContentAwarePairing ||
                        DoublePageCompatibilityPolicy.canPair(
                            segment[first].spreadInfo,
                            segment[second].spreadInfo,
                        )
                },
            )
            layout.forEach { slot ->
                result += PagerSlot.Pages(segment[slot.first], slot.second?.let(segment::get))
            }
            segment.clear()
        }

        sourceItems.forEach { item ->
            when (item) {
                is SourceItem.Page -> {
                    val page = item.page
                    if (segment.lastOrNull()?.chapter?.chapter?.id != null &&
                        segment.last().chapter.chapter.id != page.chapter.chapter.id
                    ) {
                        flushSegment()
                    }
                    segment += page
                }
                is SourceItem.Transition -> {
                    flushSegment()
                    result += PagerSlot.Transition(item.transition)
                }
            }
        }
        flushSegment()
        return result
    }

    private fun resolvedSlots(): List<PagerSlot> {
        val forwardSlots = if (viewer.config.doublePages) buildDoublePageSlots() else buildSinglePageSlots()
        return if (viewer is R2LPagerViewer) forwardSlots.asReversed() else forwardSlots
    }

    private fun anchorPage(slot: PagerSlot): ReaderPage? = (slot as? PagerSlot.Pages)?.progressPage

    private sealed interface SourceItem {
        data class Page(val page: ReaderPage) : SourceItem
        data class Transition(val transition: ChapterTransition) : SourceItem
    }
}
