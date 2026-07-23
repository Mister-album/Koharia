package koharia.epub.service

import koharia.epub.model.EpubOpenRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubReaderSupportResolutionTest {

    @Test
    fun `divina compatible epub opens with page reader`() {
        val resolution = EpubReaderSupportResolution(
            mangaId = 1,
            chapterId = 2,
            isDivinaCompatible = true,
            preferredOpenSource = EpubOpenRequest.OpenSource.REMOTE,
        )

        assertTrue(resolution.shouldOpenAsPages)
    }

    @Test
    fun `regular epub stays in native epub reader`() {
        val resolution = EpubReaderSupportResolution(
            mangaId = 1,
            chapterId = 2,
            isDivinaCompatible = false,
            preferredOpenSource = EpubOpenRequest.OpenSource.REMOTE,
        )

        assertFalse(resolution.shouldOpenAsPages)
        assertTrue(resolution.isNativeSupported)
    }

    @Test
    fun `unsupported publication cannot be routed as pages by epub classification`() {
        val resolution = EpubReaderSupportResolution(
            mangaId = 1,
            chapterId = 2,
            isDivinaCompatible = true,
            preferredOpenSource = null,
        )

        assertFalse(resolution.shouldOpenAsPages)
    }
}
