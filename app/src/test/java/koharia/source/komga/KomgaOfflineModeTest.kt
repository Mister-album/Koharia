package koharia.source.komga

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaOfflineModeTest {

    @Test
    fun `cached-only mode never permits Komga network access`() {
        assertFalse(shouldUseKomgaNetwork(cachedOnly = true, isOnline = true))
        assertFalse(shouldUseKomgaNetwork(cachedOnly = true, isOnline = false))
    }

    @Test
    fun `normal mode follows device connectivity`() {
        assertTrue(shouldUseKomgaNetwork(cachedOnly = false, isOnline = true))
        assertFalse(shouldUseKomgaNetwork(cachedOnly = false, isOnline = false))
    }
}
