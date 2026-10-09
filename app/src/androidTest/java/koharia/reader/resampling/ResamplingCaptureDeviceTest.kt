package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ResamplingCaptureDeviceTest {
    @Test
    fun nativeBaseIsAllowedButStaleOrUnfilteredEnlargementIsRejected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        assertTrue(ImageResampler.available)
        val source = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val bytes = ByteArrayOutputStream().use {
            check(source.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        source.recycle()
        for (kernel in ResamplingKernel.entries.filter { it != ResamplingKernel.MITCHELL }) {
            for (width in listOf(1000, 1024)) {
                val options = ResamplingOptions(kernel)
                val screen = Bitmap.createBitmap(width, 1200, Bitmap.Config.ARGB_8888)
                lateinit var image: SubsamplingScaleImageView
                instrumentation.runOnMainSync {
                    image = SubsamplingScaleImageView(context).apply {
                        setHardwareConfig(false)
                        setMaxTileSize(ResamplingRegionDecoder.MAX_TILE_SIZE)
                        setRegionDecoderFactory { crop, _, profile ->
                            ResamplingRegionDecoder(crop, profile, options)
                        }
                        measure(exact(width), exact(1200))
                        layout(0, 0, width, 1200)
                        setImage(ImageSource.inputStream(ByteArrayInputStream(bytes)))
                    }
                }
                try {
                    var complete = false
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    while (!complete && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync {
                            image.draw(Canvas(screen))
                            val state = ResamplingCaptureState(image)
                            complete = image.isReady && state.complete
                            if (complete) {
                                state.assertProcessed(options)
                                assertTrue(
                                    state.tiles.any {
                                        captureField(it, "bitmapScale") == 1f &&
                                            captureField(it, "bitmapFiltered") == false
                                    },
                                )
                                if (width > 1000) {
                                    val detail = state.tiles.first { captureField(it, "bitmapScale") == image.scale }
                                    setField(detail, "bitmapFiltered", false)
                                    assertThrows(AssertionError::class.java) { state.assertProcessed(options) }
                                    setField(detail, "bitmapFiltered", true)
                                    val revision = captureField(detail, "completedRevision") as Int
                                    setField(detail, "completedRevision", revision - 1)
                                    assertFalse(ResamplingCaptureState(image).complete)
                                    setField(detail, "completedRevision", revision)
                                }
                            }
                        }
                        if (!complete) SystemClock.sleep(20)
                    }
                    assertTrue("Capture timeout for $kernel at width $width", complete)
                } finally {
                    instrumentation.runOnMainSync { image.recycle() }
                    screen.recycle()
                }
            }
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun setField(target: Any, name: String, value: Any) = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        set(target, value)
    }
}
