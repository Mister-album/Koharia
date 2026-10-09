package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.provider.OpenStreamProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class ResamplingRegionDecoderDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "app.koharia.dev.devicefixture")
        check(ImageResampler.available)
    }

    private fun source(width: Int, height: Int, alpha: Boolean = false): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height) { index ->
                val x = index % width
                val y = index / width
                val value = if ((x % 7 - 3) * (x % 7 - 3) + (y % 7 - 3) * (y % 7 - 3) < 5) 0 else 255
                Color.argb(if (alpha && x % 17 < 4) 0 else 255, value, value, value)
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun encoded(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(format, 95, output))
            output.toByteArray()
        }

    private fun decoder(bytes: ByteArray) = ResamplingRegionDecoder(false, byteArrayOf()).apply {
        init(context, OpenStreamProvider(ByteArrayInputStream(bytes)))
    }

    @Test
    fun jpegBandReuseMatchesRegionalDecodeAndPreservesFallbackBudgets() {
        val original = source(1051, 739)
        val bytes = encoded(original, Bitmap.CompressFormat.JPEG)
        original.recycle()
        for (kernel in ResamplingKernel.entries.filter { it != ResamplingKernel.MITCHELL }) {
            for (scale in listOf(.18f, .42f, .75f, 1.024f, 4f)) {
                val options = ResamplingOptions(kernel)
                val reference = ResamplingRegionDecoder(false, byteArrayOf(), options = options)
                val cached = ResamplingRegionDecoder(
                    false,
                    byteArrayOf(),
                    options = options,
                    budget = ResamplingBudget(reuseJpegBands = true),
                )
                val bounded = ResamplingRegionDecoder(
                    false,
                    byteArrayOf(),
                    options = options,
                    budget = ResamplingBudget(cacheBytes = 1024, reuseJpegBands = true),
                )
                try {
                    for (decoder in listOf(reference, cached, bounded)) {
                        decoder.init(context, OpenStreamProvider(bytes.inputStream()))
                    }
                    for (region in listOf(Rect(0, 0, 127, 111), Rect(121, 97, 301, 253), Rect(911, 601, 1051, 739))) {
                        val expected = reference.decodeResult(region, 1, scale, true, BooleanSupplier { false })
                        try {
                            for (decoder in listOf(cached, bounded)) {
                                val actual = decoder.decodeResult(region, 1, scale, true, BooleanSupplier { false })
                                try {
                                    assertTrue(actual.filtered)
                                    assertEquals(expected.bitmap.width, actual.bitmap.width)
                                    assertEquals(expected.bitmap.height, actual.bitmap.height)
                                    assertPixels(actual.bitmap, expected.bitmap, 0, 0, "$kernel/$scale/cache")
                                } finally {
                                    actual.bitmap.recycle()
                                }
                            }
                        } finally {
                            expected.bitmap.recycle()
                        }
                    }
                    if (scale < 1f) assertTrue(cached.nativeDecodeCount.get() < reference.nativeDecodeCount.get())
                    assertEquals(reference.nativeDecodeCount.get(), bounded.nativeDecodeCount.get())
                } finally {
                    reference.recycle()
                    cached.recycle()
                    bounded.recycle()
                }
            }
        }
    }

    @Test
    fun bypassUsesNativePixelsInsteadOfMitchellAtEveryPyramidCalibration() {
        val original = source(512, 512)
        val bytes = encoded(original)
        val decoder = decoder(bytes)
        val native = checkNotNull(ImageDecoder.newInstance(bytes.inputStream(), false, byteArrayOf()))
        try {
            assertTrue(decoder.shouldFilter(.5f))
            assertTrue(decoder.shouldFilter(.51f))
            for ((sample, factor, nativeSample) in listOf(Triple(2, 1.6f, 1), Triple(4, 1.6f, 2))) {
                val output = decoder.decodeRegion(
                    Rect(0, 0, 512, 512),
                    sample,
                    factor,
                    false,
                    BooleanSupplier {
                        false
                    },
                )
                val reference = checkNotNull(native.decode(Rect(0, 0, 512, 512), nativeSample))
                try {
                    assertEquals(reference.width, output.width)
                    assertEquals(reference.height, output.height)
                    assertPixels(output, reference, 0, 0, "explicit bypass")
                } finally {
                    output.recycle()
                    reference.recycle()
                }
            }
        } finally {
            original.recycle()
            decoder.recycle()
            native.recycle()
        }
    }

    @Test
    fun transparentColoursRemainPremultipliedAndNativeSizeIsUnfiltered() {
        val original = Bitmap.createBitmap(127, 93, Bitmap.Config.ARGB_8888)
        original.eraseColor(Color.argb(128, 220, 60, 20))
        val decoder = decoder(encoded(original))
        try {
            for (sample in listOf(1, 2, 4)) {
                val output = decoder.decodeRegion(Rect(0, 0, 127, 93), sample)
                try {
                    val pixel = output.getPixel(output.width / 2, output.height / 2)
                    val expected = original.getPixel(0, 0)
                    for (shift in listOf(0, 8, 16, 24)) {
                        assertTrue(abs(((pixel ushr shift) and 255) - ((expected ushr shift) and 255)) <= 2)
                    }
                    if (sample == 1) assertPixels(output, original, 0, 0, "native resolution")
                } finally {
                    output.recycle()
                }
            }
        } finally {
            original.recycle()
            decoder.recycle()
        }
    }

    @Test
    fun repeatedConcurrentLongStripRegionsReleaseNativeAllocations() {
        val original = source(513, 4097)
        val decoder = decoder(encoded(original))
        original.recycle()
        val executor = Executors.newFixedThreadPool(4)
        try {
            fun decode() = decoder.decodeRegion(Rect(0, 3071, 513, 4097), 4).also {
                assertEquals(128, it.width)
                assertEquals(256, it.height)
                it.recycle()
            }
            repeat(8) { decode() }
            val before = Debug.getNativeHeapAllocatedSize()
            val measurements = ConcurrentLinkedQueue<Long>()
            val jobs = (0 until 40).map {
                executor.submit {
                    val start = SystemClock.elapsedRealtimeNanos()
                    decode()
                    measurements += SystemClock.elapsedRealtimeNanos() - start
                }
            }
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            val growth = Debug.getNativeHeapAllocatedSize() - before
            val output = File(context.getExternalFilesDir(null), "moire-integration").apply { mkdirs() }
            File(output, "region-performance.csv").writeText(
                "milliseconds\n" + measurements.joinToString("\n") { (it / 1_000_000.0).toString() } + "\n",
            )
            File(output, "native-growth.txt").writeText("$growth bytes after 40 regions (8 warmups)\n")
            assertTrue("Native growth after repeated regions: $growth bytes", growth < 8 * 1024 * 1024)
        } finally {
            executor.shutdownNow()
            decoder.recycle()
        }
    }

    @Test
    fun rectangularInputWithMatchingAxisScaleDoesNotReuseDifferentEdgeBounds() {
        check(ImageResampler.available)
        val input = source(268, 262)
        val square = Bitmap.createBitmap(268, 268, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val expected = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 268) {
                val row = IntArray(268)
                input.getPixels(row, 0, 268, 0, minOf(y, 261), 268, 1)
                square.setPixels(row, 0, 268, 0, y, 268, 1)
            }
            assertTrue(ImageResampler.resize(input, actual, 6.0, 6.0, 262.0, 262.0, 0, 0, 128, 128))
            assertTrue(ImageResampler.resize(square, expected, 6.0, 6.0, 262.0, 262.0, 0, 0, 128, 128))
            assertPixels(actual, expected, 0, 0, "rectangular edge clamp")
        } finally {
            input.recycle()
            square.recycle()
            actual.recycle()
            expected.recycle()
        }
    }

    @Test
    fun oddTilesAndInternalBlocksMatchWholeImageSamplingPhaseForPngJpegAndWebp() {
        val original = source(1051, 739, alpha = true)
        try {
            val formats = listOf(
                Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.WEBP_LOSSLESS,
            )
            for (format in formats) {
                val bytes = encoded(original, format)
                val decoder = decoder(bytes)
                try {
                    for (scale in listOf(.25f, .33f, .5f, .75f)) {
                        val plan = RegionResamplingPlan(scale.toDouble())
                        val whole = decoder.decodeRegion(Rect(0, 0, 1051, 739), 4, scale * 4, BooleanSupplier { false })
                        try {
                            val xs = listOf(0, 333, 701, 1051)
                            val ys = listOf(0, 217, 511, 739)
                            for ((left, right) in xs.zipWithNext()) {
                                for ((top, bottom) in ys.zipWithNext()) {
                                    val tile = decoder.decodeRegion(
                                        Rect(left, top, right, bottom),
                                        4,
                                        scale * 4,
                                        BooleanSupplier { false },
                                    )
                                    try {
                                        val tileX = plan.outputBoundary(left)
                                        val tileY = plan.outputBoundary(top)
                                        assertEquals(plan.outputBoundary(right) - tileX, tile.width)
                                        assertEquals(plan.outputBoundary(bottom) - tileY, tile.height)
                                        assertPixels(tile, whole, tileX, tileY, "$format/$scale")
                                    } finally {
                                        tile.recycle()
                                    }
                                }
                            }
                        } finally {
                            whole.recycle()
                        }
                    }
                } finally {
                    decoder.recycle()
                }
            }
        } finally {
            original.recycle()
        }
    }

    @Test
    fun quarterScaleReducesDotInterferenceAndNativeOutputMatchesIndependentFullInputResize() {
        val original = source(1024, 1024)
        val bytes = encoded(original)
        original.recycle()
        val filteredDecoder = decoder(bytes)
        val raw = checkNotNull(ImageDecoder.newInstance(ByteArrayInputStream(bytes)))
        try {
            val filtered = filteredDecoder.decodeRegion(Rect(0, 0, 1024, 1024), 4)
            val baseline = checkNotNull(raw.decode(sampleSize = 4))
            val higher = checkNotNull(raw.decode(sampleSize = 2))
            val reference = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            try {
                assertTrue(ImageResampler.resize(higher, reference, 0.0, 0.0, 512.0, 512.0, 0, 0, 256, 256))
                var difference = 0
                for (y in 0 until 256) {
                    for (x in 0 until 256) {
                        val delta = abs(Color.red(filtered.getPixel(x, y)) - Color.red(reference.getPixel(x, y)))
                        difference = maxOf(difference, delta)
                    }
                }
                assertTrue("block/reference difference=$difference", difference <= 1)
                val before = interference(baseline)
                val after = interference(filtered)
                assertTrue("before=$before after=$after", after < before * .5)
            } finally {
                filtered.recycle()
                baseline.recycle()
                higher.recycle()
                reference.recycle()
            }
        } finally {
            filteredDecoder.recycle()
            raw.recycle()
        }
    }

    @Test
    fun cancellationStopsBetweenBlocksWithoutPublishingPartialPixelsAndDecoderCanBeReused() {
        val original = source(2048, 2048)
        val decoder = decoder(encoded(original))
        original.recycle()
        try {
            var checks = 0
            var cancelled = false
            try {
                decoder.decodeRegion(Rect(0, 0, 2048, 2048), 4, 1f, BooleanSupplier { ++checks > 6 })
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            decoder.decodeRegion(Rect(0, 0, 128, 128), 1).also {
                assertEquals(128, it.width)
                it.recycle()
            }
        } finally {
            decoder.recycle()
        }
        assertTrue(!decoder.isReady())
    }

    @Test
    fun allocationGuardReportsRawFallbackAndDisablesFilteringForThisPage() {
        val original = source(1300, 1300)
        val decoder = decoder(encoded(original))
        original.recycle()
        try {
            val result = decoder.decodeResult(Rect(0, 0, 1300, 1300), 1, .9f, true, BooleanSupplier { false })
            try {
                assertTrue(!result.filtered)
                assertEquals(1300, result.bitmap.width)
                assertTrue(!decoder.shouldFilter(.25f))
                assertTrue(decoder.isFilteringEnabled)
            } finally {
                result.bitmap.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun interference(bitmap: Bitmap): Double {
        val blocks = mutableListOf<Double>()
        for (y in 0 until bitmap.height - 7 step 8) {
            for (x in 0 until bitmap.width - 7 step 8) {
                var sum = 0.0
                for (dy in 0..7) for (dx in 0..7) sum += Color.red(bitmap.getPixel(x + dx, y + dy))
                blocks += sum / 64
            }
        }
        val mean = blocks.average()
        return sqrt(blocks.sumOf { (it - mean) * (it - mean) } / blocks.size)
    }

    private fun assertPixels(tile: Bitmap, whole: Bitmap, left: Int, top: Int, label: String) {
        for (y in 0 until tile.height) {
            for (x in 0 until tile.width) {
                val a = tile.getPixel(x, y)
                val b = whole.getPixel(x + left, y + top)
                for (shift in listOf(0, 8, 16, 24)) {
                    fun channel(color: Int) = if (shift == 24) {
                        Color.alpha(color)
                    } else {
                        ((color ushr shift) and 255) * Color.alpha(color) / 255
                    }
                    val delta = abs(channel(a) - channel(b))
                    if (delta >
                        2
                    ) {
                        throw AssertionError("$label tile=$left,$top pixel=$x,$y channel=$shift delta=$delta")
                    }
                }
            }
        }
    }
}
