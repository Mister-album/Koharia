package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResamplingEdgeDeviceTest {
    @Test
    fun triangleIsMonotoneWhileSharpKernelsRetainTheirRinging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        assertTrue(ImageResampler.available)
        val input = Bitmap.createBitmap(64, 8, Bitmap.Config.ARGB_8888)
        for (y in 0 until 8) {
            for (x in 0 until 64) {
                val level = if (x < 32) 64 else 192
                input.setPixel(x, y, Color.rgb(level, level, level))
            }
        }
        val report = StringBuilder("kernel,scale,minimum,maximum,undershoot,overshoot\n")
        try {
            for (kernel in ResamplingKernel.entries) {
                val output = Bitmap.createBitmap(256, 32, Bitmap.Config.ARGB_8888)
                try {
                    assertTrue(
                        ImageResampler.resize(
                            input, output, 0.0, 0.0, 64.0, 8.0, 0, 0, 256, 32,
                            ResamplingOptions(kernel = kernel),
                        ),
                    )
                    val row = (0 until 256).map { Color.red(output.getPixel(it, 16)) }
                    assertEquals(64, row.first())
                    assertEquals(192, row.last())
                    if (kernel == ResamplingKernel.BILINEAR) {
                        assertTrue(row.zipWithNext().all { (left, right) -> left <= right })
                    } else {
                        assertTrue("Expected the selected kernel's negative lobes", row.min() < 64 && row.max() > 192)
                    }
                    report.append("$kernel,4,${row.min()},${row.max()},${64 - row.min()},${row.max() - 192}\n")
                } finally {
                    output.recycle()
                }
            }
        } finally {
            input.recycle()
            val directory = File(context.getExternalFilesDir(null), "resampling-edge-evidence").apply { mkdirs() }
            File(directory, "edge.csv").writeText(report.toString())
        }
    }
}
