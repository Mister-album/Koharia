package koharia.reader.resampling

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in external fixtures; measures submitted hardware frames without image encoding in the timed path. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MoireScreenSpeedDeviceTest {
    @Test
    fun compareSubmittedFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val arguments = InstrumentationRegistry.getArguments()
        val directory = arguments.getString("moireFixtureDir")
        assumeNotNull(directory)
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val inputDirectory = File(root, checkNotNull(directory)).canonicalFile
        require(inputDirectory.toPath().startsWith(root.toPath()) && inputDirectory != root)
        val input = File(inputDirectory, arguments.getString("moireFixtureName") ?: "006.png")
        require(input.isFile)
        val competingPages = (arguments.getString("moireCompetingPages")?.toInt() ?: 0).also { require(it in 0..2) }
        val profiling = arguments.getString("moireProfile") == "true"
        val budget = ResamplingBudget(
            reuseJpegBands = arguments.getString("moireReuseJpegBands") == "true",
            pngBandRows = arguments.getString("moirePngBandRows")?.toInt() ?: 256,
        )
        assertTrue(ImageResampler.available)
        val output = File(inputDirectory, "screen-${System.currentTimeMillis()}").apply { check(mkdir()) }
        val resumed = CountDownLatch(1)
        var host: Activity? = null
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is EInkMotionFixtureActivity && stage == Stage.RESUMED) {
                host = activity
                resumed.countDown()
            }
        }
        val metrics =
            StringBuilder(
                "image,round,mode,width,height,scale,first_frame_ms,complete_frame_ms,tiles,input_sample," +
                    "native_decodes,cache_bytes,peak_native_bytes,competing_pages," +
                    ResamplingTimings.csvHeader + "\n",
            )
        var activeViews = emptyList<TimedImageView>()
        try {
            instrumentation.runOnMainSync {
                monitor.addLifecycleCallback(callback)
                // Bounded asynchronous launch also reports OEM background-launch failures.
                context.startActivity(
                    Intent(context, EInkMotionFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            assertTrue("Fixture activity did not resume within 15 seconds", resumed.await(15, TimeUnit.SECONDS))
            val activity = checkNotNull(host)
            val selectedKernel = arguments.getString("moireKernel")
            val cases = if (selectedKernel != null) {
                if (selectedKernel == "OFF") {
                    listOf("off" to null)
                } else {
                    listOf(selectedKernel to ResamplingOptions(ResamplingKernel.valueOf(selectedKernel)))
                }
            } else if (arguments.getString("moireInterpolation") == "true") {
                ResamplingKernel.entries.filter { it != ResamplingKernel.MITCHELL }.flatMap { kernel ->
                    ResamplingQuality.entries.map { quality ->
                        "${kernel.name}-${quality.name}" to ResamplingOptions(kernel = kernel, quality = quality)
                    }
                }
            } else if (arguments.getString("moireCompatibility") == "true") {
                listOf("mitchell" to ResamplingOptions())
            } else {
                listOf(
                    "off" to null,
                    "mitchell" to ResamplingOptions(),
                    "catmull" to ResamplingOptions(ResamplingKernel.CATMULL_ROM),
                    "catmull025" to ResamplingOptions(ResamplingKernel.CATMULL_ROM, .25),
                    "catmull05" to ResamplingOptions(ResamplingKernel.CATMULL_ROM, .5),
                )
            }
            repeat(arguments.getString("moireRounds")?.toInt() ?: 3) { round ->
                // Rotate case order so one algorithm is not always the cold or hottest run.
                val order = cases.drop(round % cases.size) + cases.take(round % cases.size)
                for ((name, options) in order) {
                    val laidOut = CountDownLatch(1)
                    lateinit var image: TimedImageView
                    instrumentation.runOnMainSync {
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        activeViews = List(competingPages + 1) {
                            TimedImageView(activity).apply {
                                captureOptions = options
                                timings = if (profiling) ResamplingTimings() else null
                                setMaxTileSize(ResamplingRegionDecoder.MAX_TILE_SIZE)
                                setMinimumTileDpi(180)
                                setRegionDecoderFactory { crop, _, profile ->
                                    ResamplingRegionDecoder(
                                        crop,
                                        profile,
                                        options ?: ResamplingOptions(),
                                        budget = budget,
                                        enabled = options != null,
                                        timings = timings,
                                    )
                                }
                            }
                        }
                        image = activeViews.last()
                        activity.setContentView(
                            FrameLayout(activity).apply {
                                activeViews.forEach { addView(it, FrameLayout.LayoutParams(1080, 1620)) }
                            },
                        )
                        image.post { laidOut.countDown() }
                    }
                    assertTrue("Layout timeout", laidOut.await(5, TimeUnit.SECONDS))
                    val focusDeadline = SystemClock.elapsedRealtime() + 5_000
                    var focused = false
                    while (!focused && SystemClock.elapsedRealtime() < focusDeadline) {
                        instrumentation.runOnMainSync { focused = image.hasWindowFocus() }
                        if (!focused) SystemClock.sleep(20)
                    }
                    instrumentation.runOnMainSync {
                        assertEquals(1080, image.width)
                        assertEquals(1620, image.height)
                        assertTrue("Fixture must be visible on the physical screen", image.hasWindowFocus())
                        val started = SystemClock.elapsedRealtimeNanos()
                        activeViews.forEach {
                            it.started = started
                            it.setImage(ImageSource.inputStream(input.inputStream()))
                        }
                    }
                    assertTrue("Frame timeout: $name", image.complete.await(90, TimeUnit.SECONDS))
                    metrics.append(
                        "${input.name},$round,$name,${image.width},${image.height},${image.scale}," +
                            "${image.firstMs},${image.completeMs},${image.tileCount}," +
                            "${options?.let {
                                RegionResamplingPlan(image.scale.toDouble(), options = it).inputSample
                            }}," +
                            "${image.nativeDecodes},${image.cacheBytes},${image.peakNativeBytes},$competingPages," +
                            (image.timings?.csv() ?: ResamplingTimings().csv()) + "\n",
                    )
                    File(output, "timings.csv").writeText(metrics.toString())
                    if (round == 0) saveFrame(activity, image, File(output, "$name.png"))
                    activeViews.forEach {
                        assertTrue("Competing page timeout", it.complete.await(90, TimeUnit.SECONDS))
                    }
                    instrumentation.runOnMainSync { activeViews.forEach { it.recycle() } }
                    activeViews = emptyList()
                    SystemClock.sleep(500)
                }
            }
        } finally {
            File(output, "timings.csv").writeText(metrics.toString())
            instrumentation.runOnMainSync {
                activeViews.forEach { it.recycle() }
                host?.finish()
                monitor.removeLifecycleCallback(callback)
            }
        }
    }

    private fun saveFrame(activity: Activity, view: TimedImageView, file: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val copied = CountDownLatch(1)
            var result = -1
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val position = IntArray(2)
                view.getLocationInWindow(position)
                PixelCopy.request(
                    activity.window,
                    Rect(position[0], position[1], position[0] + view.width, position[1] + view.height),
                    bitmap,
                    {
                        result = it
                        copied.countDown()
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
            assertTrue(copied.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private class TimedImageView(context: Context) : SubsamplingScaleImageView(context) {
        var started = 0L
        var firstMs = -1.0
        var completeMs = -1.0
        var tileCount = 0
        var nativeDecodes = 0
        var cacheBytes = 0L
        var peakNativeBytes = 0L
        var captureOptions: ResamplingOptions? = null
        var timings: ResamplingTimings? = null
        val complete = CountDownLatch(1)
        private var firstSubmitted = false
        private var completeSubmitted = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            peakNativeBytes = maxOf(peakNativeBytes, android.os.Debug.getNativeHeapAllocatedSize())
            if (started == 0L || !isReady || completeSubmitted) return
            check(canvas.isHardwareAccelerated)
            val state = ResamplingCaptureState(this)
            val done = state.complete
            if (done) captureOptions?.let(state::assertProcessed)
            val first = !firstSubmitted
            if (first || done) {
                firstSubmitted = true
                completeSubmitted = done
                tileCount = state.tiles.size
                viewTreeObserver.registerFrameCommitCallback {
                    val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    if (first) firstMs = elapsed
                    if (done) {
                        (field(this, "decoder") as? ResamplingRegionDecoder)?.let { decoder ->
                            nativeDecodes = decoder.nativeDecodeCount.get()
                            cacheBytes = (field(field(decoder, "inputCache")!!, "retainedBytes") as Long)
                        }
                        completeMs = elapsed
                        complete.countDown()
                    }
                }
                // ViewRoot may already have collected this traversal's commit callbacks.
                postInvalidateOnAnimation()
            }
        }

        private fun field(target: Any, name: String): Any? {
            val type = if (target === this) SubsamplingScaleImageView::class.java else target.javaClass
            return type.getDeclaredField(name).run {
                isAccessible = true
                get(target)
            }
        }
    }
}
