package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Opt-in evidence capture; fixtures are supplied only to the isolated device-test package. */
@RunWith(AndroidJUnit4::class)
class ComicResamplingEvidenceDeviceTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(EInkMotionFixtureActivity::class.java)

    @Test
    fun captureDecodeAndDisplayPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val root = File(context.getExternalFilesDir(null), "issues-96-98-99/resampling")
        val inputs = File(root, "inputs").listFiles { file -> file.extension == "png" }.orEmpty()
        org.junit.Assume.assumeTrue("Supply resampling fixtures to opt in", inputs.isNotEmpty())
        val output = File(root, "android").apply { mkdirs() }
        val metrics = StringBuilder("image,percent,sample,decode_ms\n")
        inputs.sortedBy { it.name }.forEach { file ->
            val decoder = checkNotNull(ImageDecoder.newInstance(file.inputStream()))
            try {
                for (percent in listOf(25, 33, 50, 75)) {
                    val width = (decoder.width * percent / 100.0).roundToInt()
                    val height = (decoder.height * percent / 100.0).roundToInt()
                    var sample = 1
                    while (sample * 2 <= 100.0 / percent) sample *= 2
                    for ((label, sampling) in listOf("sampled" to sample, "higher" to maxOf(1, sample / 2))) {
                        var decoded: Bitmap? = null
                        repeat(6) { trial ->
                            decoded?.recycle()
                            val start = SystemClock.elapsedRealtimeNanos()
                            decoded = checkNotNull(decoder.decode(sampleSize = sampling))
                            val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                            if (trial > 0) metrics.append("${file.nameWithoutExtension},$percent,$sampling,$ms\n")
                        }
                        val bitmap = checkNotNull(decoded)
                        writePpm(bitmap, File(output, "${file.nameWithoutExtension}-$percent-$label.ppm"))
                        if (label == "sampled") {
                            val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            Canvas(
                                scaled,
                            ).drawBitmap(bitmap, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
                            File(output, "${file.nameWithoutExtension}-$percent-canvas.png").outputStream().use {
                                scaled.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            scaled.recycle()
                        }
                        bitmap.recycle()
                    }
                    if (file.nameWithoutExtension != "long") captureView(file, width, height, output, percent)
                }
            } finally {
                decoder.recycle()
            }
        }
        File(output, "decode.csv").writeText(metrics.toString())
    }

    private fun captureView(file: File, width: Int, height: Int, output: File, percent: Int) {
        val loaded = CountDownLatch(1)
        lateinit var view: ReaderPageImageView
        activityRule.scenario.onActivity { activity ->
            view = ReaderPageImageView(activity)
            val frame = FrameLayout(activity)
            frame.addView(view, FrameLayout.LayoutParams(width, height))
            activity.setContentView(frame)
            view.onImageLoaded = { loaded.countDown() }
            view.setImage(file.source().buffer(), false, ReaderPageImageView.Config(0))
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        SystemClock.sleep(400)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = -1
        activityRule.scenario.onActivity { activity ->
            val position = IntArray(2)
            view.getLocationInWindow(position)
            PixelCopy.request(
                activity.window,
                Rect(
                    position[0],
                    position[1],
                    position[0] + width,
                    position[1] + height,
                ),
                bitmap,
                {
                    result = it
                    copied.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
        }
        assertTrue(copied.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        File(output, "${file.nameWithoutExtension}-$percent-display.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        activityRule.scenario.onActivity { view.recycle() }
    }

    private fun writePpm(bitmap: Bitmap, file: File) {
        val pixels = IntArray(bitmap.width)
        val row = ByteArray(bitmap.width * 3)
        file.outputStream().buffered().use { stream ->
            stream.write("P6\n${bitmap.width} ${bitmap.height}\n255\n".toByteArray())
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                pixels.forEachIndexed { x, color ->
                    row[x * 3] = (color shr 16).toByte()
                    row[x * 3 + 1] = (color shr 8).toByte()
                    row[x * 3 + 2] = color.toByte()
                }
                stream.write(row)
            }
        }
    }
}
