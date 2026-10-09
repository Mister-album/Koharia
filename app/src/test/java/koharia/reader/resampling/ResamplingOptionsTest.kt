package koharia.reader.resampling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResamplingOptionsTest {
    @Test
    fun `default preserves Mitchell with no extra softness`() {
        assertEquals(ResamplingKernel.MITCHELL, ResamplingOptions().kernel)
        assertEquals(0.0, ResamplingOptions().softening)
        assertEquals(3.0, RegionResamplingPlan(.5).outputHalo)
    }

    @Test
    fun `nonfinite and excessive softness cannot reach native allocation`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.01, 1.01)) {
            assertThrows(IllegalArgumentException::class.java) { ResamplingOptions(softening = value) }
        }
    }

    @Test
    fun `halo covers cubic convolved with gaussian at every sample level`() {
        for (scale in listOf(.01, .17830609, .25, .42265147, .75)) {
            for (softness in listOf(.25, .5, 1.0)) {
                val plan = RegionResamplingPlan(scale, options = ResamplingOptions(softening = softness))
                val support = (2 + 3 * softness) / scale
                val edge = 10000
                assertTrue(plan.inputStart(edge) <= edge / scale - support)
                assertTrue(plan.inputEnd(edge, Int.MAX_VALUE) >= edge / scale + support)
                assertEquals(0, plan.inputStart(edge) % plan.inputSample)
            }
        }
    }
}
