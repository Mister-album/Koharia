package koharia.reader.resampling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RegionResamplingPlanTest {
    @Test
    fun `webp crop keeps even origin and integer decode ratio at odd edges`() {
        val plan = RegionResamplingPlan(.25, evenCrop = true)
        assertEquals(1050, plan.inputEnd(263, 1051))
        assertEquals(0, plan.inputStart(83) % 2)
        assertEquals(1, RegionResamplingPlan(.01, minimumSourceDimension = 1).inputSample)
    }

    @Test
    fun `input retains at least twice the target resolution`() {
        for (scale in listOf(.01, .125, .25, .33, .5, .75)) {
            val plan = RegionResamplingPlan(scale)
            assertTrue(plan.inputSample == 1 || 1.0 / plan.inputSample >= scale * 2)
            assertEquals(1, Integer.bitCount(plan.inputSample))
        }
    }

    @Test
    fun `odd tile boundaries partition the same output grid and preserve decode alignment`() {
        for (scale in listOf(.25, .33, .5, .75)) {
            val plan = RegionResamplingPlan(scale)
            val boundaries = listOf(0, 133, 512, 821, 1051).map(plan::outputBoundary)
            assertEquals(plan.outputBoundary(1051), boundaries.zipWithNext().sumOf { (a, b) -> b - a })
            for (edge in boundaries) {
                assertEquals(0, plan.inputStart(edge) % plan.inputSample)
                assertTrue(plan.inputStart(edge) <= edge / scale)
                assertTrue(plan.inputEnd(edge, 1051) <= 1051)
            }
        }
    }
}
