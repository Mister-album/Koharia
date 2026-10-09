package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageExportOptions
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageFormat
import eu.kanade.tachiyomi.ui.reader.setting.MergedPageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MergedPageJpegFixtureDeviceTest {
    // Supply jpegFixtureDir relative to the fixture app's external files, with page8.jpg, page9.jpg and old-merged.png.
    @Test
    fun equalHeightJpegPagesPreservePixelsAndReduceLosslessSize() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val argument = InstrumentationRegistry.getArguments().getString("jpegFixtureDir")
        assumeNotNull(argument)
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val dir = File(root, checkNotNull(argument)).canonicalFile
        require(dir.toPath().startsWith(root.toPath()) && dir != root)
        val outputDir = File(dir, "exports-${System.currentTimeMillis()}").apply { check(mkdir()) }
        val inputs = listOf(File(dir, "page9.jpg"), File(dir, "page8.jpg"))
        val dimensions = inputs.map { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outMimeType == "image/jpeg")
            bounds.outWidth to bounds.outHeight
        }
        assertEquals(dimensions[0].second, dimensions[1].second)
        val width = dimensions.sumOf { it.first }
        val height = dimensions[0].second
        val metrics = StringBuilder("file,bytes,width,height,changed_pixels,mae,max_error,elapsed_ms\n")

        fun compare(file: File, elapsed: Long, exact: Boolean) {
            val merged = checkNotNull(BitmapFactory.decodeFile(file.path))
            var changed = 0L
            var absoluteError = 0L
            var maxError = 0
            try {
                assertEquals(width, merged.width)
                assertEquals(height, merged.height)
                var offset = 0
                for (input in inputs) {
                    val original = checkNotNull(BitmapFactory.decodeFile(input.path))
                    try {
                        val expected = IntArray(original.width)
                        val actual = IntArray(original.width)
                        for (y in 0 until height) {
                            original.getPixels(expected, 0, original.width, 0, y, original.width, 1)
                            merged.getPixels(actual, 0, original.width, offset, y, original.width, 1)
                            for (x in expected.indices) {
                                if (expected[x] != actual[x]) changed++
                                for (shift in 0..16 step 8) {
                                    val error = kotlin.math.abs(
                                        ((expected[x] shr shift) and 255) - ((actual[x] shr shift) and 255),
                                    )
                                    absoluteError += error
                                    maxError = maxOf(maxError, error)
                                }
                            }
                        }
                        offset += original.width
                    } finally {
                        original.recycle()
                    }
                }
                val mae = absoluteError.toDouble() / (width.toLong() * height * 3)
                metrics.append("${file.name},${file.length()},$width,$height,$changed,$mae,$maxError,$elapsed\n")
                if (exact) assertEquals("${file.name} changed source pixels", 0L, changed)
            } finally {
                merged.recycle()
            }
        }

        try {
            compare(File(dir, "old-merged.png"), 0, false)
            for (layout in MergedPageLayout.entries) {
                val outputs = MergedPageFormat.entries.associateWith { format ->
                    val file = File(outputDir, "$layout-$format.image")
                    val started = android.os.SystemClock.elapsedRealtime()
                    val result = MergedPageImage.write(
                        { inputs[0].inputStream() },
                        { inputs[1].inputStream() },
                        file,
                        MergedPageExportOptions(layout, format),
                    )
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    val named = File(outputDir, "$layout-$format.${result.extension}")
                    check(file.renameTo(named))
                    compare(named, elapsed, format != MergedPageFormat.JPEG)
                    named
                }
                assertTrue(
                    outputs.getValue(MergedPageFormat.LOSSLESS_AUTO).length() <=
                        outputs.getValue(MergedPageFormat.PNG).length(),
                )
            }
        } finally {
            File(outputDir, "metrics.csv").writeText(metrics.toString())
        }
    }
}
