package eu.kanade.tachiyomi.ui.reader.viewer.pager

internal object PagerSelectionPolicy {

    fun shouldHandle(
        selectedPosition: Int,
        pendingPosition: Int?,
        userDragSelectionPending: Boolean,
    ): Boolean {
        return userDragSelectionPending || pendingPosition == selectedPosition
    }
}
