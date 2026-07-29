package eu.kanade.tachiyomi.ui.reader.viewer.pager

internal object DoublePageCompositionPolicy {
    private const val BYTES_PER_PIXEL = 4L
    private const val OVERHEAD_BYTES = 16L * 1024L * 1024L

    data class Image(
        val width: Int,
        val height: Int,
        val compressedBytes: Long,
    )

    fun estimatedPeakBytes(first: Image, second: Image): Long {
        val outputWidth = first.width.toLong() + second.width.toLong()
        val outputHeight = maxOf(first.height, second.height).toLong()
        return first.width.toLong() * first.height * BYTES_PER_PIXEL +
            second.width.toLong() * second.height * BYTES_PER_PIXEL +
            outputWidth * outputHeight * BYTES_PER_PIXEL +
            first.compressedBytes + second.compressedBytes + OVERHEAD_BYTES
    }

    fun shouldCompose(first: Image, second: Image, availableBytes: Long, maxHeapBytes: Long): Boolean {
        if (minOf(first.width, first.height, second.width, second.height) <= 0) return false
        val outputWidth = first.width.toLong() + second.width.toLong()
        val outputHeight = maxOf(first.height, second.height).toLong()
        if (outputWidth > Int.MAX_VALUE || outputHeight > Int.MAX_VALUE) return false
        val limit = minOf((availableBytes * 0.60).toLong(), (maxHeapBytes * 0.35).toLong())
        return estimatedPeakBytes(first, second) <= limit
    }
}

internal object DoublePagePlacement {
    fun firstPageOnLeft(isRightToLeft: Boolean, inverted: Boolean): Boolean =
        !isRightToLeft xor inverted
}
