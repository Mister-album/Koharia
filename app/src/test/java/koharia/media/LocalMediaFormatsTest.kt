package koharia.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalMediaFormatsTest {

    @Test
    fun `available extensions include local books images and archive aliases`() {
        assertTrue("txt" in LocalMediaFormats.allExtensions)
        assertTrue("mobi" in LocalMediaFormats.allExtensions)
        assertTrue("azw3" in LocalMediaFormats.allExtensions)
        assertTrue("cb7" in LocalMediaFormats.allExtensions)
        assertTrue("cbt" in LocalMediaFormats.allExtensions)
        assertTrue("png" in LocalMediaFormats.allExtensions)
        assertTrue("djvu" in LocalMediaFormats.allExtensions)
        assertTrue("djvu" in LocalMediaFormats.knownExtensions)
    }

    @Test
    fun `book classification keeps text and mobi separate from images`() {
        assertTrue(LocalMediaFormats.isBook("txt"))
        assertTrue(LocalMediaFormats.isBook("mobi"))
        assertTrue(LocalMediaFormats.isBook("djvu"))
        assertFalse(LocalMediaFormats.isBook("png"))
        assertFalse(LocalMediaFormats.isReflowableBook("pdf"))
        assertFalse(LocalMediaFormats.isReflowableBook("djvu"))
        assertTrue(LocalMediaFormats.isReflowableBook("txt"))
        assertTrue(LocalMediaFormats.isReflowableBook("mobi"))
        assertTrue("pdf" in LocalMediaFormats.comicExtensions)
    }
}
