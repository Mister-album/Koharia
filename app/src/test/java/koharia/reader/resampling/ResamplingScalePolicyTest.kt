package koharia.reader.resampling

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResamplingScalePolicyTest {
    @Test
    fun `all kernels apply to every valid downscale and upscale`() {
        for (kernel in ResamplingKernel.entries) {
            for (quality in ResamplingQuality.entries) {
                val options = ResamplingOptions(kernel, quality = quality)
                for (scale in listOf(.18f, .25f, .33f, .5f, .501f, .75f, .999f, 1.024f, 2f, 4f, 10f)) {
                    assertTrue(options.shouldFilter(scale), "$kernel/$quality at $scale")
                }
            }
        }
    }

    @Test
    fun `original size bypasses interpolation unless an extra effect requires it`() {
        for (kernel in ResamplingKernel.entries) {
            assertFalse(ResamplingOptions(kernel).shouldFilter(1f))
            assertTrue(ResamplingOptions(kernel, softening = .5).shouldFilter(1f))
        }
    }

    @Test
    fun `invalid scales never request filtering`() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (kernel in ResamplingKernel.entries) {
                assertFalse(ResamplingOptions(kernel).shouldFilter(scale))
            }
        }
    }
}
