package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlin.math.max
import kotlin.math.min

internal object DoublePageCompatibilityPolicy {

    private const val MIN_PORTRAIT_ASPECT_RATIO = 0.35f
    private const val MAX_PORTRAIT_ASPECT_RATIO = 0.95f
    private const val MAX_ASPECT_RATIO_DIFFERENCE = 1.25f
    private const val MAX_DIMENSION_DIFFERENCE = 1.35f

    fun canPair(first: ReaderPage.SpreadInfo, second: ReaderPage.SpreadInfo): Boolean {
        if (first.kind == ReaderPage.SpreadKind.UNKNOWN || second.kind == ReaderPage.SpreadKind.UNKNOWN) {
            return true
        }
        if (!first.isRegularPortrait() || !second.isRegularPortrait()) return false

        val firstAspect = first.aspectRatio() ?: return false
        val secondAspect = second.aspectRatio() ?: return false
        if (max(firstAspect, secondAspect) / min(firstAspect, secondAspect) > MAX_ASPECT_RATIO_DIFFERENCE) {
            return false
        }

        val widthDifference = dimensionDifference(first.width, second.width) ?: return false
        val heightDifference = dimensionDifference(first.height, second.height) ?: return false
        return max(widthDifference, heightDifference) <= MAX_DIMENSION_DIFFERENCE
    }

    private fun ReaderPage.SpreadInfo.isRegularPortrait(): Boolean {
        if (kind != ReaderPage.SpreadKind.PAIRABLE) return false
        val aspect = aspectRatio() ?: return false
        return aspect in MIN_PORTRAIT_ASPECT_RATIO..MAX_PORTRAIT_ASPECT_RATIO
    }

    private fun ReaderPage.SpreadInfo.aspectRatio(): Float? {
        val validWidth = width?.takeIf { it > 0 } ?: return null
        val validHeight = height?.takeIf { it > 0 } ?: return null
        return validWidth.toFloat() / validHeight
    }

    private fun dimensionDifference(first: Int?, second: Int?): Float? {
        val validFirst = first?.takeIf { it > 0 } ?: return null
        val validSecond = second?.takeIf { it > 0 } ?: return null
        return max(validFirst, validSecond).toFloat() / min(validFirst, validSecond)
    }
}
