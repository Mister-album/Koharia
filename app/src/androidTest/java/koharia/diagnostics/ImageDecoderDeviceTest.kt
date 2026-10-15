package koharia.diagnostics

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tachiyomi.decoder.ImageDecoder
import kotlin.math.abs

/** Uses the packaged JNI library, including in minified fixtures (its public API is JNI-kept). */
class ImageDecoderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val assets get() = instrumentation.context.assets

    @Before
    fun requireFixture() {
        check(instrumentation.targetContext.packageName == "app.koharia.dev.devicefixture")
    }

    @Test
    fun allFormatsDecodeColorAndSampledRegionsThroughThePackagedLibrary() {
        val profile = assets.open("image-decoder/srgb.icc").use { it.readBytes() }
        for (extension in listOf("jpg", "png", "webp", "heic", "avif", "jxl")) {
            val name = "rgb.$extension"
            val bytes = assets.open("image-decoder/$name").use { it.readBytes() }
            assertNotNull(name, ImageDecoder.findType(bytes))
            for (displayProfile in listOf(null, profile)) {
                withDecoder(name, displayProfile = displayProfile) { decoder ->
                    assertEquals(name, 64, decoder.width)
                    assertEquals(name, 48, decoder.height)
                    val full = checkNotNull(decoder.decode()) { name }
                    try {
                        assertColor(name, full.getPixel(32, 24), 32, 96, 160)
                        assertColor(name, full.getPixel(1, 1), 255, 255, 255)
                    } finally {
                        full.recycle()
                    }
                    val region = checkNotNull(decoder.decode(Rect(8, 8, 56, 40), sampleSize = 2)) { name }
                    try {
                        assertEquals(name, 24, region.width)
                        assertEquals(name, 16, region.height)
                        assertColor(name, region.getPixel(12, 8), 32, 96, 160)
                    } finally {
                        region.recycle()
                    }
                }
            }
        }
    }

    @Test
    fun borderCroppingRetainsTheContentCoordinates() {
        for (extension in listOf("jpg", "png", "webp", "heic", "avif", "jxl")) {
            withDecoder("rgb.$extension", cropBorders = true) { decoder ->
                assertEquals(extension, 48, decoder.width)
                assertEquals(extension, 32, decoder.height)
                val bitmap = checkNotNull(decoder.decode())
                try {
                    assertColor(extension, bitmap.getPixel(24, 16), 32, 96, 160)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun heifPaddedRowsKeepRegionOffsetsAndCropCoordinates() {
        for (name in listOf("odd.heic", "odd.avif")) {
            withDecoder(name) { decoder ->
                assertEquals(name, 17, decoder.width)
                assertEquals(name, 13, decoder.height)
                val region = checkNotNull(decoder.decode(Rect(4, 4, 13, 9)))
                try {
                    assertColor(name, region.getPixel(4, 2), 32, 96, 160)
                } finally {
                    region.recycle()
                }
            }
            withDecoder(name, cropBorders = true) { decoder ->
                assertEquals(name, 9, decoder.width)
                assertEquals(name, 5, decoder.height)
            }
        }
    }

    @Test
    fun alphaGrayscaleAndCmykInputsKeepTheirColorSemantics() {
        val inputs = listOf("alpha.png", "alpha.webp", "alpha.heic", "alpha.avif", "alpha.jxl", "gray.png", "cmyk.jpg")
        for (name in inputs) {
            withDecoder(name) { decoder ->
                val bitmap = checkNotNull(decoder.decode())
                try {
                    val pixel = bitmap.getPixel(32, 24)
                    if (name.startsWith("alpha")) {
                        assertEquals(name, 128, Color.alpha(pixel))
                        assertTrue(name, bitmap.isPremultiplied)
                        val composited = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
                        try {
                            composited.eraseColor(Color.WHITE)
                            Canvas(composited).drawBitmap(bitmap, 0f, 0f, null)
                            assertColor(name, composited.getPixel(32, 24), 143, 175, 207)
                        } finally {
                            composited.recycle()
                        }
                    }
                    if (name == "gray.png") {
                        assertEquals(Color.red(pixel), Color.green(pixel))
                        assertEquals(Color.green(pixel), Color.blue(pixel))
                    } else if (name == "cmyk.jpg") {
                        // Independent ImageCms oracle with the decoder's USWebCoatedSWOP fallback.
                        assertColor(name, pixel, 54, 102, 133)
                    } else {
                        assertColor(name, pixel, 32, 96, 160)
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun paletteRowsWithNonVectorWidthsDecodeWithoutChangingPixels() {
        for (name in listOf("palette.png", "palette-alpha.png")) {
            withDecoder(name) { decoder ->
                assertEquals(17, decoder.width)
                assertEquals(5, decoder.height)
                val bitmap = checkNotNull(decoder.decode())
                try {
                    for (y in 0 until 5) {
                        for (x in 0 until 17) {
                            val pixel = bitmap.getPixel(x, y)
                            if (name == "palette-alpha.png") {
                                assertEquals(x, Color.alpha(pixel))
                            } else {
                                assertColor(name, pixel, x, (x * 7 + 3) % 256, (x * 13 + 9) % 256, tolerance = 0)
                            }
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun withDecoder(
        name: String,
        cropBorders: Boolean = false,
        displayProfile: ByteArray? = null,
        verify: (ImageDecoder) -> Unit,
    ) {
        val decoder = checkNotNull(
            ImageDecoder.newInstance(assets.open("image-decoder/$name"), cropBorders, displayProfile),
        ) { "Failed to open $name" }
        try {
            verify(decoder)
        } finally {
            decoder.recycle()
            assertTrue(decoder.isRecycled)
        }
    }

    private fun assertColor(name: String, pixel: Int, red: Int, green: Int, blue: Int, tolerance: Int = 4) {
        assertTrue("$name red ${Color.red(pixel)}", abs(Color.red(pixel) - red) <= tolerance)
        assertTrue("$name green ${Color.green(pixel)}", abs(Color.green(pixel) - green) <= tolerance)
        assertTrue("$name blue ${Color.blue(pixel)}", abs(Color.blue(pixel) - blue) <= tolerance)
    }
}
