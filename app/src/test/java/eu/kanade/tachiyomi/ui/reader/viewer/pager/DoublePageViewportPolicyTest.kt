package eu.kanade.tachiyomi.ui.reader.viewer.pager

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoublePageViewportPolicyTest {

    @Test
    fun `wide viewport allows automatic double pages`() {
        assertTrue(DoublePageViewportPolicy.allowsAutomaticDoublePages(2400, 1080))
    }

    @Test
    fun `portrait and square viewports use single pages`() {
        assertFalse(DoublePageViewportPolicy.allowsAutomaticDoublePages(1080, 2400))
        assertFalse(DoublePageViewportPolicy.allowsAutomaticDoublePages(1200, 1200))
    }

    @Test
    fun `unmeasured viewport uses single pages`() {
        assertFalse(DoublePageViewportPolicy.allowsAutomaticDoublePages(0, 1080))
        assertFalse(DoublePageViewportPolicy.allowsAutomaticDoublePages(2400, 0))
    }
}
