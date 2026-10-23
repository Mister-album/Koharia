package koharia.reader.resampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.Decoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderImagePreview
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Slow final decoding must never change the preview's source coordinate system. */
@RunWith(AndroidJUnit4::class)
class ReaderPreviewGeometryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "app.koharia.dev.devicefixture")
    }

    @Test
    fun asymmetricBordersKeepViewportWhenFinalTilesReplacePreview() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP)) {
            val original = Bitmap.createBitmap(1000, 1600, Bitmap.Config.ARGB_8888)
            val bytes = try {
                original.eraseColor(Color.WHITE)
                Canvas(original).drawRect(260f, 80f, 860f, 1080f, Paint().apply { color = Color.BLUE })
                ByteArrayOutputStream().use { output ->
                    assertTrue(original.compress(format, 100, output))
                    output.toByteArray()
                }
            } finally {
                original.recycle()
            }
            for (crop in listOf(false, true)) verifyTransition(bytes, crop)
        }
    }

    private fun verifyTransition(bytes: ByteArray, crop: Boolean) {
        val source = Buffer().write(bytes)
        val preview = checkNotNull(ReaderImagePreview.decode(source, crop))
        assertEquals(bytes.size.toLong(), source.size)
        val expected = checkNotNull(ImageDecoder.newInstance(bytes.inputStream(), crop))
        try {
            assertEquals(expected.width, preview.width)
            assertEquals(expected.height, preview.height)
            if (crop) assertTrue("Fixture must exercise actual cropping", expected.width < 1000)
        } finally {
            expected.recycle()
        }
        val gate = CountDownLatch(1)
        val delegate = Decoder(crop, false, byteArrayOf())
        val delayed = object : ImageRegionDecoder by delegate {
            override fun init(context: Context, provider: InputProvider) = run {
                check(gate.await(10, TimeUnit.SECONDS))
                delegate.init(context, provider)
            }
        }
        lateinit var view: SubsamplingScaleImageView
        var created = false
        lateinit var center: PointF
        var scale = 0f
        val target = Bitmap.createBitmap(400, 700, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                view = SubsamplingScaleImageView(context)
                created = true
                view.apply {
                    setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH)
                    setCropBorders(crop)
                    setRegionDecoderFactory { _, _, _ -> delayed }
                    measure(exact(400), exact(700))
                    layout(0, 0, 400, 700)
                    val bitmap = preview.take()
                    assertTrue(bitmap.width.toLong() * bitmap.height <= 1_048_576L)
                    setImageWithPreview(
                        ImageSource.inputStream(bytes.inputStream()),
                        bitmap,
                        preview.width,
                        preview.height,
                    )
                    draw(Canvas(target))
                    assertTrue(isReady)
                    scale = this.scale
                    center = checkNotNull(this.center)
                }
            }
            gate.countDown()
            var loaded = false
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (!loaded && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    view.draw(Canvas(target))
                    assertEquals(scale, view.scale, .00001f)
                    assertEquals(center.x, checkNotNull(view.center).x, .01f)
                    assertEquals(center.y, checkNotNull(view.center).y, .01f)
                    loaded = view.isImageLoaded
                }
                SystemClock.sleep(20)
            }
            assertTrue("Final tiles never loaded", loaded)
        } finally {
            gate.countDown()
            instrumentation.runOnMainSync { if (created) view.recycle() }
            preview.recycle()
            target.recycle()
            source.close()
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
