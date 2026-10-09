package koharia.reader.resampling

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Capture only published tiles matching the current request; a raw 1:1 base is legitimate. */
internal class ResamplingCaptureState(private val view: SubsamplingScaleImageView) {
    val tiles = (captureField(view, "tileMap") as? Map<*, *>)?.values
        ?.flatMap { it as List<*> }.orEmpty().filterNotNull()
        .filter { captureField(it, "visible") == true }
    val complete = tiles.isNotEmpty() && tiles.all {
        captureField(it, "loading") == false && captureField(it, "bitmap") != null &&
            captureField(it, "bitmapScale") == captureField(it, "requestedScale") &&
            captureField(it, "completedFiltering") == captureField(it, "requestedFiltering") &&
            captureField(it, "completedRevision") == captureField(it, "requestedRevision")
    }

    fun assertProcessed(options: ResamplingOptions) {
        assertTrue("Capture requires a complete current layer", complete)
        val decoder = captureField(view, "decoder") as ResamplingRegionDecoder
        assertFalse("Benchmark must not time a failed filter", captureField(decoder, "filteringFailed") as Boolean)
        for (tile in tiles) {
            val target = captureField(tile, "requestedScale") as Float
            val expected = requiresFilter(target, options)
            assertEquals("Unexpected filtering at scale $target", expected, captureField(tile, "bitmapFiltered"))
        }
        val displayScale = captureField(view, "lastDisplayScale") as Float
        if (requiresFilter(displayScale, options)) {
            val detail = tiles.filter { captureField(it, "requestedScale") == displayScale }
            assertTrue("No detailed layer at actual display scale $displayScale", detail.isNotEmpty())
            assertTrue(
                "Detailed layer fell back to raw pixels",
                detail.all {
                    captureField(it, "bitmapFiltered") == true
                },
            )
        }
    }

    private fun requiresFilter(scale: Float, options: ResamplingOptions): Boolean =
        scale != 1f || options.softening != 0.0
}

internal fun captureField(target: Any, name: String): Any? {
    val type = if (target is SubsamplingScaleImageView) SubsamplingScaleImageView::class.java else target.javaClass
    return type.getDeclaredField(name).run {
        isAccessible = true
        get(target)
    }
}
