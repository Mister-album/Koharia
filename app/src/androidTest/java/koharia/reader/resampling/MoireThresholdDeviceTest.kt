package koharia.reader.resampling

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.FilteredRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier

@RunWith(AndroidJUnit4::class)
class MoireThresholdDeviceTest {
    @Test
    fun zoomCrossesThresholdWithinSameLodAndKeepsPreviewWhileReplacingTiles() {
        assertFixture()
        val decoder = ColouredDecoder()
        lateinit var view: SubsamplingScaleImageView
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { host ->
                    view = SubsamplingScaleImageView(host)
                    host.setContentView(FrameLayout(host).apply { addView(view, FrameLayout.LayoutParams(400, 400)) })
                    prepare(view, decoder)
                }
                awaitColour(scenario, { view }, Color.RED)
                scenario.onActivity { view.setScaleAndCenter(.5f, PointF(512f, 512f)) }
                awaitColour(scenario, { view }, Color.RED)
                decoder.holdRaw = true
                scenario.onActivity { view.setScaleAndCenter(.51f, PointF(512f, 512f)) }
                await { decoder.rawRequests.get() > 0 }
                assertColour(scenario, view, Color.RED)
                scenario.onActivity { view.setScaleAndCenter(.49f, PointF(512f, 512f)) }
                await { decoder.cancelledRequests.get() > 0 }
                decoder.holdRaw = false
                awaitColour(scenario, { view }, Color.RED)
                scenario.onActivity { view.setScaleAndCenter(.51f, PointF(512f, 512f)) }
                awaitColour(scenario, { view }, Color.GREEN)
                scenario.onActivity { view.setScaleAndCenter(.5f, PointF(512f, 512f)) }
                awaitColour(scenario, { view }, Color.RED)
                scenario.onActivity { view.setScaleAndCenter(1f, PointF(512f, 512f)) }
                awaitColour(scenario, { view }, Color.GREEN)
            } finally {
                decoder.holdRaw = false
                scenario.onActivity { view.recycle() }
            }
        }
    }

    @Test
    fun webtoonParentZoomCountsTowardDisplayedPixelRatio() {
        assertFixture()
        val decoder = ColouredDecoder()
        var image: WebtoonSubsamplingImageView? = null
        lateinit var recycler: WebtoonRecyclerView
        ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { host ->
                    recycler = WebtoonRecyclerView(host).apply {
                        layoutManager = LinearLayoutManager(host)
                        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                            override fun getItemCount() = 1
                            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                                val child = WebtoonSubsamplingImageView(host).apply {
                                    layoutParams = RecyclerView.LayoutParams(400, 400)
                                    prepare(this, decoder)
                                }
                                image = child
                                return object : RecyclerView.ViewHolder(child) {}
                            }
                            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                        }
                    }
                    host.setContentView(
                        FrameLayout(host).apply {
                            addView(recycler, FrameLayout.LayoutParams(400, 400))
                        },
                    )
                }
                awaitColour(scenario, { image }, Color.RED)
                scenario.onActivity { recycler.onScale(2f) }
                awaitColour(scenario, { image }, Color.GREEN)
                scenario.onActivity { recycler.onScale(.5f) }
                awaitColour(scenario, { image }, Color.RED)
            } finally {
                scenario.onActivity { image?.recycle() }
            }
        }
    }

    private fun prepare(view: SubsamplingScaleImageView, decoder: ColouredDecoder) {
        view.setMaxTileSize(256)
        view.setRegionDecoderFactory { _, _, _ -> decoder }
        view.setImage(ImageSource.inputStream(byteArrayOf().inputStream()))
    }

    private fun assertColour(
        scenario: ActivityScenario<EInkMotionFixtureActivity>,
        view: SubsamplingScaleImageView,
        colour: Int,
    ) {
        scenario.onActivity { assertEquals(colour, centreColour(view)) }
    }

    private fun awaitColour(
        scenario: ActivityScenario<EInkMotionFixtureActivity>,
        view: () -> SubsamplingScaleImageView?,
        colour: Int,
    ) {
        await {
            var matched = false
            scenario.onActivity { matched = view()?.takeIf { it.isReady }?.let(::centreColour) == colour }
            matched
        }
    }

    private fun centreColour(view: SubsamplingScaleImageView): Int {
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap))
            bitmap.getPixel(200, 200)
        } finally {
            bitmap.recycle()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        assertTrue("Threshold transition timed out", condition())
    }

    private fun assertFixture() {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }

    private class ColouredDecoder : FilteredRegionDecoder {
        @Volatile var holdRaw = false

        @Volatile private var ready = true
        val rawRequests = AtomicInteger()
        val cancelledRequests = AtomicInteger()
        override fun init(context: Context, provider: InputProvider) = Point(1024, 1024)
        override fun isReady() = ready
        override fun isFilteringEnabled() = true
        override fun shouldFilter(displayScale: Float) = MoireReductionPolicy.shouldFilter(displayScale, 50)
        override fun recycle() {
            ready = false
        }
        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap = error("Expected filter-aware decoding")
        override fun decodeRegion(
            region: Rect,
            sampleSize: Int,
            scaleFactor: Float,
            filter: Boolean,
            cancelled: BooleanSupplier,
        ): Bitmap {
            if (!filter) rawRequests.incrementAndGet()
            while (!filter && holdRaw) {
                if (cancelled.asBoolean || !ready) {
                    cancelledRequests.incrementAndGet()
                    throw CancellationException()
                }
                SystemClock.sleep(5)
            }
            return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(if (filter) Color.RED else Color.GREEN)
            }
        }
    }
}
