package koharia.reader.resampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.decoder.FilteredRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import java.util.concurrent.CancellationException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Input bitmaps are bounded independently of the source image size; codec-internal memory is separate. */
internal class MitchellRegionDecoder(
    private val cropBorders: Boolean,
    private val displayProfile: ByteArray,
    private val thresholdPercent: Int = MoireReductionPolicy.DEFAULT_THRESHOLD,
) : FilteredRegionDecoder {
    @Volatile private var encoded: ByteArray? = null

    @Volatile private var fallback: ImageDecoder? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var evenCrop = false

    override fun init(context: Context, provider: InputProvider): Point {
        val bytes = checkNotNull(provider.openStream()).use { it.readBytes() }
        val format = ImageDecoder.findType(bytes)?.format
        val image = checkNotNull(ImageDecoder.newInstance(bytes.inputStream(), cropBorders, displayProfile))
        imageWidth = image.width
        imageHeight = image.height
        evenCrop = format == Format.Webp
        if (format in listOf(Format.Jpeg, Format.Png, Format.Webp)) {
            encoded = bytes
            image.recycle()
        } else {
            fallback = image
        }
        return Point(imageWidth, imageHeight)
    }

    override fun isReady(): Boolean = encoded != null || fallback?.isRecycled == false

    override fun isFilteringEnabled(): Boolean = encoded != null

    override fun shouldFilter(displayScale: Float): Boolean =
        isFilteringEnabled() && MoireReductionPolicy.shouldFilter(displayScale, thresholdPercent)

    override fun recycle() {
        encoded = null
        val image = fallback
        fallback = null
        image?.recycle()
    }

    // The existing codecs retain only the last colour transform. A fresh instance per
    // decode releases every transform and avoids sharing mutable native state between tiles.
    private fun decodeInput(region: Rect, sample: Int): Bitmap {
        require((region.width().toLong() / sample) * (region.height() / sample) <= 2L * 1024 * 1024) {
            "Oversized resampling input"
        }
        val bytes = encoded ?: throw CancellationException()
        val image = checkNotNull(ImageDecoder.newInstance(bytes.inputStream(), cropBorders, displayProfile))
        try {
            val bitmap = checkNotNull(image.decode(region, sample))
            if (!MitchellResampler.premultiply(bitmap)) {
                bitmap.recycle()
                error("Unable to premultiply decoded pixels")
            }
            return bitmap
        } finally {
            image.recycle()
        }
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap =
        decodeRegion(sRect, sampleSize, 1f, BooleanSupplier { false })

    override fun decodeRegion(
        region: Rect,
        sampleSize: Int,
        scaleFactor: Float,
        filter: Boolean,
        cancelled: BooleanSupplier,
    ): Bitmap {
        fun checkActive() {
            if (cancelled.asBoolean || !isReady() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        checkActive()
        while (!workers.tryAcquire(50, TimeUnit.MILLISECONDS)) checkActive()
        try {
            checkActive()
            fallback?.let { return checkNotNull(it.decode(region, sampleSize)) }
            val scale = (scaleFactor.toDouble() / sampleSize).coerceAtMost(1.0)
            val rawSample = Integer.highestOneBit((sampleSize / scaleFactor).toInt().coerceAtLeast(1))
                .coerceAtMost(Integer.highestOneBit(minOf(imageWidth, imageHeight).coerceAtLeast(1)))
            if (!filter) {
                return decodeInput(region, rawSample).also { bitmap ->
                    try {
                        checkActive()
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
            if (scale >= 1.0) return decodeInput(region, 1)
            try {
                return filtered(
                    region,
                    RegionResamplingPlan(scale, evenCrop, minOf(imageWidth, imageHeight)),
                    ::checkActive,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                logcat(LogPriority.WARN, error) { "Mitchell tile allocation failed; using original region decoder" }
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Mitchell tile failed; using original region decoder" }
            }
            checkActive()
            return decodeInput(region, rawSample)
        } finally {
            workers.release()
        }
    }

    private fun filtered(
        region: Rect,
        plan: RegionResamplingPlan,
        checkActive: () -> Unit,
    ): Bitmap {
        val left = plan.outputBoundary(region.left)
        val top = plan.outputBoundary(region.top)
        val width = (plan.outputBoundary(region.right) - left).coerceAtLeast(1)
        val height = (plan.outputBoundary(region.bottom) - top).coerceAtLeast(1)
        require(width.toLong() * height <= 1024L * 1024) { "Oversized filtered tile" }
        var output: Bitmap? = null
        try {
            for (y in 0 until height step BLOCK_SIZE) {
                for (x in 0 until width step BLOCK_SIZE) {
                    checkActive()
                    val columns = minOf(BLOCK_SIZE, width - x)
                    val rows = minOf(BLOCK_SIZE, height - y)
                    val inputRegion = Rect(
                        plan.inputStart(left + x),
                        plan.inputStart(top + y),
                        plan.inputEnd(left + x + columns, imageWidth),
                        plan.inputEnd(top + y + rows, imageHeight),
                    )
                    val input = decodeInput(inputRegion, plan.inputSample)
                    try {
                        checkActive()
                        val target = output ?: Bitmap.createBitmap(
                            width,
                            height,
                            Bitmap.Config.ARGB_8888,
                            true,
                            input.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),
                        ).also { output = it }
                        check(
                            MitchellResampler.resize(
                                input, target,
                                ((left + x) / plan.scale - inputRegion.left) / plan.inputSample,
                                ((top + y) / plan.scale - inputRegion.top) / plan.inputSample,
                                ((left + x + columns) / plan.scale - inputRegion.left) / plan.inputSample,
                                ((top + y + rows) / plan.scale - inputRegion.top) / plan.inputSample,
                                x, y, columns, rows,
                            ),
                        ) { "Native Mitchell resize failed" }
                    } finally {
                        input.recycle()
                    }
                }
            }
            checkActive()
            return checkNotNull(output)
        } catch (error: Throwable) {
            output?.recycle()
            throw error
        }
    }

    companion object {
        private const val BLOCK_SIZE = 256
        private val workers = Semaphore(2, true)
    }
}
