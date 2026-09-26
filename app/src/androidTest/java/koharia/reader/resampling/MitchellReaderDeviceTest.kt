package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageRotation
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.provider.OpenStreamProvider
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier
import kotlin.math.abs
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class MitchellReaderDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(EInkMotionFixtureActivity::class.java)

    @Test
    fun realReaderReducesQuarterScaleInterferenceAndCapturesOtherFitScales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        assertTrue(MitchellResampler.available)
        val preferences = Injekt.get<ReaderPreferences>()
        val old = preferences.moireReduction.get()
        val wasSet = preferences.moireReduction.isSet()
        val oldThreshold = preferences.moireReductionThreshold.get()
        val thresholdWasSet = preferences.moireReductionThreshold.isSet()
        val input = File.createTempFile("moire-", ".png", context.cacheDir)
        val output = File(context.getExternalFilesDir(null), "moire-integration").apply { mkdirs() }
        val original = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(1024 * 1024) { index ->
            val x = index % 1024 % 7 - 3
            val y = index / 1024 % 7 - 3
            if (x * x + y * y < 5) Color.BLACK else Color.WHITE
        }
        original.setPixels(pixels, 0, 1024, 0, 0, 1024, 1024)
        input.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
        original.recycle()
        val results = StringBuilder("percent,enabled,ready_ms,capture_ms,interference\n")
        try {
            preferences.moireReductionThreshold.set(100)
            for (percent in listOf(25, 33, 50, 75)) {
                val measures = mutableListOf<Double>()
                for (enabled in listOf(false, true)) {
                    preferences.moireReduction.set(enabled)
                    val start = SystemClock.elapsedRealtime()
                    val (bitmap, readyMs) = capture(input, (1024 * percent / 100.0).toInt())
                    try {
                        val metric = interference(bitmap)
                        measures += metric
                        results.append("$percent,$enabled,$readyMs,${SystemClock.elapsedRealtime() - start},$metric\n")
                        File(output, "dots-$percent-$enabled.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        if (enabled) assertMatchesWholeRegion(input, bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (percent == 25) assertTrue("baseline/filtered=$measures", measures[1] < measures[0] * .6)
            }
        } finally {
            File(output, "display.csv").writeText(results.toString())
            if (wasSet) preferences.moireReduction.set(old) else preferences.moireReduction.delete()
            if (thresholdWasSet) {
                preferences.moireReductionThreshold.set(
                    oldThreshold,
                )
            } else {
                preferences.moireReductionThreshold.delete()
            }
            input.delete()
        }
    }

    private fun assertMatchesWholeRegion(input: File, display: Bitmap) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val decoder = MitchellRegionDecoder(false, byteArrayOf())
        decoder.init(context, OpenStreamProvider(input.inputStream()))
        try {
            val scale = display.width / 1024f
            val reference = decoder.decodeRegion(Rect(0, 0, 1024, 1024), 4, scale * 4, BooleanSupplier { false })
            try {
                var maximum = 0
                for (y in 0 until display.height) {
                    for (x in 0 until display.width) {
                        maximum =
                            maxOf(maximum, abs(Color.red(display.getPixel(x, y)) - Color.red(reference.getPixel(x, y))))
                    }
                }
                assertTrue("Displayed tile phase at $scale: max channel difference=$maximum", maximum <= 2)
            } finally {
                reference.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    @Test
    fun replacingPagesThenRotatingAndZoomingDoesNotPublishPreviousImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val preference = Injekt.get<ReaderPreferences>().moireReduction
        val old = preference.get()
        val wasSet = preference.isSet()
        val files = mutableListOf<File>()
        var view: ReaderPageImageView? = null
        val loaded = CountDownLatch(1)
        try {
            preference.set(true)
            for (colour in listOf(Color.RED, Color.GREEN, Color.BLUE)) {
                val bitmap = Bitmap.createBitmap(767, 1025, Bitmap.Config.ARGB_8888)
                val file = File.createTempFile("moire-replace-", ".png", context.cacheDir).also(files::add)
                bitmap.eraseColor(colour)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            activity.scenario.onActivity { host ->
                val image = ReaderPageImageView(host).also { view = it }
                host.setContentView(FrameLayout(host).apply { addView(image, FrameLayout.LayoutParams(512, 512)) })
                image.onImageLoaded = { loaded.countDown() }
                files.forEach { image.setImage(it.source().buffer(), false, ReaderPageImageView.Config(0)) }
            }
            assertTrue(loaded.await(20, TimeUnit.SECONDS))
            activity.scenario.onActivity {
                val ssiv = checkNotNull(view).getChildAt(0) as SubsamplingScaleImageView
                assertEquals(767, ssiv.sWidth)
                assertEquals(1025, ssiv.sHeight)
                ssiv.imageRotation = ImageRotation.ROTATION_90
            }
            val rendered = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var blue = false
                while (!blue && SystemClock.elapsedRealtime() < deadline) {
                    activity.scenario.onActivity {
                        rendered.eraseColor(Color.TRANSPARENT)
                        checkNotNull(view).draw(Canvas(rendered))
                        blue = rendered.getPixel(256, 256) == Color.BLUE
                    }
                    if (!blue) SystemClock.sleep(50)
                }
                assertTrue("Rotated image did not render", blue)
            } finally {
                rendered.recycle()
            }
            activity.scenario.onActivity {
                val image = checkNotNull(view)
                val ssiv = image.getChildAt(0) as SubsamplingScaleImageView
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                try {
                    for (scale in listOf(1f, .5f, .9f, .6f, 1.2f)) {
                        ssiv.setScaleAndCenter(scale, PointF(512f, 380f))
                        image.draw(Canvas(bitmap))
                        assertEquals(Color.BLUE, bitmap.getPixel(256, 256))
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            activity.scenario.onActivity { view?.recycle() }
            if (wasSet) preference.set(old) else preference.delete()
            files.forEach { it.delete() }
        }
    }

    private fun capture(input: File, size: Int): Pair<Bitmap, Long> {
        val start = SystemClock.elapsedRealtime()
        val loaded = CountDownLatch(1)
        lateinit var view: ReaderPageImageView
        activity.scenario.onActivity { host ->
            view = ReaderPageImageView(host)
            val frame = FrameLayout(host)
            frame.addView(view, FrameLayout.LayoutParams(size, size))
            host.setContentView(frame)
            view.onImageLoaded = { loaded.countDown() }
            view.setImage(input.source().buffer(), false, ReaderPageImageView.Config(0))
        }
        try {
            assertTrue("Reader load timed out", loaded.await(20, TimeUnit.SECONDS))
            val readyMs = SystemClock.elapsedRealtime() - start
            SystemClock.sleep(400)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var result = -1
            activity.scenario.onActivity { host ->
                val position = IntArray(2)
                view.getLocationInWindow(position)
                PixelCopy.request(
                    host.window,
                    Rect(position[0], position[1], position[0] + size, position[1] + size),
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
            return bitmap to readyMs
        } finally {
            activity.scenario.onActivity { view.recycle() }
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
}
