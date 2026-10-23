package eu.kanade.tachiyomi.ui.reader.loader

import kotlin.math.abs

internal class PageLoadGate(
    private val preloadSize: Int = 2,
    private val prefetchOnActivate: Boolean = false,
    private val prefetchPageCount: Int? = null,
) {
    private var activePageIndexes: Set<Int> = emptySet()
    private var logicalPageIndex: Int? = null
    private var prefetchUnlocked = false
    private var prefetchDirection = Direction.FORWARD

    @Synchronized
    fun activate(pageIndex: Int, pageCount: Int): Activation {
        return activate(setOf(pageIndex), pageIndex, pageCount)
    }

    @Synchronized
    fun activate(pageIndexes: Set<Int>, logicalPageIndex: Int, pageCount: Int): Activation {
        val normalizedIndexes = pageIndexes.filterTo(linkedSetOf()) { it in 0 until pageCount }
        val previousPageIndex = this.logicalPageIndex
        val previousPageCount = activePageIndexes.size.coerceAtLeast(1)
        val changed = activePageIndexes != normalizedIndexes || previousPageIndex != logicalPageIndex
        if (previousPageIndex != null) {
            // Selection is immediate even when decoding lags behind. Follow the latest
            // selected page, otherwise a rapid reversal keeps prefetching the old direction.
            val directionAnchor = previousPageIndex
            prefetchDirection = when {
                logicalPageIndex < directionAnchor -> Direction.BACKWARD
                logicalPageIndex > directionAnchor -> Direction.FORWARD
                else -> prefetchDirection
            }
            val adjacentDistance = maxOf(previousPageCount, normalizedIndexes.size.coerceAtLeast(1))
            if (abs(logicalPageIndex - previousPageIndex) > adjacentDistance) {
                prefetchUnlocked = false
            }
        }
        activePageIndexes = normalizedIndexes
        this.logicalPageIndex = logicalPageIndex
        return Activation(
            changed = changed,
            prefetchIndexes = if (prefetchUnlocked || prefetchOnActivate) {
                prefetchIndexes(pageCount)
            } else {
                emptyList()
            },
        )
    }

    @Synchronized
    fun isActive(pageIndex: Int): Boolean = pageIndex in activePageIndexes

    @Synchronized
    fun onPageDisplayed(pageIndex: Int, pageCount: Int): List<Int> {
        return onPagesDisplayed(setOf(pageIndex), pageCount)
    }

    @Synchronized
    fun onPagesDisplayed(pageIndexes: Set<Int>, pageCount: Int): List<Int> {
        if (activePageIndexes.isEmpty() || activePageIndexes != pageIndexes || pageCount <= 0) {
            return emptyList()
        }
        prefetchUnlocked = true
        return prefetchIndexes(pageCount)
    }

    private fun prefetchIndexes(pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val logicalSpreadSize = activePageIndexes.size.coerceAtLeast(1)
        val targetSize = prefetchPageCount?.coerceAtLeast(1)
            ?: logicalSpreadSize.coerceAtMost(preloadSize.coerceAtLeast(1))
        return when (prefetchDirection) {
            Direction.FORWARD -> {
                val first = (activePageIndexes.maxOrNull() ?: return emptyList()) + 1
                val lastExclusive = (first + targetSize).coerceAtMost(pageCount)
                if (first < lastExclusive) (first until lastExclusive).toList() else emptyList()
            }
            Direction.BACKWARD -> {
                val first = (activePageIndexes.minOrNull() ?: return emptyList()) - 1
                val lastInclusive = (first - targetSize + 1).coerceAtLeast(0)
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
