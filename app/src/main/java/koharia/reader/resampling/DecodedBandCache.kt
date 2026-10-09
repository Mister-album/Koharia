package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect

/** Reuses decoded scanlines across adjacent regions within the input and cache budgets. */
internal class DecodedBandCache(
    private val budgetBytes: Long,
    private val inputPixels: Int = 2 * 1024 * 1024,
    private val maxBandRows: Int = 256,
) {
    init {
        require(maxBandRows in 1..1024)
    }

    private data class Key(val sample: Int, val row: Int)

    private val bands = LinkedHashMap<Key, Bitmap>(16, .75f, true)
    internal var retainedBytes = 0L
        private set
    internal var decodeCount = 0
        private set

    private fun bandRows(width: Int): Int = minOf(
        maxBandRows.toLong(),
        inputPixels.toLong() / width.coerceAtLeast(1),
        budgetBytes / 8 / width.coerceAtLeast(1),
    ).toInt()

    /** Leave one band for arbitrary row alignment so adjacent blocks can reuse their entire halo. */
    fun reusableRows(sourceWidth: Int, sample: Int): Int {
        val width = (sourceWidth / sample).coerceAtLeast(1)
        val rows = bandRows(width)
        if (rows == 0) return 0
        return ((budgetBytes / (width.toLong() * rows * 4) - 1).coerceAtLeast(1) * rows)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Synchronized
    fun decode(
        region: Rect,
        sample: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        checkActive: () -> Unit,
        decode: (Rect, Int) -> Bitmap,
    ): Bitmap {
        checkActive()
        val width = sourceWidth / sample
        // Keep a band below 2 Mi pixels and below half the cache budget, including narrow images.
        val rows = bandRows(width)
        if (rows < 1 || region.left % sample != 0 || region.top % sample != 0) return decode(region, sample)
        val outputWidth = region.width() / sample
        val outputHeight = region.height() / sample
        require(outputWidth.toLong() * outputHeight <= inputPixels) { "Oversized resampling input" }
        var output: Bitmap? = null
        try {
            var copied = 0
            while (copied < outputHeight) {
                checkActive()
                val sourceRow = region.top / sample + copied
                val bandRow = sourceRow / rows * rows
                val key = Key(sample, bandRow)
                val band = bands[key] ?: run {
                    val bottom = minOf(sourceHeight / sample, bandRow + rows)
                    val bytes = width.toLong() * (bottom - bandRow) * 4
                    trimTo(budgetBytes - bytes)
                    val decoded = decode(Rect(0, bandRow * sample, width * sample, bottom * sample), sample)
                    try {
                        checkActive()
                    } catch (error: Throwable) {
                        decoded.recycle()
                        throw error
                    }
                    decodeCount++
                    bands[key] = decoded
                    retainedBytes += decoded.allocationByteCount
                    decoded
                }
                val target = output ?: Bitmap.createBitmap(
                    outputWidth,
                    outputHeight,
                    Bitmap.Config.ARGB_8888,
                    true,
                    checkNotNull(band.colorSpace),
                ).also { output = it }
                val offset = sourceRow - bandRow
                val count = minOf(outputHeight - copied, band.height - offset)
                check(count > 0)
                copyCanvas.setBitmap(target)
                copyCanvas.drawBitmap(
                    band,
                    Rect(region.left / sample, offset, region.left / sample + outputWidth, offset + count),
                    Rect(0, copied, outputWidth, copied + count),
                    copyPaint,
                )
                copied += count
            }
            checkActive()
            return checkNotNull(output)
        } catch (error: Throwable) {
            output?.recycle()
            throw error
        } finally {
            copyCanvas.setBitmap(null)
        }
    }

    @Synchronized
    fun clear() = trimTo(0)

    private fun trimTo(bytes: Long) {
        val iterator = bands.values.iterator()
        while (retainedBytes > bytes && iterator.hasNext()) {
            val bitmap = iterator.next()
            retainedBytes -= bitmap.allocationByteCount
            bitmap.recycle()
            iterator.remove()
        }
    }

    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val copyCanvas = Canvas()
}
