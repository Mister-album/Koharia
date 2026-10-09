package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.provider.OpenStreamProvider
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayOutputStream
import java.util.function.BooleanSupplier

@RunWith(AndroidJUnit4::class)
class ResamplingReviewRegressionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "app.koharia.dev.devicefixture")
        check(ImageResampler.available)
    }

    @Test
    fun disabledInterpolationPreservesNativeDetailAtCalibratedScales() {
        val source = Bitmap.createBitmap(1440, 1440, Bitmap.Config.ARGB_8888)
        val row = IntArray(1440) { if (it % 2 == 0) Color.BLACK else Color.WHITE }
        for (y in 0 until source.height) source.setPixels(row, 0, row.size, 0, y, row.size, 1)
        try {
            for (format in listOf(
                Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.WEBP_LOSSLESS,
            )) {
                val bytes = encode(source, format)
                val decoder = ResamplingRegionDecoder(false, byteArrayOf(), enabled = false)
                val native = checkNotNull(ImageDecoder.newInstance(bytes.inputStream(), false, byteArrayOf()))
                try {
                    decoder.init(context, OpenStreamProvider(bytes.inputStream()))
                    assertFalse(decoder.shouldFilter(.75f))
                    for ((sample, factor, rawSample) in listOf(
                        Triple(2, 1.5f, 1),
                        Triple(4, 1.5f, 2),
                        Triple(2, 1f, 2),
                    )) {
                        val region = Rect(0, 0, 768, 768)
                        val result = decoder.decodeResult(region, sample, factor, false, BooleanSupplier { false })
                        val expected = checkNotNull(native.decode(region, rawSample))
                        try {
                            assertFalse(result.filtered)
                            assertEquals("$format sample=$sample factor=$factor", expected.width, result.bitmap.width)
                            assertEquals(expected.height, result.bitmap.height)
                            assertTrue("Native pixels changed for $format", expected.sameAs(result.bitmap))
                        } finally {
                            result.bitmap.recycle()
                            expected.recycle()
                        }
                    }
                } finally {
                    decoder.recycle()
                    native.recycle()
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun webtoonRebindRetainsChangedAlgorithmQualityAndSwitch() = verifyRebind(true)

    @Test
    fun pagerRebindRetainsChangedAlgorithmQualityAndSwitch() = verifyRebind(false)

    private fun verifyRebind(webtoon: Boolean) {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        val base = BasePreferences(context, InMemoryPreferenceStore()).apply {
            alwaysDecodeLongStripWithSSIV.set(true)
        }
        preferences.moireReduction.set(true)
        preferences.resamplingKernel.set(ResamplingKernel.CATMULL_ROM)
        val source = Bitmap.createBitmap(1440, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val bytes = encode(source, Bitmap.CompressFormat.PNG)
        source.recycle()
        val screen = Bitmap.createBitmap(1080, 256, Bitmap.Config.ARGB_8888)
        lateinit var frame: ReaderPageImageView
        instrumentation.runOnMainSync {
            frame =
                ReaderPageImageView(
                    context,
                    isWebtoon = webtoon,
                    basePreferences = base,
                    readerPreferences = preferences,
                )
        }
        fun draw(): SubsamplingScaleImageView {
            frame.measure(exact(1080), exact(256))
            frame.layout(0, 0, 1080, 256)
            frame.draw(Canvas(screen))
            return frame.getChildAt(0) as SubsamplingScaleImageView
        }
        fun awaitPage(expected: ReaderResamplingSettings) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var complete = false
            while (!complete && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val image = draw()
                    val decoder = captureField(image, "decoder") as? ResamplingRegionDecoder
                    complete = image.isReady && decoder != null &&
                        captureField(decoder, "enabled") == expected.enabled &&
                        captureField(decoder, "options") == expected.options &&
                        ResamplingCaptureState(image).complete
                }
                if (!complete) SystemClock.sleep(25)
            }
            assertTrue("Rebound page did not apply $expected (webtoon=$webtoon)", complete)
        }
        fun bind() {
            instrumentation.runOnMainSync {
                frame.recycle()
                frame.setImage(Buffer().write(bytes), false, ReaderPageImageView.Config(0))
            }
        }
        try {
            bind()
            awaitPage(preferences.resamplingSettings())
            for (change in listOf<() -> Unit>(
                {
                    preferences.resamplingKernel.set(ResamplingKernel.LANCZOS3)
                    preferences.resamplingQuality.set(ResamplingQuality.SPEED)
                },
                { preferences.moireReduction.set(false) },
                { preferences.moireReduction.set(true) },
                {
                    preferences.resamplingKernel.set(ResamplingKernel.MITCHELL)
                },
            )) {
                instrumentation.runOnMainSync {
                    val image = frame.getChildAt(0)
                    change()
                    assertTrue(frame.refreshResampling())
                    assertSame("Live refresh must retain the viewport", image, frame.getChildAt(0))
                }
                awaitPage(preferences.resamplingSettings())
                bind()
                awaitPage(preferences.resamplingSettings())
            }
        } finally {
            instrumentation.runOnMainSync { frame.recycle() }
            screen.recycle()
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat) = ByteArrayOutputStream().use {
        check(bitmap.compress(format, 100, it))
        it.toByteArray()
    }
}
