package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

internal object DoublePageProgressPolicy {

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
}
