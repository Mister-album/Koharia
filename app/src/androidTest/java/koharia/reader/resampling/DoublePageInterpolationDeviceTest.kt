package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DoublePageCompositionPolicy
import eu.kanade.tachiyomi.ui.reader.viewer.pager.DoublePageLayout
import eu.kanade.tachiyomi.ui.reader.viewer.refreshReaderResampling
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.ByteArrayOutputStream

class DoublePageInterpolationDeviceTest {
    @Test
    fun bothPhysicalPagesSwitchTogetherWithoutResettingSpreadZoom() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        preferences.moireReduction.set(true)
        preferences.resamplingKernel.set(ResamplingKernel.BILINEAR)
        lateinit var spread: DoublePageLayout
        val pages = mutableListOf<ReaderPageImageView>()
        val bitmap = Bitmap.createBitmap(1080, 800, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                spread = DoublePageLayout(
                    context,
                    DoublePageCompositionPolicy.Image(767, 1025, 0),
                    DoublePageCompositionPolicy.Image(509, 1025, 0),
                    {},
                    {},
                )
                for ((width, colour) in listOf(767 to Color.RED, 509 to Color.BLUE)) {
                    val source = Bitmap.createBitmap(width, 1025, Bitmap.Config.ARGB_8888)
                    source.eraseColor(colour)
                    val bytes = ByteArrayOutputStream().use {
                        check(source.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                    source.recycle()
                    val page = ReaderPageImageView(context, readerPreferences = preferences)
                    pages += page
                    spread.addView(page)
                    page.onImageLoaded = { spread.updateViewport() }
                    page.setImage(Buffer().write(bytes), false, ReaderPageImageView.Config(0))
                }
            }
            fun await(kernel: ResamplingKernel) {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                var complete = false
                while (!complete && SystemClock.elapsedRealtime() < deadline) {
                    instrumentation.runOnMainSync {
                        spread.measure(exact(1080), exact(800))
                        spread.layout(0, 0, 1080, 800)
                        spread.draw(Canvas(bitmap))
                        complete = pages.all { page ->
                            val image = page.getChildAt(0) as SubsamplingScaleImageView
                            val decoder = field(image, "decoder") as? ResamplingRegionDecoder
                            val tiles = (field(image, "tileMap") as? Map<*, *>)?.values
                                ?.flatMap { it as List<*> }.orEmpty().filterNotNull()
                                .filter { field(it, "visible") == true }
                            decoder != null && (field(decoder, "options") as ResamplingOptions).kernel == kernel &&
                                tiles.isNotEmpty() && tiles.all {
                                    field(it, "bitmap") != null && field(it, "loading") == false &&
                                        field(it, "bitmapFiltered") == true &&
                                        field(it, "completedRevision") == field(it, "requestedRevision")
                                }
                        }
                    }
                    if (!complete) SystemClock.sleep(25)
                }
                assertTrue("Spread interpolation timeout for $kernel", complete)
            }
            await(ResamplingKernel.BILINEAR)
            instrumentation.runOnMainSync { spread.zoomAt(3f, 540f, 400f) }
            await(ResamplingKernel.BILINEAR)
            val images = pages.map { it.getChildAt(0) as SubsamplingScaleImageView }
            val states = images.map { checkNotNull(it.state) }
            instrumentation.runOnMainSync {
                preferences.resamplingKernel.set(ResamplingKernel.LANCZOS3)
                preferences.resamplingQuality.set(ResamplingQuality.DETAIL)
                assertTrue(spread.refreshReaderResampling())
            }
            await(ResamplingKernel.LANCZOS3)
            instrumentation.runOnMainSync {
                assertEquals(3f, spread.zoom, .00001f)
                images.forEachIndexed { index, image ->
                    assertSame(image, pages[index].getChildAt(0))
                    assertEquals(states[index].scale, image.scale, .00001f)
                    assertEquals(states[index].center.x, checkNotNull(image.center).x, .001f)
                    assertEquals(states[index].center.y, checkNotNull(image.center).y, .001f)
                }
                spread.zoomAt(5f, 540f, 400f)
            }
            await(ResamplingKernel.LANCZOS3)
            assertEquals(5f, spread.zoom, .00001f)
        } finally {
            instrumentation.runOnMainSync { pages.forEach { it.recycle() } }
            bitmap.recycle()
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(owner)
    }
}
