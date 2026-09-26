package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageExportOptions
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageFormat
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MergedPageExportDeviceTest {
    @Test
    fun patternsPreservePixelsAndAutomaticEncodingNeverExceedsPng() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val dir = File(context.getExternalFilesDir(null), "issues-96-98-99/export").apply { mkdirs() }
        fun pattern(width: Int, height: Int, name: String): File {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    bitmap.setPixel(x, y, if ((x + y) % 3 == 0) Color.BLACK else Color.WHITE)
                }
            }
            Canvas(bitmap).drawText(
                "Small text / 0123456789",
                8f,
                25f,
                Paint().apply {
                    color = Color.BLUE
                    textSize =
                        16f
                },
            )
            return File(dir, name).also { file ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        val left = pattern(301, 401, "left.png")
        val right = pattern(400, 600, "right.png")
        val original = checkNotNull(BitmapFactory.decodeFile(left.path))
        val metrics = StringBuilder("layout,format,bytes,width,height\n")
        try {
            for (layout in MergedPageLayout.entries) {
                val outputs = MergedPageFormat.entries.associateWith { format ->
                    val file = File(dir, "$layout-$format.image")
                    val result = MergedPageImage.write({
                        left.inputStream()
                    }, { right.inputStream() }, file, MergedPageExportOptions(layout, format))
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
                    metrics.append("$layout,$format,${file.length()},${bitmap.width},${bitmap.height}\n")
                    assertEquals(600, bitmap.height)
                    assertEquals(if (layout == MergedPageLayout.ORIGINAL_PIXELS) 701 else 851, bitmap.width)
                    if (layout == MergedPageLayout.ORIGINAL_PIXELS && format != MergedPageFormat.JPEG) {
                        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
                        for (y in 0 until original.height) {
                            for (x in 0 until original.width) {
                                assertEquals("Pixel $x,$y", original.getPixel(x, y), bitmap.getPixel(x, y + 99))
                            }
                        }
                    }
                    assertEquals(
                        if (format ==
                            MergedPageFormat.JPEG
                        ) {
                            "image/jpeg"
                        } else if (result.extension ==
                            "webp"
                        ) {
                            "image/webp"
                        } else {
                            "image/png"
                        },
                        result.mimeType,
                    )
                    bitmap.recycle()
                    result
                }
                assertTrue(
                    outputs.getValue(MergedPageFormat.LOSSLESS_AUTO).file.length() <=
                        outputs.getValue(MergedPageFormat.PNG).file.length(),
                )
                val png = checkNotNull(BitmapFactory.decodeFile(outputs.getValue(MergedPageFormat.PNG).file.path))
                val auto =
                    checkNotNull(BitmapFactory.decodeFile(outputs.getValue(MergedPageFormat.LOSSLESS_AUTO).file.path))
                for (y in 0 until png.height) {
                    for (x in 0 until png.width) {
                        assertEquals("Lossless pixel $x,$y", png.getPixel(x, y), auto.getPixel(x, y))
                    }
                }
                png.recycle()
                auto.recycle()
            }
        } finally {
            original.recycle()
            File(dir, "metrics.csv").writeText(metrics.toString())
        }
    }

    @Test
    fun transparentPixelsBecomeWhiteInJpegAndEqualHeightPngIsExact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val dir = File(context.cacheDir, "issues-96-98-99-export").apply { mkdirs() }
        val input = File(dir, "transparent.png")
        val output = File(dir, "output.image")
        val bitmap = Bitmap.createBitmap(37, 53, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        bitmap.setPixel(10, 10, Color.BLACK)
        try {
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            MergedPageImage.write(
                { input.inputStream() },
                { input.inputStream() },
                output,
                MergedPageExportOptions(format = MergedPageFormat.PNG),
            )
            checkNotNull(BitmapFactory.decodeFile(output.path)).let { decoded ->
                assertEquals(74, decoded.width)
                assertEquals(Color.BLACK, decoded.getPixel(10, 10))
                assertEquals(Color.TRANSPARENT, decoded.getPixel(0, 0))
                decoded.recycle()
            }
            MergedPageImage.write({
                input.inputStream()
            }, { input.inputStream() }, output, MergedPageExportOptions(format = MergedPageFormat.JPEG))
            checkNotNull(BitmapFactory.decodeFile(output.path)).let { decoded ->
                assertEquals(Color.WHITE, decoded.getPixel(30, 40))
                decoded.recycle()
            }
        } finally {
            bitmap.recycle()
            input.delete()
            output.delete()
            dir.delete()
        }
    }
}
