package koharia.reader.resampling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MoireReductionPolicyTest {
    @Test
    fun `default filters at half size and below but not slight downscaling`() {
        assertEquals(50, MoireReductionPolicy.DEFAULT_THRESHOLD)
        assertTrue(MoireReductionPolicy.shouldFilter(.5f, 50))
        assertTrue(MoireReductionPolicy.shouldFilter(.25f, 50))
        assertFalse(MoireReductionPolicy.shouldFilter(.501f, 50))
        assertFalse(MoireReductionPolicy.shouldFilter(.75f, 50))
    }

    @Test
    fun `every preset applies at its boundary except native size`() {
        for (percent in MoireReductionPolicy.thresholds) {
            val scale = percent / 100f
            assertTrue(MoireReductionPolicy.shouldFilter(scale - .001f, percent))
            assertEquals(percent < 100, MoireReductionPolicy.shouldFilter(scale, percent))
            assertFalse(MoireReductionPolicy.shouldFilter(scale + .001f, percent))
        }
    }

    @Test
    fun `invalid scale is bypassed and stale preference falls back to half size`() {
        for (scale in listOf(0f, -1f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(MoireReductionPolicy.shouldFilter(scale, 100))
        }
        for (percent in listOf(0, -1, 40, 101)) {
            assertEquals(50, MoireReductionPolicy.normalize(percent))
            assertTrue(MoireReductionPolicy.shouldFilter(.5f, percent))
            assertFalse(MoireReductionPolicy.shouldFilter(.51f, percent))
        }
    }
}
