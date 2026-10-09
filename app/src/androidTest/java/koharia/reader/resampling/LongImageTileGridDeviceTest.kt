package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class LongImageTileGridDeviceTest {
    @Test
    fun longWebtoonLoadsAndReachesLastPixelsWithAndWithoutInterpolation() = verify(257, 20001)

    @Test
    fun wideImageLoadsAndReachesLastPixelsWithoutOversizedOrMissingTiles() = verify(20001, 257)

    private fun verify(width: Int, height: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val preferences = Injekt.get<ReaderPreferences>()
        val enabled = preferences.moireReduction.get()
        val enabledWasSet = preferences.moireReduction.isSet()
        val oldKernel = preferences.resamplingKernel.get()
        val kernelWasSet = preferences.resamplingKernel.isSet()
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
            preferences.resamplingKernel.set(ResamplingKernel.MITCHELL)
            for (filtering in listOf(false, true)) {
                preferences.moireReduction.set(filtering)
                val loaded = CountDownLatch(1)
                var image: ReaderPageImageView? = null
                run {
                    try {
                        instrumentation.runOnMainSync {
                            val reader = ReaderPageImageView(
                                context,
                                isWebtoon = height > width,
                                basePreferences = BasePreferences(context, InMemoryPreferenceStore()).apply {
                                    alwaysDecodeLongStripWithSSIV.set(true)
                                },
                            ).also { image = it }
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
                        val loadDeadline = SystemClock.uptimeMillis() + 30_000
                        val capture = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
                        try {
                            while (loaded.count > 0 && SystemClock.uptimeMillis() < loadDeadline) {
                                instrumentation.runOnMainSync {
                                    checkNotNull(image).apply {
                                        measure(
                                            View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY),
                                            View.MeasureSpec.makeMeasureSpec(256, View.MeasureSpec.EXACTLY),
                                        )
                                        layout(0, 0, 128, 256)
                                        draw(Canvas(capture))
                                    }
                                }
                                SystemClock.sleep(25)
                            }
                        } finally {
                            capture.recycle()
                        }
                        assertEquals(
                            "Long image load timed out: $width x $height, interpolation=$filtering",
                            0L,
                            loaded.count,
                        )
                        instrumentation.runOnMainSync {
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
                            instrumentation.runOnMainSync {
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
                        instrumentation.runOnMainSync {
                            assertGrid(checkNotNull(image).getChildAt(0) as SubsamplingScaleImageView, width, height)
                        }
                    } finally {
                        instrumentation.runOnMainSync { image?.recycle() }
                    }
                }
            }
        } finally {
            if (enabledWasSet) preferences.moireReduction.set(enabled) else preferences.moireReduction.delete()
            if (kernelWasSet) preferences.resamplingKernel.set(oldKernel) else preferences.resamplingKernel.delete()
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
                if (bitmap !=
                    null
                ) {
                    assertTrue(
                        bitmap.width <= ResamplingRegionDecoder.MAX_TILE_SIZE &&
                            bitmap.height <= ResamplingRegionDecoder.MAX_TILE_SIZE,
                    )
                }
            }
            assertEquals("Every level must cover the entire source", width.toLong() * height, area)
        }
    }
}
