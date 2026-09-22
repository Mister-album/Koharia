package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

data class PagerLayoutState(val anchor: ReaderPage, val commitPending: Boolean)

internal object DoublePageProgressPolicy {

    fun layoutState(
        slot: PagerSlot.Pages?,
        anchor: ReaderPage,
        pendingCommitAnchor: ReaderPage?,
    ): PagerLayoutState = PagerLayoutState(
        anchor = if (anchor is eu.kanade.tachiyomi.ui.reader.model.InsertPage) anchor.parent else anchor,
        commitPending = pendingCommitAnchor != null && slot?.contains(pendingCommitAnchor) == true,
    )

    fun layoutAnchor(
        slot: PagerSlot.Pages,
        requestedAnchor: ReaderPage?,
        previousAnchor: ReaderPage?,
        userNavigation: Boolean,
        layoutRebuild: Boolean = false,
    ): ReaderPage = previousAnchor?.takeIf { layoutRebuild && slot.contains(it) }
        ?: requestedAnchor?.takeIf(slot::contains)
        ?: previousAnchor?.takeIf { !userNavigation && slot.contains(it) }
        ?: slot.first

    fun shouldCommitSelection(
        userNavigation: Boolean,
        restoringSinglePage: Boolean,
        pendingCommitInSlot: Boolean,
    ): Boolean = userNavigation || restoringSinglePage || pendingCommitInSlot

    data class ClassificationAnchor(
        val page: ReaderPage,
        val transfersPendingCommit: Boolean,
    )

    fun classificationAnchor(
        pendingCommitAnchor: ReaderPage?,
        stableAnchor: ReaderPage?,
        classifiedPages: List<ReaderPage>,
    ): ClassificationAnchor? {
        val firstPage = classifiedPages.firstOrNull() ?: return null
        val transfersPendingCommit = pendingCommitAnchor != null &&
            pendingCommitAnchor === stableAnchor &&
            classifiedPages.any { it === stableAnchor }
        return ClassificationAnchor(
            page = if (transfersPendingCommit) firstPage else stableAnchor ?: firstPage,
            transfersPendingCommit = transfersPendingCommit,
        )
    }

    fun activationDisplayPage(
        visiblePages: List<ReaderPage>,
        anchorPage: ReaderPage?,
    ): ReaderPage? {
        return anchorPage
            ?.let { anchor -> visiblePages.firstOrNull { it === anchor || it.index == anchor.index } }
            ?: visiblePageEnd(visiblePages)
    }

    fun visiblePageEnd(visiblePages: List<ReaderPage>): ReaderPage? = visiblePages.maxByOrNull { it.index }
}
