package eu.kanade.tachiyomi.ui.reader.loader

internal class PageLoadGate(
    private val preloadSize: Int,
) {
    private var activePageIndex: Int? = null
    private var prefetchUnlocked = false
    private var prefetchDirection = Direction.FORWARD

    @Synchronized
    fun activate(pageIndex: Int, pageCount: Int): Activation {
        val previousPageIndex = activePageIndex
        val changed = activePageIndex != pageIndex
        if (previousPageIndex != null) {
            prefetchDirection = when {
                pageIndex < previousPageIndex -> Direction.BACKWARD
                pageIndex > previousPageIndex -> Direction.FORWARD
                else -> prefetchDirection
            }
        }
        activePageIndex = pageIndex
        return Activation(
            changed = changed,
            prefetchIndexes = if (prefetchUnlocked) prefetchIndexes(pageIndex, pageCount) else emptyList(),
        )
    }

    @Synchronized
    fun isActive(pageIndex: Int): Boolean = activePageIndex == pageIndex

    @Synchronized
    fun onPageDisplayed(pageIndex: Int, pageCount: Int): List<Int> {
        if (activePageIndex != pageIndex || pageCount <= 0) return emptyList()
        prefetchUnlocked = true
        return prefetchIndexes(pageIndex, pageCount)
    }

    private fun prefetchIndexes(pageIndex: Int, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        return when (prefetchDirection) {
            Direction.FORWARD -> {
                val first = pageIndex + 1
                val lastExclusive = (first + preloadSize).coerceAtMost(pageCount)
                if (first < lastExclusive) (first until lastExclusive).toList() else emptyList()
            }
            Direction.BACKWARD -> {
                val first = pageIndex - 1
                val lastInclusive = (pageIndex - preloadSize).coerceAtLeast(0)
                if (first >= lastInclusive) (first downTo lastInclusive).toList() else emptyList()
            }
        }
    }

    data class Activation(
        val changed: Boolean,
        val prefetchIndexes: List<Int>,
    )

    private enum class Direction {
        FORWARD,
        BACKWARD,
    }
}
