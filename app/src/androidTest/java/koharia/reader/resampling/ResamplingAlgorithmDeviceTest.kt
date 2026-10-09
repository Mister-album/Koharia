package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.provider.OpenStreamProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.function.BooleanSupplier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class ResamplingAlgorithmDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "app.koharia.dev.devicefixture")
        check(ImageResampler.available)
    }

    @Test
    fun catmullRomPreservesMoreEdgeContrastAndSofteningSuppressesAliasing() {
        context
        val edge = Bitmap.createBitmap(1024, 64, Bitmap.Config.ARGB_8888)
        val wave = Bitmap.createBitmap(1024, 64, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 64) {
                for (x in 0 until 1024) {
                    val step = if (x < 509) 64 else 192
                    val value = (128 + 100 * cos(2 * PI * .32 * x)).roundToInt()
                    edge.setPixel(x, y, Color.rgb(step, step, step))
                    wave.setPixel(x, y, Color.rgb(value, value, value))
                }
            }
            fun response(input: Bitmap, options: ResamplingOptions): List<Int> {
                val output = Bitmap.createBitmap(512, 32, Bitmap.Config.ARGB_8888)
                try {
                    assertTrue(ImageResampler.resize(input, output, 0.0, 0.0, 1024.0, 64.0, 0, 0, 512, 32, options))
                    return (16 until 496).map { Color.red(output.getPixel(it, 16)) }
                } finally {
                    output.recycle()
                }
            }
            val mitchell = response(edge, ResamplingOptions())
            val catmull = response(edge, ResamplingOptions(ResamplingKernel.CATMULL_ROM))
            val softened = response(edge, ResamplingOptions(ResamplingKernel.CATMULL_ROM, .5))
            assertTrue("Catmull-Rom should retain stronger edge response", catmull.max() > mitchell.max())
            assertTrue("Softening should reduce ringing", softened.max() < catmull.max())
            fun deviation(values: List<Int>): Double {
                val mean = values.average()
                return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            }
            val amplitudes = listOf(0.0, .25, .5, .75, 1.0).map {
                deviation(response(wave, ResamplingOptions(ResamplingKernel.CATMULL_ROM, it)))
            }
            assertTrue(
                "Aliasing response must decrease with softness: $amplitudes",
                amplitudes.zipWithNext().all { (a, b) -> b <= a + .1 },
            )
            assertTrue(
                "Medium softness should reduce this alias pattern: $amplitudes",
                amplitudes[2] < amplitudes[0] * .5,
            )
        } finally {
            edge.recycle()
            wave.recycle()
        }
    }

    @Test
    fun bothKernelsAndSofteningPreserveConstantPremultipliedColour() {
        context
        val input = Bitmap.createBitmap(127, 93, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.argb(128, 220, 60, 20))
        }
        try {
            for (kernel in ResamplingKernel.entries) {
                for (softness in listOf(0.0, .25, .5, 1.0)) {
                    val output = Bitmap.createBitmap(42, 31, Bitmap.Config.ARGB_8888)
                    try {
                        assertTrue(
                            ImageResampler.resize(
                                input, output, 0.0, 0.0, 127.0, 93.0,
                                0, 0, 42, 31, ResamplingOptions(kernel, softness),
                            ),
                        )
                        val expected = input.getPixel(0, 0)
                        for (y in 0 until 31) {
                            for (x in 0 until 42) {
                                val actual = output.getPixel(x, y)
                                for (shift in listOf(0, 8, 16, 24)) {
                                    val delta = ((actual ushr shift) and 255) - ((expected ushr shift) and 255)
                                    assertTrue(abs(delta) <= 2)
                                }
                            }
                        }
                    } finally {
                        output.recycle()
                    }
                }
            }
        } finally {
            input.recycle()
        }
    }

    @Test
    fun selectableKernelsAndQualityMatchAcrossDownscaleUpscaleAndOddTiles() {
        val source = Bitmap.createBitmap(83, 61, Bitmap.Config.ARGB_8888)
        for (y in 0 until 61) {
            for (x in 0 until 83) {
                source.setPixel(x, y, Color.argb(if (x % 13 == 0) 0 else 255, x * 31 % 256, y * 47 % 256, 97))
            }
        }
        try {
            for (format in listOf(
                Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.WEBP_LOSSLESS,
            )) {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(source.compress(format, 95, output))
                    output.toByteArray()
                }
                for (kernel in ResamplingKernel.entries) {
                    for (quality in ResamplingQuality.entries) {
                        val options = ResamplingOptions(kernel = kernel, quality = quality)
                        val decoder = ResamplingRegionDecoder(false, byteArrayOf(), options)
                        decoder.init(context, OpenStreamProvider(bytes.inputStream()))
                        try {
                            for (scale in listOf(.18f, .42f, .75f, 1.024f, 2f, 4f, 10f)) {
                                assertTrue(decoder.shouldFilter(scale))
                                val plan = RegionResamplingPlan(scale.toDouble(), options = options)
                                val whole = decoder.decodeResult(
                                    Rect(0, 0, 83, 61),
                                    1,
                                    scale,
                                    true,
                                    BooleanSupplier {
                                        false
                                    },
                                )
                                assertTrue("$format/$options/$scale fell back", whole.filtered)
                                try {
                                    for ((left, right) in listOf(0, 23, 51, 83).zipWithNext()) {
                                        for ((top, bottom) in listOf(0, 17, 39, 61).zipWithNext()) {
                                            compareTile(
                                                decoder,
                                                plan,
                                                scale,
                                                whole.bitmap,
                                                Rect(left, top, right, bottom),
                                            )
                                        }
                                    }
                                } finally {
                                    whole.bitmap.recycle()
                                }
                            }
                            assertTrue(!decoder.shouldFilter(1f))
                            val raw = decoder.decodeResult(Rect(0, 0, 83, 61), 1, 1f, false, BooleanSupplier { false })
                            assertTrue(!raw.filtered)
                            raw.bitmap.recycle()
                        } finally {
                            decoder.recycle()
                        }
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun expandedHaloMatchesWholeImageAcrossOddTilesAndInternalBlocks() {
        val source = Bitmap.createBitmap(1051, 739, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val pixel = Color.argb(if (x % 17 < 4) 0 else 255, (x * 37) % 256, (y * 53) % 256, ((x + y) * 19) % 256)
                source.setPixel(x, y, pixel)
            }
        }
        try {
            val formats = listOf(
                Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.WEBP_LOSSLESS,
            )
            for (format in formats) {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(source.compress(format, 95, output))
                    output.toByteArray()
                }
                val candidates = listOf(
                    ResamplingOptions(ResamplingKernel.CATMULL_ROM),
                    ResamplingOptions(ResamplingKernel.CATMULL_ROM, 1.0),
                    ResamplingOptions(softening = .5),
                )
                for (options in candidates) {
                    val decoder = ResamplingRegionDecoder(false, byteArrayOf(), options)
                    decoder.init(context, OpenStreamProvider(bytes.inputStream()))
                    try {
                        for (scale in listOf(.17830609f, .42265147f, .75f)) {
                            val plan = RegionResamplingPlan(scale.toDouble(), options = options)
                            val whole = decoder.decodeResult(
                                Rect(0, 0, 1051, 739),
                                4,
                                scale * 4,
                                true,
                                BooleanSupplier { false },
                            )
                            assertTrue("Whole image unexpectedly fell back", whole.filtered)
                            try {
                                for ((left, right) in listOf(0, 333, 701, 1051).zipWithNext()) {
                                    for ((top, bottom) in listOf(0, 217, 511, 739).zipWithNext()) {
                                        val region = Rect(left, top, right, bottom)
                                        compareTile(decoder, plan, scale, whole.bitmap, region)
                                    }
                                }
                            } finally {
                                whole.bitmap.recycle()
                            }
                        }
                    } finally {
                        decoder.recycle()
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }
    private fun compareTile(
        decoder: ResamplingRegionDecoder,
        plan: RegionResamplingPlan,
        scale: Float,
        whole: Bitmap,
        region: Rect,
    ) {
        val tile = decoder.decodeResult(region, 4, scale * 4, true, BooleanSupplier { false })
        try {
            assertTrue("Tile unexpectedly fell back", tile.filtered)
            val tx = plan.outputBoundary(region.left)
            val ty = plan.outputBoundary(region.top)
            val width = tile.bitmap.width
            val height = tile.bitmap.height
            assertEquals(plan.outputBoundary(region.right) - tx, width)
            val a = IntArray(width * height)
            val b = IntArray(a.size)
            tile.bitmap.getPixels(a, 0, width, 0, 0, width, height)
            whole.getPixels(b, 0, width, tx, ty, width, height)
            for (i in a.indices) {
                for (shift in 0..24 step 8) {
                    fun channel(c: Int): Int = if (shift == 24) {
                        Color.alpha(c)
                    } else {
                        ((c ushr shift) and 255) * Color.alpha(c) / 255
                    }
                    if (abs(channel(a[i]) - channel(b[i])) > 2) {
                        throw AssertionError(
                            "$plan/$region pixel=$i channel=$shift: ${channel(a[i])} vs ${channel(b[i])}",
                        )
                    }
                }
            }
        } finally {
            tile.bitmap.recycle()
        }
    }
}
