package koharia.reader.resampling

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException

@RunWith(AndroidJUnit4::class)
class DecodedBandCacheDeviceTest {
    @Test
    fun expandedBandsRespectPixelLimitAndCopyAcrossUnalignedRows() {
        val cache = DecodedBandCache(32L * 1024 * 1024, maxBandRows = 1024)
        val rectangles = mutableListOf<Rect>()
        try {
            val result = cache.decode(Rect(17, 330, 117, 400), 1, 6057, 8648, {}) { rect, sample ->
                assertTrue(rect.width().toLong() * rect.height() / sample / sample <= 2 * 1024 * 1024)
                rectangles += Rect(rect)
                Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(rect.top % 256, 37, 91))
                }
            }
            try {
                assertEquals(2, rectangles.size)
                assertEquals(346, rectangles[0].height())
                assertEquals(Color.rgb(0, 37, 91), result.getPixel(99, 15))
                assertEquals(Color.rgb(346 % 256, 37, 91), result.getPixel(99, 16))
                assertTrue(cache.retainedBytes <= 32L * 1024 * 1024)
            } finally {
                result.recycle()
            }
        } finally {
            cache.clear()
        }
        assertEquals(0L, cache.retainedBytes)
    }

    @Test
    fun cacheSizedRowsReuseUnalignedBandsAcrossColumns() {
        val cache = DecodedBandCache(1024L * 1024)
        val rows = cache.reusableRows(512, 1)
        try {
            for (left in listOf(0, 128, 256)) {
                cache.decode(Rect(left, 17, left + 128, 17 + rows), 1, 512, 2048, {}) { rect, _ ->
                    Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                }.recycle()
                assertEquals(2, cache.decodeCount)
                assertTrue(cache.retainedBytes <= 1024L * 1024)
            }
        } finally {
            cache.clear()
        }
    }

    @Test
    fun assembledInputCannotBypassThePixelLimit() {
        val cache = DecodedBandCache(1024L * 1024)
        assertThrows(IllegalArgumentException::class.java) {
            cache.decode(Rect(0, 0, 1, 3 * 1024 * 1024), 1, 1, 3 * 1024 * 1024, {}) { _, _ ->
                error("An oversized input must fail before decoding or allocation")
            }
        }
        assertEquals(0L, cache.retainedBytes)
    }

    @Test
    fun adjacentRegionsReuseScanlinesAndEvictionRespectsBudget() {
        val cache = DecodedBandCache(1024L * 1024)
        val decoded = mutableListOf<Bitmap>()
        val decode: (Rect, Int) -> Bitmap = { rect, sample ->
            Bitmap.createBitmap(rect.width() / sample, rect.height() / sample, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        setPixel(
                            x,
                            y,
                            Color.rgb(
                                (rect.left / sample + x) % 256,
                                (rect.top / sample + y) % 256,
                                37,
                            ),
                        )
                    }
                }
                decoded += this
            }
        }
        try {
            fun region(left: Int, top: Int) {
                val bitmap = cache.decode(Rect(left, top, left + 128, top + 128), 1, 512, 2048, {}, decode)
                try {
                    assertEquals(Color.rgb(left % 256, top % 256, 37), bitmap.getPixel(0, 0))
                    assertEquals(Color.rgb((left + 127) % 256, (top + 127) % 256, 37), bitmap.getPixel(127, 127))
                    assertTrue(cache.retainedBytes <= 1024L * 1024)
                } finally {
                    bitmap.recycle()
                }
            }
            region(0, 0)
            region(128, 0)
            assertEquals(1, cache.decodeCount)
            for (row in 256..1792 step 256) region(128, row)
            assertTrue(decoded.first().isRecycled)
        } finally {
            cache.clear()
        }
        assertEquals(0L, cache.retainedBytes)
        assertTrue(decoded.all { it.isRecycled })
    }

    @Test
    fun cancellationDuringDecodeDoesNotRetainPixels() {
        val cache = DecodedBandCache(1024L * 1024)
        var cancelled = false
        var allocated: Bitmap? = null
        try {
            cache.decode(Rect(0, 0, 128, 128), 1, 512, 512, {
                if (cancelled) throw CancellationException()
            }) { rect, _ ->
                Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888).also {
                    allocated = it
                    cancelled = true
                }
            }
            error("Cancellation was ignored")
        } catch (_: CancellationException) {
            assertEquals(0L, cache.retainedBytes)
            assertTrue(checkNotNull(allocated).isRecycled)
        } finally {
            cache.clear()
        }
    }
}
