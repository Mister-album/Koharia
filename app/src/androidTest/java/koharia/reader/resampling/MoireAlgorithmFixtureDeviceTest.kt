package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.provider.OpenStreamProvider
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.function.BooleanSupplier

/** Opt-in comparison using external media; all crops retain the full page's sampling phase. */
@RunWith(AndroidJUnit4::class)
class MoireAlgorithmFixtureDeviceTest {
    @Test
    fun captureKernelAndSofteningComparisons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val argument = InstrumentationRegistry.getArguments().getString("moireFixtureDir")
        assumeNotNull(argument)
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val dir = File(root, checkNotNull(argument)).canonicalFile
        require(dir.toPath().startsWith(root.toPath()) && dir != root)
        assertTrue(ImageResampler.available)
        val output = File(dir, "algorithms-${System.currentTimeMillis()}").apply { check(mkdir()) }
        val metrics = StringBuilder("file,display_width,kernel,softening,milliseconds,output_width,output_height\n")
        val options = listOf(
            ResamplingOptions(),
            ResamplingOptions(ResamplingKernel.CATMULL_ROM),
            ResamplingOptions(ResamplingKernel.CATMULL_ROM, .25),
            ResamplingOptions(ResamplingKernel.CATMULL_ROM, .5),
            ResamplingOptions(ResamplingKernel.CATMULL_ROM, .75),
            ResamplingOptions(softening = .5),
        )
        try {
            for (name in listOf("005", "006", "007")) {
                val input = File(dir, "$name.png")
                require(input.isFile)
                for (width in listOf(1080, 2560)) {
                    for (option in options) {
                        val decoder = ResamplingRegionDecoder(false, byteArrayOf(), option)
                        try {
                            val size = decoder.init(context, OpenStreamProvider(input.inputStream()))
                            val scale = width.toFloat() / size.x
                            val region = if (name ==
                                "007"
                            ) {
                                Rect(3200, 6144, 5248, 8192)
                            } else {
                                Rect(128, 6144, 2176, 8192)
                            }
                            val started = SystemClock.elapsedRealtimeNanos()
                            val result = decoder.decodeResult(region, 4, scale * 4, true, BooleanSupplier { false })
                            val duration = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                            try {
                                assertTrue("Candidate fell back: $name/$width/$option", result.filtered)
                                val label = "$name-$width-${option.kernel.name}-${option.softening}"
                                File(output, "$label.png").outputStream().use {
                                    check(result.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                                metrics.append(
                                    "$name,$width,${option.kernel},${option.softening},$duration,${result.bitmap.width},${result.bitmap.height}\n",
                                )
                            } finally {
                                result.bitmap.recycle()
                            }
                        } finally {
                            decoder.recycle()
                        }
                    }
                }
            }
        } finally {
            File(output, "comparisons.csv").writeText(metrics.toString())
        }
    }
}
