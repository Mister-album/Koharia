package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EpubFootnoteImageLoaderTest {

    @Test
    fun `image sources retain relative paths and remove duplicates`() {
        val sources = epubFootnoteImageSources(
            """
            <p>Formula <img src="../images/formula.svg" alt="formula"></p>
            <p><img src="symbols/note.png"><img src="../images/formula.svg"></p>
            """.trimIndent(),
        )

        assertEquals(listOf("../images/formula.svg", "symbols/note.png"), sources)
    }

    @Test
    fun `image source count is bounded`() {
        val html = (0..9).joinToString(separator = "") { index -> "<img src=\"image-$index.png\">" }

        assertEquals(8, epubFootnoteImageSources(html).size)
    }
}
