package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubImageResourceLoaderTest {

    @Test
    fun `relative source is resolved from its xhtml resource`() {
        val candidates = candidates(
            documentHref = "OPS/text/chapter.xhtml",
            currentSource = "../images/cover%20art.jpg?width=1200#preview",
            rawSource = "../images/cover%20art.jpg",
        )

        assertEquals(
            listOf(
                "OPS/images/cover%20art.jpg?width=1200",
                "OPS/images/cover%20art.jpg",
            ),
            candidates,
        )
    }

    @Test
    fun `readium absolute current source also yields a publication path`() {
        val candidates = candidates(
            documentHref = "OPS/text/chapter.xhtml",
            currentSource = "https://readium_package/OPS/images/cover.jpg#preview",
            rawSource = "../images/cover.jpg",
        )

        assertEquals(
            listOf(
                "https://readium_package/OPS/images/cover.jpg",
                "OPS/images/cover.jpg",
            ),
            candidates,
        )
    }

    @Test
    fun `remote absolute source stays inside publication lookup candidates`() {
        val candidates = candidates(
            documentHref = "OPS/text/chapter.xhtml",
            currentSource = "https://cdn.example.org/book/illustration.webp?token=redacted",
            rawSource = "",
        )

        assertEquals(
            listOf("https://cdn.example.org/book/illustration.webp?token=redacted"),
            candidates,
        )
    }

    @Test
    fun `data blob and local schemes are rejected`() {
        listOf(
            "data:image/png;base64,AA==",
            "blob:https://readium_package/id",
            "file:///storage/image.png",
            "content://provider/image.png",
            "javascript:alert(1)",
        ).forEach { source ->
            assertTrue(candidates("OPS/chapter.xhtml", source, source).isEmpty(), source)
        }
    }

    private fun candidates(
        documentHref: String,
        currentSource: String,
        rawSource: String,
    ): List<String> {
        return epubImageCandidateHrefs(documentHref, currentSource, rawSource)
    }
}
