package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import koharia.testing.FixtureActivityLauncher
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Verifies the comic reader's double tap zoom switch for both image views it can use: the
 * subsampling view that draws still pages and the photo view that draws animated ones.
 *
 * Every wait happens off the main thread, because both views run their zoom animation through the
 * main looper and a blocked main thread would leave the scale untouched in both cases.
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapZoomDeviceTest {

    @Test
    fun staticPageZoomsOnDoubleTapWhenEnabled() = verifyStaticPage(doubleTapZoomEnabled = true) { scale, minScale ->
        assertTrue("Double tap must zoom in when the switch is on, scale=$scale minScale=$minScale", scale > minScale)
    }

    @Test
    fun staticPageIgnoresDoubleTapWhenDisabled() = verifyStaticPage(doubleTapZoomEnabled = false) { scale, minScale ->
        assertEquals("Double tap must not zoom when the switch is off", minScale, scale, 0.001f)
    }

    @Test
    fun animatedPageZoomsOnDoubleTapWhenEnabled() = verifyAnimatedPage(doubleTapZoomEnabled = true) { scale ->
        assertTrue("Double tap must zoom in when the switch is on, scale=$scale", scale > 1.5f)
    }

    @Test
    fun animatedPageIgnoresDoubleTapWhenDisabled() = verifyAnimatedPage(doubleTapZoomEnabled = false) { scale ->
        assertEquals("Double tap must not zoom when the switch is off", 1f, scale, 0.001f)
    }

    private fun verifyStaticPage(doubleTapZoomEnabled: Boolean, assertScale: (Float, Float) -> Unit) {
        val image = Bitmap.createBitmap(1_000, 500, Bitmap.Config.ARGB_8888)
        FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            val reader = showImage(scenario) { activity, view, loaded ->
                view.onImageLoaded = { loaded.countDown() }
                view.setImage(
                    BitmapDrawable(activity.resources, image),
                    ReaderPageImageView.Config(0, doubleTapZoomEnabled = doubleTapZoomEnabled),
                )
            }
            try {
                var scaleView: SubsamplingScaleImageView? = null
                scenario.onActivity {
                    scaleView = reader.getChildAt(0) as SubsamplingScaleImageView
                    dispatchDoubleTap(reader)
                }
                val scale = awaitSettledScale(scenario) { checkNotNull(scaleView).scale }
                var minScale = 1f
                scenario.onActivity { minScale = checkNotNull(scaleView).minScale }
                assertScale(scale, minScale)
            } finally {
                scenario.onActivity { reader.recycle() }
                image.recycle()
            }
        }
    }

    private fun verifyAnimatedPage(doubleTapZoomEnabled: Boolean, assertScale: (Float) -> Unit) {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("reader-animated.gif")
            .use { it.readBytes() }
        FixtureActivityLauncher.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            val reader = showImage(scenario) { _, view, loaded ->
                view.onImageLoaded = { loaded.countDown() }
                view.setImageWithCoil(
                    Buffer().write(bytes),
                    ReaderPageImageView.Config(0, doubleTapZoomEnabled = doubleTapZoomEnabled),
                )
            }
            try {
                var photoView: PhotoView? = null
                scenario.onActivity {
                    photoView = reader.getChildAt(0) as PhotoView
                    dispatchDoubleTap(reader)
                }
                assertScale(awaitSettledScale(scenario) { checkNotNull(photoView).scale })
            } finally {
                scenario.onActivity { reader.recycle() }
            }
        }
    }

    private fun showImage(
        scenario: ActivityScenario<EInkMotionFixtureActivity>,
        load: (android.app.Activity, ReaderPageImageView, CountDownLatch) -> Unit,
    ): ReaderPageImageView {
        val loaded = CountDownLatch(1)
        lateinit var reader: ReaderPageImageView
        scenario.onActivity { activity ->
            reader = ReaderPageImageView(activity)
            activity.setContentView(reader)
            load(activity, reader, loaded)
        }
        assertTrue("Image did not load", loaded.await(10, TimeUnit.SECONDS))
        // Let the first layout settle so the gesture is dispatched against real bounds.
        SystemClock.sleep(300)
        return reader
    }

    /** Reads the scale until it stops moving, so a running zoom animation is never asserted early. */
    private fun awaitSettledScale(scenario: ActivityScenario<*>, scale: () -> Float): Float {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var current = 0f
        var previous = -1f
        var stableSince = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            scenario.onActivity { current = scale() }
            if (current != previous) {
                previous = current
                stableSince = SystemClock.uptimeMillis()
            } else if (SystemClock.uptimeMillis() - stableSince >= 400) {
                return current
            }
        }
        return current
    }

    /** Two taps inside the double tap timeout, at the center of the image. */
    private fun dispatchDoubleTap(target: View) {
        val downTime = SystemClock.uptimeMillis()
        val taps = listOf(
            downTime to MotionEvent.ACTION_DOWN,
            downTime + 20L to MotionEvent.ACTION_UP,
            downTime + 80L to MotionEvent.ACTION_DOWN,
            downTime + 100L to MotionEvent.ACTION_UP,
        )
        taps.forEach { (eventTime, action) ->
            val event = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                target.width / 2f,
                target.height / 2f,
                0,
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            target.dispatchTouchEvent(event)
            event.recycle()
        }
    }
}
