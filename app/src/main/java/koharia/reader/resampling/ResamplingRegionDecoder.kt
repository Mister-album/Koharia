package koharia.reader.resampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.decoder.FilteredRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import com.davemorrissey.labs.subscaleview.provider.OpenStreamProvider
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.Format
import tachiyomi.decoder.ImageDecoder
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/** Input bitmaps are bounded independently of the source image size; codec-internal memory is separate. */
internal class ResamplingRegionDecoder(
    private val cropBorders: Boolean,
    private val displayProfile: ByteArray,
    private val options: ResamplingOptions = ResamplingOptions(),
    private val budget: ResamplingBudget = ResamplingBudget(),
    private val enabled: Boolean = true,
    private val scheduler: ResamplingScheduler = ResamplingScheduler.shared,
    private val timings: ResamplingTimings? = null,
) : FilteredRegionDecoder {
    @Volatile private var encodedFile: File? = null

    @Volatile private var fallback: ImageDecoder? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var evenCrop = false
    private var cachePngBands = false
    private var cacheJpegBands = false

    @Volatile private var filteringFailed = false
    private var inputCache = DecodedBandCache(budget.cacheBytes, budget.inputPixels)
    internal val nativeDecodeCount = AtomicInteger()

    override fun init(context: Context, provider: InputProvider): Point {
        recycle()
        val file = File.createTempFile("koharia-mitchell-", ".image", context.cacheDir)
        try {
            timings.measure(ResamplingTimings.Stage.COPY_INPUT) {
                checkNotNull(provider.openStream()).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val format = ImageDecoder.findType(file.inputStream().use(::readHeader))?.format
            val image = checkNotNull(
                timings.measure(ResamplingTimings.Stage.OPEN_CODEC) {
                    file.inputStream().use { input ->
                        ImageDecoder.newInstance(input, cropBorders, displayProfile)
                    }
                },
            )
            imageWidth = image.width
            imageHeight = image.height
            evenCrop = format == Format.Webp
            cachePngBands = format == Format.Png
            cacheJpegBands =
                format == Format.Jpeg && budget.reuseJpegBands && options.kernel != ResamplingKernel.MITCHELL
            inputCache = DecodedBandCache(
                budget.cacheBytes,
                budget.inputPixels,
                when {
                    cacheJpegBands -> 1024
                    options.kernel == ResamplingKernel.MITCHELL -> 256
                    else -> budget.pngBandRows
                },
            )
            filteringFailed = false
            if (format in listOf(Format.Jpeg, Format.Png, Format.Webp)) {
                encodedFile = file
                if (enabled) image.recycle() else fallback = image
            } else {
                fallback = image
                file.delete()
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        return Point(imageWidth, imageHeight)
    }

    override fun isReady(): Boolean = encodedFile != null || fallback?.isRecycled == false

    override fun recordsTaskQueueTiming(): Boolean = timings != null

    override fun recordTaskQueueWait(elapsedNanos: Long) {
        timings?.record(ResamplingTimings.Stage.WAIT_EXECUTOR, elapsedNanos)
    }

    fun reconfigured(context: Context, settings: ReaderResamplingSettings): ResamplingRegionDecoder? {
        val file = encodedFile ?: return null
        return ResamplingRegionDecoder(
            cropBorders,
            displayProfile,
            settings.options,
            budget,
            settings.enabled,
            scheduler,
            timings,
        ).also { replacement -> replacement.init(context, OpenStreamProvider(file.inputStream())) }
    }

    override fun isFilteringEnabled(): Boolean = encodedFile != null

    override fun shouldFilter(displayScale: Float): Boolean =
        enabled && !filteringFailed && isFilteringEnabled() && options.shouldFilter(displayScale)

    override fun recycle() {
        inputCache.clear()
        encodedFile?.let { file ->
            encodedFile = null
            file.delete()
        }
        val image = fallback
        fallback = null
        image?.recycle()
    }

    // The existing codecs retain only the last colour transform. A fresh instance per
    // decode releases every transform and avoids sharing mutable native state between tiles.
    private fun decodeInput(region: Rect, sample: Int): Bitmap {
        require((region.width().toLong() / sample) * (region.height() / sample) <= budget.inputPixels) {
            "Oversized resampling input"
        }
        val file = encodedFile ?: throw CancellationException()
        val image = checkNotNull(
            timings.measure(ResamplingTimings.Stage.OPEN_CODEC) {
                file.inputStream().use { input ->
                    ImageDecoder.newInstance(input, cropBorders, displayProfile)
                }
            },
        )
        try {
            nativeDecodeCount.incrementAndGet()
            val bitmap = timings.measure(ResamplingTimings.Stage.DECODE_PIXELS) {
                checkNotNull(image.decode(region, sample))
            }
            if (!timings.measure(ResamplingTimings.Stage.PREMULTIPLY) { ImageResampler.premultiply(bitmap) }) {
                bitmap.recycle()
                error("Unable to premultiply decoded pixels")
            }
            return bitmap
        } finally {
            timings.measure(ResamplingTimings.Stage.RECYCLE_CODEC) { image.recycle() }
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
    ): Bitmap = decodeResult(region, sampleSize, scaleFactor, filter, cancelled).bitmap

    override fun decodeResult(
        region: Rect,
        sampleSize: Int,
        scaleFactor: Float,
        filter: Boolean,
        cancelled: BooleanSupplier,
    ): FilteredRegionDecoder.Result {
        fun result(bitmap: Bitmap, filtered: Boolean = false) = FilteredRegionDecoder.Result(bitmap, filtered)
        fun checkActive() {
            if (cancelled.asBoolean || !isReady() || Thread.currentThread().isInterrupted) throw CancellationException()
        }
        checkActive()
        timings.measure(ResamplingTimings.Stage.WAIT_PERMIT) {
            while (!scheduler.permits.tryAcquire(50, TimeUnit.MILLISECONDS)) checkActive()
        }
        try {
            checkActive()
            // The tiled pyramid stays calibrated even when interpolation is disabled.
            val rawSample = Integer.highestOneBit((sampleSize / scaleFactor).toInt().coerceAtLeast(1))
                .coerceAtMost(Integer.highestOneBit(minOf(imageWidth, imageHeight).coerceAtLeast(1)))
            fallback?.let {
                return result(
                    timings.measure(ResamplingTimings.Stage.DECODE_PIXELS) {
                        checkNotNull(it.decode(region, if (isFilteringEnabled()) rawSample else sampleSize))
                    },
                )
            }
            val scale = scaleFactor.toDouble() / sampleSize
            if (!filter || filteringFailed) {
                return result(
                    decodeInput(region, rawSample).also { bitmap ->
                        try {
                            checkActive()
                        } catch (error: Throwable) {
                            bitmap.recycle()
                            throw error
                        }
                    },
                )
            }
            if (scale == 1.0 && options.softening == 0.0) return result(decodeInput(region, 1))
            try {
                return result(
                    filtered(
                        region,
                        RegionResamplingPlan(scale, evenCrop, minOf(imageWidth, imageHeight), options),
                        ::checkActive,
                    ),
                    true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: OutOfMemoryError) {
                logcat(LogPriority.WARN, error) { "Mitchell tile allocation failed; using original region decoder" }
            } catch (error: Exception) {
                logcat(LogPriority.WARN, error) { "Mitchell tile failed; using original region decoder" }
            }
            filteringFailed = true
            inputCache.clear()
            checkActive()
            return result(decodeInput(region, rawSample))
        } finally {
            scheduler.permits.release()
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
        require(width.toLong() * height <= budget.outputPixels) { "Oversized filtered tile" }
        // Include the filter halo while staying below the 2 Mi pixel input limit.
        val legacyBlocks = options.kernel == ResamplingKernel.MITCHELL &&
            options.quality == ResamplingQuality.BALANCED && budget.inputPixels == 2 * 1024 * 1024
        val blockSize = if (legacyBlocks) {
            minOf(
                budget.blockEdge,
                (1400 * plan.scale * plan.inputSample - 6 * options.softening).toInt().coerceAtLeast(1),
            )
        } else {
            minOf(
                budget.blockEdge,
                (
                    (kotlin.math.sqrt(budget.inputPixels.toDouble()) - 4) * plan.scale * plan.inputSample -
                        2 * plan.outputHalo
                    ).toInt().coerceAtLeast(1),
            )
        }
        // Reuse JPEG input only when the sampled page fits the existing cache budget.
        // Large pages and zoomed regions retain regional decoding instead of scanning unused columns.
        val cacheBands = cachePngBands || (
            cacheJpegBands && plan.scale < 1.0 &&
                (imageWidth / plan.inputSample).toLong() * (imageHeight / plan.inputSample) * 4 <= budget.cacheBytes
            )
        val cachedRows = if (cacheBands && !legacyBlocks) {
            inputCache.reusableRows(imageWidth, plan.inputSample)
        } else {
            0
        }
        val blockHeight = if (cachedRows > 0) {
            minOf(
                blockSize,
                (cachedRows * plan.inputSample * plan.scale - 2 * plan.outputHalo).toInt().coerceAtLeast(1),
            )
        } else {
            blockSize
        }
        var output: Bitmap? = null
        try {
            for (y in 0 until height step blockHeight) {
                for (x in 0 until width step blockSize) {
                    checkActive()
                    val columns = minOf(blockSize, width - x)
                    val rows = minOf(blockHeight, height - y)
                    val inputRegion = Rect(
                        plan.inputStart(left + x),
                        plan.inputStart(top + y),
                        plan.inputEnd(left + x + columns, imageWidth),
                        plan.inputEnd(top + y + rows, imageHeight),
                    )
                    val input = if (cacheBands) {
                        inputCache.decode(
                            inputRegion,
                            plan.inputSample,
                            imageWidth,
                            imageHeight,
                            checkActive,
                            ::decodeInput,
                        )
                    } else {
                        decodeInput(inputRegion, plan.inputSample)
                    }
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
                            timings.measure(ResamplingTimings.Stage.RESIZE) {
                                ImageResampler.resize(
                                    input, target,
                                    ((left + x) / plan.scale - inputRegion.left) / plan.inputSample,
                                    ((top + y) / plan.scale - inputRegion.top) / plan.inputSample,
                                    ((left + x + columns) / plan.scale - inputRegion.left) / plan.inputSample,
                                    ((top + y + rows) / plan.scale - inputRegion.top) / plan.inputSample,
                                    x, y, columns, rows,
                                    options,
                                )
                            },
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
        const val MAX_TILE_SIZE = 1024
        private const val TYPE_HEADER_SIZE = 64 * 1024

        private fun readHeader(input: InputStream): ByteArray {
            val header = ByteArray(TYPE_HEADER_SIZE)
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            return if (offset == header.size) header else header.copyOf(offset)
        }
    }
}
