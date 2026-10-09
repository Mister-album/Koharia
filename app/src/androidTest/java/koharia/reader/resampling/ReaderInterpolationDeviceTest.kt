package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.refreshReaderResampling
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderInterpolationDeviceTest {
    @Test
    fun slightlyEnlargedPageUsesSessionAlgorithmAndSwitchesWithoutLosingViewport() = verifySwitching(true)

    @Test
    fun enablingDuringInitialLoadUsesLatestSnapshotWithoutReplacingView() = verifySwitching(false)

    @Test
    fun mitchellProcessesSlightAndHighMagnificationWithoutThreshold() = verifySwitching(true, ResamplingKernel.MITCHELL)

    private fun verifySwitching(
        initiallyEnabled: Boolean,
        initialKernel: ResamplingKernel = ResamplingKernel.CATMULL_ROM,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        preferences.moireReduction.set(initiallyEnabled)
        preferences.resamplingKernel.set(initialKernel)
        val source = Bitmap.createBitmap(2500, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val bytes = ByteArrayOutputStream().use { output ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        source.recycle()
        lateinit var frame: ReaderPageImageView
        lateinit var image: SubsamplingScaleImageView
        var created = false
        val screen = Bitmap.createBitmap(2560, 256, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                frame = ReaderPageImageView(context, readerPreferences = preferences)
                created = true
                frame.measure(exact(2560), exact(256))
                frame.layout(0, 0, 2560, 256)
                frame.setImage(Buffer().write(bytes), false, ReaderPageImageView.Config(0))
                image = frame.getChildAt(0) as SubsamplingScaleImageView
                if (!initiallyEnabled) {
                    preferences.moireReduction.set(true)
                    assertTrue(frame.refreshReaderResampling())
                }
            }
            fun awaitKernel(kernel: ResamplingKernel) {
                var done = false
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (!done && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        frame.measure(exact(2560), exact(256))
                        frame.layout(0, 0, 2560, 256)
                        frame.draw(Canvas(screen))
                        val decoder = field(image, "decoder") as? ResamplingRegionDecoder
                        val tiles = (field(image, "tileMap") as? Map<*, *>)?.values
                            ?.flatMap { it as List<*> }.orEmpty().filterNotNull()
                            .filter { field(it, "visible") == true }
                        done = decoder != null && (field(decoder, "options") as ResamplingOptions).kernel == kernel &&
                            tiles.isNotEmpty() && tiles.all {
                                field(it, "bitmap") != null && field(it, "loading") == false &&
                                    field(it, "completedRevision") == field(it, "requestedRevision")
                            }
                        if (done) {
                            val detail = (field(image, "tileMap") as Map<*, *>)[0] as List<*>
                            assertTrue(
                                detail.filterNotNull().filter { field(it, "visible") == true }.all {
                                    field(it, "bitmapScale") == image.scale && field(it, "bitmapFiltered") == true
                                },
                            )
                        }
                    }
                    if (!done) SystemClock.sleep(25)
                }
                assertTrue("Reader interpolation timeout for $kernel", done)
            }
            awaitKernel(initialKernel)
            assertEquals(1.024f, image.scale, .00001f)
            instrumentation.runOnMainSync { image.setScaleAndCenter(4f, PointF(1200f, 50f)) }
            awaitKernel(initialKernel)
            var center = PointF()
            instrumentation.runOnMainSync {
                center = checkNotNull(image.center)
                preferences.resamplingKernel.set(ResamplingKernel.BILINEAR)
                assertTrue(frame.refreshReaderResampling())
                preferences.resamplingKernel.set(ResamplingKernel.LANCZOS3)
                preferences.resamplingQuality.set(ResamplingQuality.SPEED)
                assertTrue(frame.refreshReaderResampling())
            }
            awaitKernel(ResamplingKernel.LANCZOS3)
            instrumentation.runOnMainSync {
                assertSame(image, frame.getChildAt(0))
                assertEquals(4f, image.scale, .00001f)
                assertEquals(center.x, checkNotNull(image.center).x, .001f)
                assertEquals(center.y, checkNotNull(image.center).y, .001f)
            }
            instrumentation.runOnMainSync {
                preferences.moireReduction.set(false)
                assertTrue(frame.refreshReaderResampling())
            }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var disabled = false
            while (!disabled && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    frame.draw(Canvas(screen))
                    val decoder = field(image, "decoder") as? ResamplingRegionDecoder
                    disabled = decoder != null && field(decoder, "enabled") == false
                }
                if (!disabled) SystemClock.sleep(25)
            }
            assertTrue("Disabling interpolation timed out", disabled)
            instrumentation.runOnMainSync {
                preferences.moireReduction.set(true)
                assertTrue(frame.refreshReaderResampling())
            }
            awaitKernel(ResamplingKernel.LANCZOS3)
            instrumentation.runOnMainSync {
                assertSame(image, frame.getChildAt(0))
                assertEquals(4f, image.scale, .00001f)
                assertEquals(center.x, checkNotNull(image.center).x, .001f)
            }
            var maximumScale = 0f
            instrumentation.runOnMainSync {
                maximumScale = image.maxScale
                image.setScaleAndCenter(maximumScale, center)
            }
            awaitKernel(ResamplingKernel.LANCZOS3)
            instrumentation.runOnMainSync {
                assertEquals(maximumScale, image.scale, .00001f)
                val capture = ResamplingCaptureState(image)
                capture.assertProcessed(ResamplingOptions(ResamplingKernel.LANCZOS3, quality = ResamplingQuality.SPEED))
                assertTrue(
                    capture.tiles.all {
                        val bitmap = captureField(it, "bitmap") as Bitmap
                        bitmap.width.toLong() * bitmap.height <= 1024L * 1024
                    },
                )
            }
        } finally {
            instrumentation.runOnMainSync { if (created) frame.recycle() }
            screen.recycle()
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
}
