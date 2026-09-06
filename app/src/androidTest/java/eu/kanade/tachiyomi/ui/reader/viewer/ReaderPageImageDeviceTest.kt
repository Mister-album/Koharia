package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager.widget.PagerAdapter
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderPageImageDeviceTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(EInkMotionFixtureActivity::class.java)

    @Test
    fun recyclingPageCancelsPendingLandscapeZoom() {
        val loaded = CountDownLatch(1)
        lateinit var reader: ReaderPageImageView
        activityRule.scenario.onActivity { activity ->
            reader = ReaderPageImageView(activity)
            activity.setContentView(reader)
            reader.onImageLoaded = { loaded.countDown() }
            reader.setImage(
                BitmapDrawable(activity.resources, Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)),
                ReaderPageImageView.Config(0, landscapeZoom = true),
            )
        }
        assertTrue("Image did not load", loaded.await(10, TimeUnit.SECONDS))
        activityRule.scenario.onActivity {
            reader.onPageSelected(true)
            reader.recycle()
        }
        SystemClock.sleep(700)
        activityRule.scenario.onActivity {
            assertFalse((reader.getChildAt(0) as SubsamplingScaleImageView).isReady)
        }
    }

    @Test
    fun coilImageSupportsPinchAndPan() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }
        bitmap.recycle()
        assertPinchAndPan(bytes.toByteArray())
    }

    @Test
    fun animatedGifSupportsPinchAndPan() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("reader-animated.gif")
            .use { it.readBytes() }
        assertPinchAndPan(bytes)
    }

    @Test
    fun animatedGifSupportsPinchInsidePager() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("reader-animated.gif")
            .use { it.readBytes() }
        assertPinchAndPan(bytes, inPager = true)
    }

    private fun assertPinchAndPan(bytes: ByteArray, inPager: Boolean = false) {
        val loaded = CountDownLatch(1)
        lateinit var reader: ReaderPageImageView
        lateinit var touchTarget: View
        activityRule.scenario.onActivity { activity ->
            reader = ReaderPageImageView(activity)
            touchTarget = if (inPager) {
                Pager(activity).apply {
                    adapter = object : PagerAdapter() {
                        override fun getCount(): Int = 3
                        override fun isViewFromObject(view: View, item: Any): Boolean = view === item
                        override fun instantiateItem(container: ViewGroup, position: Int): Any {
                            return (if (position == 1) reader else View(activity)).also(container::addView)
                        }
                        override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                            container.removeView(item as View)
                        }
                    }
                    currentItem = 1
                }
            } else {
                reader
            }
            activity.setContentView(touchTarget)
            reader.onImageLoaded = { loaded.countDown() }
            reader.setImageWithCoil(Buffer().write(bytes), ReaderPageImageView.Config(0))
        }
        assertTrue("Image did not load", loaded.await(10, TimeUnit.SECONDS))
        activityRule.scenario.onActivity {
            val photo = reader.getChildAt(0) as PhotoView
            val downTime = SystemClock.uptimeMillis()
            val properties = Array(2) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            for (step in -2..22) {
                val distance = photo.width * (0.12f + 0.28f * step.coerceIn(0, 20) / 20f)
                val points = Array(2) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = photo.width / 2f + if (i == 0) -distance else distance
                        y = photo.height / 2f
                        pressure = 1f
                        size = 1f
                    }
                }
                val action = when (step) {
                    -2 -> MotionEvent.ACTION_DOWN
                    -1 -> MotionEvent.ACTION_POINTER_DOWN or (1 shl 8)
                    21 -> MotionEvent.ACTION_POINTER_UP or (1 shl 8)
                    22 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val event = MotionEvent.obtain(
                    downTime, downTime + (step + 2) * 20, action,
                    if (step == -2 || step == 22) 1 else 2,
                    properties, points, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                touchTarget.dispatchTouchEvent(event)
                event.recycle()
            }
            assertTrue("Pinch scale was ${photo.scale}", photo.scale > 1.5f)
            assertTrue(
                "Zoomed Coil image must expose horizontal pan space",
                reader.canPanLeft() && reader.canPanRight(),
            )
        }
    }
}
