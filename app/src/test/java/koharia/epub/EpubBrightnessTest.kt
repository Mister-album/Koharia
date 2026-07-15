package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EpubBrightnessTest {

    @Test
    fun `disabled brightness follows the system`() {
        val state = calculateEpubBrightness(enabled = false, value = 80)

        assertNull(state.windowBrightness)
        assertEquals(0, state.overlayValue)
    }

    @Test
    fun `zero brightness follows the system`() {
        val state = calculateEpubBrightness(enabled = true, value = 0)

        assertNull(state.windowBrightness)
        assertEquals(0, state.overlayValue)
    }

    @Test
    fun `negative brightness uses the minimum window brightness and an overlay`() {
        val state = calculateEpubBrightness(enabled = true, value = -75)

        assertEquals(0.01f, state.windowBrightness)
        assertEquals(-75, state.overlayValue)
    }

    @Test
    fun `positive brightness maps to the window range`() {
        assertEquals(0.01f, calculateEpubBrightness(enabled = true, value = 1).windowBrightness)
        assertEquals(1f, calculateEpubBrightness(enabled = true, value = 100).windowBrightness)
    }

    @Test
    fun `brightness values are bounded to the supported range`() {
        assertEquals(-75, calculateEpubBrightness(enabled = true, value = -100).overlayValue)
        assertEquals(1f, calculateEpubBrightness(enabled = true, value = 150).windowBrightness)
    }
}
