package eu.kanade.tachiyomi.ui.reader

internal object RemoteProgressConflictPolicy {

    fun hasConflict(
        openingPageIndex: Int,
        currentPageIndex: Int,
        remotePageIndex: Int,
    ): Boolean {
        return remotePageIndex != openingPageIndex && remotePageIndex != currentPageIndex
    }
}
