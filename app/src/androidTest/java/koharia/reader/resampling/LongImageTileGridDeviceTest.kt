package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.eink.EInkMotionFixtureActivity
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LongImageTileGridDeviceTest {
    @Test
    fun longWebtoonLoadsAndReachesLastPixelsBothBelowAndAboveThreshold() = verify(257, 20001)

    @Test
    fun wideImageLoadsAndReachesLastPixelsWithoutOversizedOrMissingTiles() = verify(20001, 257)

    private fun verify(width: Int, height: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val preferences = Injekt.get<ReaderPreferences>()
        val enabled = preferences.moireReduction.get()
        val enabledWasSet = preferences.moireReduction.isSet()
        val threshold = preferences.moireReductionThreshold.get()
        val thresholdWasSet = preferences.moireReductionThreshold.isSet()
        val file = File.createTempFile("long-tile-grid-", ".png", context.cacheDir)
        try {
            val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.RED)
                Canvas(source).drawRect(
                    if (height > width) Rect(0, height / 2, width, height) else Rect(width / 2, 0, width, height),
                    Paint().apply { color = Color.BLUE },
                )
                file.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                source.recycle()
            }
            preferences.moireReduction.set(true)
            for (percent in listOf(50, 25)) {
                preferences.moireReductionThreshold.set(percent)
                val loaded = CountDownLatch(1)
                var image: ReaderPageImageView? = null
                ActivityScenario.launch(EInkMotionFixtureActivity::class.java).use { scenario ->
                    try {
                        scenario.onActivity { host ->
                            val reader = ReaderPageImageView(host, isWebtoon = height > width).also { image = it }
                            host.setContentView(
                                FrameLayout(host).apply { addView(reader, FrameLayout.LayoutParams(128, 256)) },
                            )
                            reader.onImageLoaded = { loaded.countDown() }
                            reader.setImage(
                                file.source().buffer(),
                                false,
                                ReaderPageImageView.Config(
                                    0,
                                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                                ),
                            )
                        }
                        assertTrue(
                            "Long image load timed out: $width x $height, threshold=$percent",
                            loaded.await(30, TimeUnit.SECONDS),
                        )
                        scenario.onActivity {
                            val view = checkNotNull(image).getChildAt(0) as SubsamplingScaleImageView
                            assertGrid(view, width, height)
                            view.setScaleAndCenter(
                                1f,
                                if (height >
                                    width
                                ) {
                                    PointF(width / 2f, height - 1f)
                                } else {
                                    PointF(width - 1f, height / 2f)
                                },
                            )
                        }
                        val deadline = SystemClock.uptimeMillis() + 10_000
                        var blue = false
                        while (!blue && SystemClock.uptimeMillis() < deadline) {
                            scenario.onActivity {
                                val bitmap = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
                                try {
                                    checkNotNull(image).draw(Canvas(bitmap))
                                    blue = bitmap.getPixel(64, 128) == Color.BLUE
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                            if (!blue) SystemClock.sleep(25)
                        }
                        assertTrue("Last region was not displayed", blue)
                        scenario.onActivity {
                            assertGrid(checkNotNull(image).getChildAt(0) as SubsamplingScaleImageView, width, height)
                        }
                    } finally {
                        scenario.onActivity { image?.recycle() }
                    }
                }
            }
        } finally {
            if (enabledWasSet) preferences.moireReduction.set(enabled) else preferences.moireReduction.delete()
            if (thresholdWasSet) {
                preferences.moireReductionThreshold.set(
                    threshold,
                )
            } else {
                preferences.moireReductionThreshold.delete()
            }
            file.delete()
        }
    }

    private fun assertGrid(view: SubsamplingScaleImageView, width: Int, height: Int) {
        val field = SubsamplingScaleImageView::class.java.getDeclaredField("tileMap").apply { isAccessible = true }
        val levels = field.get(view) as Map<*, *>
        for (tiles in levels.values) {
            var area = 0L
            for (tile in tiles as List<*>) {
                val type = checkNotNull(tile).javaClass
                val rect = type.getDeclaredField("sRect").apply { isAccessible = true }.get(tile) as Rect
                assertTrue(rect.width() > 0 && rect.height() > 0)
                area += rect.width().toLong() * rect.height()
                val bitmap = type.getDeclaredField("bitmap").apply { isAccessible = true }.get(tile) as Bitmap?
                if (bitmap != null) assertTrue(bitmap.width <= 256 && bitmap.height <= 256)
            }
            assertEquals("Every level must cover the entire source", width.toLong() * height, area)
        }
    }
}
