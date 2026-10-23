package koharia.reader.resampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ReaderDecoderRetirementDeviceTest {
    @Test
    fun previewDisplaysWhileOriginalDecoderInitializationIsBlocked() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val release = CountDownLatch(1)
        val preview = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        lateinit var view: SubsamplingScaleImageView
        instrumentation.runOnMainSync {
            view = SubsamplingScaleImageView(context)
            view.setMaxTileSize(512)
            view.setRegionDecoderFactory { _, _, _ ->
                object : ImageRegionDecoder {
                    override fun init(context: Context, provider: InputProvider): Point {
                        check(release.await(10, TimeUnit.SECONDS))
                        return Point(360, 300)
                    }
                    override fun isReady() = true
                    override fun decodeRegion(sRect: Rect, sampleSize: Int) =
                        Bitmap.createBitmap(sRect.width(), sRect.height(), Bitmap.Config.ARGB_8888).apply {
                            eraseColor(Color.RED)
                        }
                    override fun recycle() = Unit
                }
            }
            val exact = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact)
            view.layout(0, 0, 400, 400)
            view.setImageWithPreview(ImageSource.inputStream(byteArrayOf().inputStream()), preview, 400, 400)
        }
        fun colour(position: Int = 200): Int {
            var result = 0
            instrumentation.runOnMainSync {
                val frame = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(frame))
                result = frame.getPixel(position, position)
                frame.recycle()
            }
            return result
        }
        try {
            assertEquals("Preview must fill the original image bounds", Color.GREEN, colour(350))
            instrumentation.runOnMainSync {
                assertTrue(view.isReady)
                assertFalse("Preview must not report the full decoder as loaded", view.isImageLoaded)
            }
            release.countDown()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < deadline) {
                val frameColour = colour()
                if (frameColour == Color.RED) break
                assertEquals("Crop geometry update must retain the preview", Color.GREEN, frameColour)
                SystemClock.sleep(20)
            }
            assertEquals(Color.RED, colour())
            assertTrue("Preview bitmap must be released after full base layer arrives", preview.isRecycled)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { view.recycle() }
        }
    }

    @Test
    fun offscreenImageStartsBaseDecodeWhenLayoutArrivesAfterDecoder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val loaded = CountDownLatch(1)
        lateinit var view: SubsamplingScaleImageView
        instrumentation.runOnMainSync {
            view = SubsamplingScaleImageView(context)
            view.setMaxTileSize(512)
            view.setRegionDecoderFactory { _, _, _ ->
                object : ImageRegionDecoder {
                    override fun init(context: Context, provider: InputProvider) = Point(400, 400)
                    override fun isReady() = true
                    override fun decodeRegion(sRect: Rect, sampleSize: Int) =
                        Bitmap.createBitmap(sRect.width(), sRect.height(), Bitmap.Config.ARGB_8888)
                    override fun recycle() = Unit
                }
            }
            view.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onImageLoaded() {
                    loaded.countDown()
                }
            })
            view.setImage(ImageSource.inputStream(byteArrayOf().inputStream()))
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var initialized = false
            while (!initialized && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { initialized = view.sWidth == 400 }
                SystemClock.sleep(20)
            }
            assertTrue("Decoder did not initialize before layout", initialized)
            instrumentation.runOnMainSync {
                val exact = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
                view.measure(exact, exact)
                view.layout(0, 0, 400, 400)
            }
            // No draw call: a clipped offscreen pager child must still prepare its base image.
            assertTrue("Offscreen layout did not start base decoding", loaded.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { view.recycle() }
        }
    }

    @Test
    fun recycleReturnsWhileNativeLikeDecodeIsBlockedAndReleasesExactlyOnceAfterward() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val recycleCount = AtomicInteger()
        val active = AtomicInteger()
        val recycledDuringDecode = AtomicInteger()
        lateinit var view: SubsamplingScaleImageView
        instrumentation.runOnMainSync {
            view = SubsamplingScaleImageView(context)
            view.setRegionDecoderFactory { _, _, _ ->
                object : ImageRegionDecoder {
                    override fun init(context: Context, provider: InputProvider) = Point(400, 400)
                    override fun isReady() = recycleCount.get() == 0
                    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
                        active.incrementAndGet()
                        try {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            return Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                    override fun recycle() {
                        if (active.get() != 0) recycledDuringDecode.incrementAndGet()
                        recycleCount.incrementAndGet()
                        retired.countDown()
                    }
                }
            }
            val exact = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY)
            view.measure(exact, exact)
            view.layout(0, 0, 400, 400)
            view.setImage(ImageSource.inputStream(byteArrayOf().inputStream()))
        }
        try {
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (entered.count != 0L && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    bitmap.recycle()
                }
                SystemClock.sleep(20)
            }
            assertTrue("Decode never started", entered.await(1, TimeUnit.SECONDS))
            Handler(Looper.getMainLooper()).post {
                view.recycle()
                view.recycle()
                returned.countDown()
            }
            assertTrue("UI recycle waited for the blocked decoder", returned.await(500, TimeUnit.MILLISECONDS))
            assertFalse("Decoder released while in use", retired.await(50, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { view.recycle() }
        }
        assertTrue("Detached decoder leaked", retired.await(5, TimeUnit.SECONDS))
        assertEquals(1, recycleCount.get())
        assertEquals(0, recycledDuringDecode.get())
    }
}
