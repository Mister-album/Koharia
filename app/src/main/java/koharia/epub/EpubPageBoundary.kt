package koharia.epub

internal fun canTurnEpubPage(
    forward: Boolean,
    resourceIndex: Int,
    resourceCount: Int,
    pageIndex: Int,
    pageCount: Int,
): Boolean {
    if (resourceIndex < 0 || pageIndex < 0 || pageCount <= 0) return true
    return if (forward) {
        resourceIndex < resourceCount - 1 || pageIndex < pageCount - 1
    } else {
        resourceIndex > 0 || pageIndex > 0
    }
}
