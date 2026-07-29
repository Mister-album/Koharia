package eu.kanade.tachiyomi.ui.reader.viewer.pager

internal object DoublePageCompositionPolicy {
    private const val BYTES_PER_PIXEL = 4L
    private const val OVERHEAD_BYTES = 16L * 1024L * 1024L

    data class Image(
        val width: Int,
        val height: Int,
        val compressedBytes: Long,
    )

    data class CompositionLayout(
        val firstWidth: Int,
        val secondWidth: Int,
        val height: Int,
        val outputWidth: Int,
    )

    fun compositionLayout(first: Image, second: Image): CompositionLayout? {
        if (minOf(first.width, first.height, second.width, second.height) <= 0) return null
        val targetHeight = maxOf(first.height, second.height)
        val firstWidth = first.scaledWidth(targetHeight) ?: return null
        val secondWidth = second.scaledWidth(targetHeight) ?: return null
        val outputWidth = firstWidth.toLong() + secondWidth
        if (outputWidth > Int.MAX_VALUE) return null
        return CompositionLayout(firstWidth, secondWidth, targetHeight, outputWidth.toInt())
    }

    fun estimatedPeakBytes(first: Image, second: Image): Long {
        val layout = compositionLayout(first, second) ?: return Long.MAX_VALUE
        return try {
            listOf(
                bitmapBytes(first.width, first.height),
                bitmapBytes(second.width, second.height),
                bitmapBytes(layout.outputWidth, layout.height),
                first.compressedBytes,
                second.compressedBytes,
                OVERHEAD_BYTES,
            ).fold(0L) { total, value -> Math.addExact(total, value) }
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    fun shouldCompose(first: Image, second: Image, availableBytes: Long, maxHeapBytes: Long): Boolean {
        compositionLayout(first, second) ?: return false
        val limit = minOf((availableBytes * 0.60).toLong(), (maxHeapBytes * 0.35).toLong())
        return estimatedPeakBytes(first, second) <= limit
    }

    private fun Image.scaledWidth(targetHeight: Int): Int? {
        val numerator = width.toLong() * targetHeight
        val scaledWidth = (numerator + height - 1L) / height
        return scaledWidth.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun bitmapBytes(width: Int, height: Int): Long =
        Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), BYTES_PER_PIXEL)
}

internal object DoublePagePlacement {
    fun firstPageOnLeft(isRightToLeft: Boolean, inverted: Boolean): Boolean =
        !isRightToLeft xor inverted
}
