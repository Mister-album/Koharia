package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** External large PNG fixture; captures loading and pyramid transitions without bundling user media. */
@RunWith(AndroidJUnit4::class)
class MoireLargePageFixtureDeviceTest {
    @Test
    fun captureLargePageLoadingAndTileTransitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val arguments = InstrumentationRegistry.getArguments()
        val argument = arguments.getString("moireFixtureDir")
        val options = ResamplingOptions(
            ResamplingKernel.valueOf(arguments.getString("moireKernel") ?: "MITCHELL"),
            arguments.getString("moireSoftening")?.toDouble() ?: 0.0,
            ResamplingQuality.valueOf(arguments.getString("moireQuality") ?: "BALANCED"),
        )
        val budget = ResamplingBudget(
            reuseJpegBands = arguments.getString("moireReuseJpegBands") == "true",
            pngBandRows = arguments.getString("moirePngBandRows")?.toInt() ?: 256,
        )
        assumeNotNull(argument)
        val root = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
        val dir = File(root, checkNotNull(argument)).canonicalFile
        require(dir.toPath().startsWith(root.toPath()) && dir != root)
        val input = File(dir, InstrumentationRegistry.getArguments().getString("moireFixtureName") ?: "006.png")
        require(input.isFile)
        assertTrue(ImageResampler.available)
        val output = File(dir, "capture-${System.currentTimeMillis()}").apply { check(mkdir()) }
        File(output, "options.txt").writeText("$options\ninput=${input.name}\n")
        val metrics =
            StringBuilder(
                "width,enabled,elapsed_ms,ready,scale,base_sample,tile_factor,visible,loading,loaded,filtered,band_decodes,cache_bytes,native_bytes,max_heap\n",
            )
        try {
            for (width in arguments.getString("moireWidth")?.toInt()?.let(::listOf) ?: listOf(1080, 2560)) {
                for (filter in if (arguments.getString("moireFilterOnly") ==
                    "true"
                ) {
                    listOf(true)
                } else {
                    listOf(false, true)
                }) {
                    run {
                        lateinit var image: SubsamplingScaleImageView
                        val height = width * 3 / 2
                        val started = SystemClock.elapsedRealtime()
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        var finished = false
                        var capturedReady = false
                        var capturedTransitions = 0
                        var previousLoaded = -1
                        var previousFiltered: Set<Float> = emptySet()
                        try {
                            instrumentation.runOnMainSync {
                                image = SubsamplingScaleImageView(context).apply {
                                    // Offscreen software capture isolates the tile engine from activity launch and GPU upload.
                                    setHardwareConfig(false)
                                    setMaxTileSize(if (filter) ResamplingRegionDecoder.MAX_TILE_SIZE else 2048)
                                    setMinimumTileDpi(180)
                                    if (filter) {
                                        setRegionDecoderFactory { crop, _, profile ->
                                            ResamplingRegionDecoder(crop, profile, options, budget)
                                        }
                                    }
                                    measure(
                                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                                    )
                                    layout(0, 0, width, height)
                                    setImage(ImageSource.inputStream(input.inputStream()))
                                }
                            }
                            while (!finished && SystemClock.elapsedRealtime() - started < 90_000) {
                                var capture: String? = null
                                instrumentation.runOnMainSync {
                                    bitmap.eraseColor(android.graphics.Color.BLACK)
                                    image.draw(Canvas(bitmap))
                                    val view = image
                                    val state = ResamplingCaptureState(view)
                                    val tiles = state.tiles
                                    val loading = tiles.count { field(it, "loading") == true }
                                    val loaded = tiles.count { field(it, "bitmap") != null }
                                    val filtered = tiles.count {
                                        field(it, "bitmap") != null &&
                                            field(it, "bitmapFiltered") == true
                                    }
                                    if (filter) {
                                        val detail = tiles.filter { field(it, "requestedScale") == view.scale }
                                        val scales = detail.filter {
                                            field(it, "bitmap") != null &&
                                                field(it, "bitmapFiltered") == true
                                        }
                                            .map { field(it, "bitmapScale") as Float }.toSet()
                                        assertTrue("Mixed filtering scales: $scales", scales.size <= 1)
                                        previousFiltered = scales
                                    }
                                    val elapsed = SystemClock.elapsedRealtime() - started
                                    metrics.append(
                                        "$width,$filter,$elapsed,${view.isReady},${view.scale}," +
                                            "${field(view, "fullImageSampleSize")},${field(view, "tileScaleFactor")}," +
                                            "${tiles.size},$loading,$loaded,$filtered," +
                                            "${cacheField(view, "decodeCount")},${cacheField(view, "retainedBytes")}," +
                                            "${android.os.Debug.getNativeHeapAllocatedSize()},${Runtime.getRuntime().maxMemory()}\n",
                                    )
                                    if (view.isReady && !capturedReady) {
                                        capture = "first-ready"
                                        capturedReady = true
                                    } else if (view.isReady && filter && width == 2560 &&
                                        loaded > previousLoaded && loading > 0 && capturedTransitions < 4
                                    ) {
                                        capture = "transition-$elapsed"
                                        capturedTransitions++
                                    }
                                    previousLoaded = loaded
                                    finished =
                                        view.isReady && state.complete
                                    if (finished && filter) {
                                        state.assertProcessed(options)
                                    }
                                    if (finished) capture = "complete"
                                }
                                capture?.takeIf { finished }?.let { stage ->
                                    File(output, "$width-$filter-$stage.png").outputStream().use {
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                }
                                if (!finished) SystemClock.sleep(250)
                            }
                            assertTrue("Large page load timed out at width=$width filtering=$filter", finished)
                            if (filter && width == 2560 &&
                                (options.kernel != ResamplingKernel.MITCHELL || image.scale < 1f) &&
                                image.scale != 1f
                            ) {
                                assertTrue(
                                    "Detailed tiles must use the final display scale",
                                    previousFiltered.single() == image.scale,
                                )
                            }
                        } finally {
                            instrumentation.runOnMainSync { image.recycle() }
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            File(output, "loading.csv").writeText(metrics.toString())
        }
    }

    private fun cacheField(view: SubsamplingScaleImageView, name: String): Any? {
        val decoder = field(view, "decoder") as? ResamplingRegionDecoder ?: return null
        return field(checkNotNull(field(decoder, "inputCache")), name)
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
}
