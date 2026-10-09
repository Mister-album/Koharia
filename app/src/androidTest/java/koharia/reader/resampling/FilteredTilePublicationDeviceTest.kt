package koharia.reader.resampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.FilteredRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

/** Exercises the real tile scheduler without depending on activity launch or changing preferences. */
@RunWith(AndroidJUnit4::class)
class FilteredTilePublicationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.also {
        check(it.packageName == "app.koharia.dev.devicefixture")
    }

    @Test
    fun replacementKeepsViewportAndCancelsPreviousConfiguration() = withView { view, decoder ->
        awaitColour(view, Color.RED)
        instrumentation.runOnMainSync { view.setScaleAndCenter(.45f, PointF(600f, 500f)) }
        awaitColour(view, Color.YELLOW)
        var center = PointF()
        instrumentation.runOnMainSync {
            center = checkNotNull(view.center)
            assertTrue(view.replaceFilteredDecoder { ColouredDecoder(Color.MAGENTA) })
            assertTrue(view.replaceFilteredDecoder { ColouredDecoder(Color.CYAN) })
        }
        awaitColour(view, Color.CYAN)
        instrumentation.runOnMainSync {
            assertEquals(.45f, view.scale, .000001f)
            assertEquals(center.x, checkNotNull(view.center).x, .001f)
            assertEquals(center.y, checkNotNull(view.center).y, .001f)
        }
        await(view) { !decoder.isReady() }
    }

    @Test
    fun rapidUnfinishedUpscaleBucketsRetainTheLastPublishedLayer() = withView { view, decoder ->
        decoder.upscale = true
        awaitColour(view, Color.RED)
        instrumentation.runOnMainSync { view.setScaleAndCenter(2f, PointF(512f, 512f)) }
        awaitColour(view, Color.YELLOW)
        decoder.hold = true
        instrumentation.runOnMainSync { view.maxScale = 10f }
        for (scale in listOf(2.01f, 4.01f)) {
            instrumentation.runOnMainSync { view.setScaleAndCenter(scale, PointF(512f, 512f)) }
            assertEquals(setOf(Color.YELLOW), colours(view))
        }
    }

    @Test
    fun enlargementGridUsesExactScaleAndBoundsOutputAcrossBuckets() = withView { view, decoder ->
        decoder.upscale = true
        awaitColour(view, Color.RED)
        instrumentation.runOnMainSync { view.maxScale = 10f }
        for (scale in listOf(1.024f, 2f, 2.01f, 4f, 10f, .75f)) {
            instrumentation.runOnMainSync { view.setScaleAndCenter(scale, PointF(512f, 512f)) }
            awaitColour(view, Color.YELLOW)
            await(view) { decoder.lastScale == scale }
            assertTrue("Upscaled output exceeded tile budget", decoder.maximumOutputPixels <= 256L * 256)
        }
    }

    @Test
    fun sameLodScaleChangesPublishTogetherAndCancelObsoleteRequests() = withView { view, decoder ->
        awaitColour(view, Color.RED)
        instrumentation.runOnMainSync { view.setScaleAndCenter(.42f, PointF(512f, 512f)) }
        awaitColour(view, Color.BLUE)
        decoder.hold = true
        instrumentation.runOnMainSync { view.setScaleAndCenter(.45f, PointF(512f, 512f)) }
        await(view) { decoder.waiting.get() > 0 }
        repeat(5) { assertEquals(setOf(Color.BLUE), colours(view)) }
        instrumentation.runOnMainSync { view.setScaleAndCenter(.43f, PointF(512f, 512f)) }
        await(view) { decoder.cancelledCount.get() > 0 }
        decoder.hold = false
        awaitColour(view, Color.BLUE)
        instrumentation.runOnMainSync { view.setScaleAndCenter(.51f, PointF(512f, 512f)) }
        awaitColour(view, Color.GREEN)
        instrumentation.runOnMainSync { view.setScaleAndCenter(.45f, PointF(512f, 512f)) }
        awaitColour(view, Color.YELLOW)
    }

    @Test
    fun failedFilterSwitchesTheWholePageToRawWithoutRetrying() = withView { view, decoder ->
        awaitColour(view, Color.RED)
        decoder.failNext = true
        instrumentation.runOnMainSync { view.setScaleAndCenter(.42f, PointF(512f, 512f)) }
        awaitColour(view, Color.GREEN)
        repeat(5) { assertEquals(setOf(Color.GREEN), colours(view)) }
        assertEquals(1, decoder.failures.get())
    }

    @Test
    fun webtoonUsesParentScaleAndReplacementDoesNotPublishOldPage() = withView(webtoon = true) { view, decoder ->
        awaitColour(view, Color.RED)
        instrumentation.runOnMainSync { (view.parent as WebtoonRecyclerView).scaleX = 1.15f }
        awaitColour(view, Color.YELLOW)
        instrumentation.runOnMainSync { (view.parent as WebtoonRecyclerView).scaleX = 2f }
        awaitColour(view, Color.GREEN)
        decoder.hold = true
        instrumentation.runOnMainSync { (view.parent as WebtoonRecyclerView).scaleX = 1.15f }
        await(view) { decoder.waiting.get() > 0 }
        instrumentation.runOnMainSync {
            view.recycle()
            view.setRegionDecoderFactory { _, _, _ -> ColouredDecoder(Color.MAGENTA) }
            view.setImage(ImageSource.inputStream(byteArrayOf().inputStream()))
        }
        awaitColour(view, Color.MAGENTA)
    }

    private fun withView(webtoon: Boolean = false, test: (SubsamplingScaleImageView, ColouredDecoder) -> Unit) {
        val decoder = ColouredDecoder()
        lateinit var view: SubsamplingScaleImageView
        instrumentation.runOnMainSync {
            view = if (webtoon) {
                WebtoonSubsamplingImageView(context).also {
                    WebtoonRecyclerView(context).apply {
                        layoutManager = LinearLayoutManager(context)
                        addView(it, ViewGroup.LayoutParams(400, 400))
                    }
                }
            } else {
                SubsamplingScaleImageView(context)
            }
            view.setMaxTileSize(256)
            view.setRegionDecoderFactory { _, _, _ -> decoder }
            view.measure(exact(400), exact(400))
            view.layout(0, 0, 400, 400)
            view.setImage(ImageSource.inputStream(byteArrayOf().inputStream()))
        }
        try {
            test(view, decoder)
        } finally {
            decoder.hold = false
            instrumentation.runOnMainSync { view.recycle() }
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun colours(view: SubsamplingScaleImageView): Set<Int> {
        var result = emptySet<Int>()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                result = (20 until 380 step 20).flatMap { y ->
                    (20 until 380 step 20).map { x -> bitmap.getPixel(x, y) }
                }.toSet()
            } finally {
                bitmap.recycle()
            }
        }
        return result
    }

    private fun awaitColour(view: SubsamplingScaleImageView, colour: Int) = await(view) {
        colours(view) == setOf(colour)
    }

    private fun await(view: SubsamplingScaleImageView, done: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            colours(view)
            if (done()) return
            SystemClock.sleep(25)
        }
        assertTrue("Tile transition timed out: ${colours(view)}", done())
    }

    private class ColouredDecoder(private val fixedColour: Int? = null) : FilteredRegionDecoder {
        @Volatile var hold = false

        @Volatile var upscale = false

        @Volatile var lastScale = 0f

        @Volatile var maximumOutputPixels = 0L

        @Volatile var failNext = false

        @Volatile private var failed = false
        val failures = AtomicInteger()

        @Volatile private var ready = true
        val waiting = AtomicInteger()
        val cancelledCount = AtomicInteger()
        override fun init(context: Context, provider: InputProvider) = Point(1024, 1024)
        override fun isReady() = ready
        override fun isFilteringEnabled() = true
        override fun shouldFilter(displayScale: Float) = !failed &&
            (if (upscale) displayScale != 1f else (displayScale > 0f && displayScale <= .5f))
        override fun recycle() {
            ready = false
        }
        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = error("Expected scale-aware decoding")
        override fun decodeResult(
            region: Rect,
            sampleSize: Int,
            scaleFactor: Float,
            filter: Boolean,
            cancelled: BooleanSupplier,
        ): FilteredRegionDecoder.Result {
            if (failNext && filter) {
                failNext = false
                failed = true
                failures.incrementAndGet()
            }
            val applied = filter && !failed
            return FilteredRegionDecoder.Result(
                decodeRegion(region, sampleSize, scaleFactor, applied, cancelled),
                applied,
            )
        }
        override fun decodeRegion(
            region: Rect,
            sampleSize: Int,
            scaleFactor: Float,
            filter: Boolean,
            cancelled: BooleanSupplier,
        ): Bitmap {
            val target = scaleFactor / sampleSize
            if (target > 1f) {
                val width = kotlin.math.round(region.right * target) - kotlin.math.round(region.left * target)
                val height = kotlin.math.round(region.bottom * target) - kotlin.math.round(region.top * target)
                maximumOutputPixels = maxOf(maximumOutputPixels, (width * height).toLong())
            }
            lastScale = target
            if (hold && target > .44f && region.left > 0) {
                waiting.incrementAndGet()
                while (hold) {
                    if (cancelled.asBoolean || !ready) {
                        cancelledCount.incrementAndGet()
                        throw CancellationException()
                    }
                    SystemClock.sleep(5)
                }
            }
            return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(
                    fixedColour ?: when {
                        !filter -> Color.GREEN
                        target < .4f -> Color.RED
                        target < .44f -> Color.BLUE
                        else -> Color.YELLOW
                    },
                )
            }
        }
    }
}
