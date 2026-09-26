package eu.kanade.tachiyomi.ui.reader.viewer.pager

import eu.kanade.tachiyomi.ui.reader.setting.MergedPageLayout

internal object MergedPageExportPolicy {
    data class Placement(val width: Int, val height: Int, val top: Int)
    data class Layout(val first: Placement, val second: Placement, val width: Int, val height: Int)

    fun layout(
        first: DoublePageCompositionPolicy.Image,
        second: DoublePageCompositionPolicy.Image,
        mode: MergedPageLayout,
    ): Layout {
        require(minOf(first.width, first.height, second.width, second.height) > 0)
        if (mode == MergedPageLayout.MATCH_HEIGHT) {
            val normalized = requireNotNull(DoublePageCompositionPolicy.compositionLayout(first, second))
            return Layout(
                Placement(normalized.firstWidth, normalized.height, 0),
                Placement(normalized.secondWidth, normalized.height, 0),
                normalized.outputWidth,
                normalized.height,
            )
        }
        val height = maxOf(first.height, second.height)
        return Layout(
            Placement(first.width, first.height, (height - first.height) / 2),
            Placement(second.width, second.height, (height - second.height) / 2),
            Math.addExact(first.width, second.width),
            height,
        )
    }

    fun fitsMemory(
        layout: Layout,
        first: DoublePageCompositionPolicy.Image,
        second: DoublePageCompositionPolicy.Image,
        available: Long,
        heap: Long,
    ): Boolean {
        val bytes = try {
            val output = Math.multiplyExact(layout.width.toLong(), layout.height.toLong())
            val input = maxOf(first.width.toLong() * first.height, second.width.toLong() * second.height)
            Math.addExact(Math.multiplyExact(Math.addExact(output, input), 4), 16L * 1024 * 1024)
        } catch (_: ArithmeticException) {
            return false
        }
        return bytes <= minOf((available * 0.60).toLong(), (heap * 0.35).toLong())
    }

    fun supportsWebp(width: Int, height: Int, sdk: Int): Boolean = sdk >= 30 && width in 1..16383 && height in 1..16383
}
