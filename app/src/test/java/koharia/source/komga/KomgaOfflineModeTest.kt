package koharia.source.komga

import koharia.epub.service.shouldUseKomgaReadiumNetwork
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

    @Test
    fun `cached-only mode blocks Readium network access`() {
        assertFalse(shouldUseKomgaReadiumNetwork(cachedOnly = true))
        assertTrue(shouldUseKomgaReadiumNetwork(cachedOnly = false))
    }
}
