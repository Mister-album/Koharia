package eu.kanade.tachiyomi.ui.reader.transition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageTurnSensitivityTest {
    @Test
    fun `lower native drag sensitivity requires longer swipes than the original ViewPager default`() {
        fun native(
            sensitivity: PageTurnSensitivity,
        ) = PageTurnSwipeThresholds(8, 1f, 50, sensitivity, nativeDrag = true)

        assertTrue(native(PageTurnSensitivity.NORMAL).shouldCommit(-650f, 0f, 1_000, 1))
        assertFalse(native(PageTurnSensitivity.LOW).shouldCommit(-650f, 0f, 1_000, 1))
        assertTrue(native(PageTurnSensitivity.LOW).shouldCommit(-800f, 0f, 1_000, 1))
        assertFalse(native(PageTurnSensitivity.LOWEST).shouldCommit(-800f, 0f, 1_000, 1))
        assertTrue(native(PageTurnSensitivity.LOWEST).shouldCommit(-950f, 0f, 1_000, 1))
        assertTrue(native(PageTurnSensitivity.HIGHEST).shouldCommit(-90f, 0f, 1_000, 1))
    }

    @Test
    fun `default preserves existing slow drag and fast swipe thresholds`() {
        val thresholds = thresholds(PageTurnSensitivity.NORMAL)

        assertFalse(thresholds.shouldCommit(-179f, 0f, 1_000, 1))
        assertTrue(thresholds.shouldCommit(-180f, 0f, 1_000, 1))
        assertFalse(thresholds.shouldCommit(-24f, -900f, 1_000, 1))
        assertFalse(thresholds.shouldCommit(-25f, -399f, 1_000, 1))
        assertTrue(thresholds.shouldCommit(-25f, -400f, 1_000, 1))
    }

    @Test
    fun `higher sensitivity accepts shorter slower gestures while lower sensitivity rejects them`() {
        val normal = thresholds(PageTurnSensitivity.NORMAL)
        val high = thresholds(PageTurnSensitivity.HIGHEST)
        val low = thresholds(PageTurnSensitivity.LOWEST)

        assertFalse(normal.shouldCommit(-90f, 0f, 1_000, 1))
        assertTrue(high.shouldCommit(-90f, 0f, 1_000, 1))
        assertFalse(normal.shouldCommit(-20f, -200f, 1_000, 1))
        assertTrue(high.shouldCommit(-20f, -200f, 1_000, 1))
        assertTrue(normal.shouldCommit(-200f, 0f, 1_000, 1))
        assertFalse(low.shouldCommit(-200f, 0f, 1_000, 1))
        assertTrue(low.shouldCommit(-360f, 0f, 1_000, 1))
    }

    @Test
    fun `increasing sensitivity never rejects an already accepted gesture in either direction`() {
        for (distance in listOf(10f, 20f, 30f, 90f, 150f, 200f, 360f)) {
            for (speed in listOf(0f, 200f, 400f, 800f)) {
                for (delta in listOf(-1, 1)) {
                    var accepted = false
                    for (sensitivity in PageTurnSensitivity.entries) {
                        val result = thresholds(
                            sensitivity,
                        ).shouldCommit(-distance * delta, -speed * delta, 1_000, delta)
                        if (accepted) assertTrue(result, "$sensitivity rejected $distance/$speed/$delta")
                        accepted = result
                    }
                }
            }
        }
    }

    @Test
    fun `sensitivity preserves touch jitter and system velocity safety floors`() {
        val thresholds = PageTurnSwipeThresholds(8, 1f, 300, PageTurnSensitivity.HIGHEST)

        assertFalse(thresholds.shouldCommit(-15f, -2_000f, 1_000, 1))
        assertFalse(thresholds.shouldCommit(-20f, -299f, 1_000, 1))
        assertTrue(thresholds.shouldCommit(-20f, -300f, 1_000, 1))
    }

    @Test
    fun `reverse displacement and reverse fling do not commit`() {
        for (sensitivity in PageTurnSensitivity.entries) {
            val thresholds = thresholds(sensitivity)
            assertFalse(thresholds.shouldCommit(500f, 1_000f, 1_000, 1))
            assertFalse(thresholds.shouldCommit(-500f, -1_000f, 1_000, -1))
            assertFalse(thresholds.shouldCommit(-20f, 1_000f, 1_000, 1))
            assertFalse(thresholds.shouldCommit(0f, -1_000f, 1_000, 1))
        }
    }

    @Test
    fun `equivalent gestures behave consistently at different display densities`() {
        for (sensitivity in PageTurnSensitivity.entries) {
            for (distance in listOf(12f, 25f, 90f, 200f, 400f)) {
                for (speed in listOf(0f, 250f, 800f)) {
                    val base = thresholds(sensitivity).shouldCommit(-distance, -speed, 1_000, 1)
                    val dense = PageTurnSwipeThresholds(24, 3f, 150, sensitivity)
                        .shouldCommit(-distance * 3f, -speed * 3f, 3_000, 1)
                    assertEquals(base, dense)
                }
            }
        }
    }

    private fun thresholds(sensitivity: PageTurnSensitivity) = PageTurnSwipeThresholds(8, 1f, 50, sensitivity)
}
