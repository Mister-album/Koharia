package koharia.core.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class BoundedXmlTest {
    @Test
    fun `deep namespace input is rejected before DOM construction`() {
        val input = buildString {
            repeat(3000) { append("<p$it:n xmlns:p$it='urn:$it'>") }
            for (index in 2999 downTo 0) append("</p$index:n>")
        }
        assertThrows(IOException::class.java) { BoundedXml.parse(input) }
    }

    @Test
    fun `wide metadata is rejected without returning a partial document`() {
        val input = "<root>" + "<item/>".repeat(100_001) + "</root>"
        assertThrows(IOException::class.java) { BoundedXml.parse(input) }
    }

    @Test
    fun `oversized stream is rejected before parsing`() {
        val input = ("<root>" + "x".repeat(8 * 1024 * 1024) + "</root>").byteInputStream()
        input.use { assertThrows(IOException::class.java) { BoundedXml.parse(it) } }
    }

    @Test
    fun `namespaced OPF and declared encodings retain metadata`() {
        val input = """<?xml version="1.0" encoding="ISO-8859-1"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Café</dc:title></metadata>
              <manifest><item id="one" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="one"/></spine>
            </package>
        """.trimIndent()
        val document = BoundedXml.parse(input.toByteArray(Charsets.ISO_8859_1).inputStream())
        assertEquals("Café", document.selectFirst("*|title")?.text())
        assertEquals("one", document.selectFirst("*|spine > *|itemref")?.attr("idref"))
    }

    @Test
    fun `doctype is preserved without fetching external resources or expanding entities`() {
        val document = BoundedXml.parse(
            """<!DOCTYPE root SYSTEM "http://127.0.0.1:1/never-fetch.dtd" [<!ENTITY private "expanded">]>
                <root><title>&private;</title></root>
            """.trimIndent(),
        )
        assertEquals("&private;", document.selectFirst("title")?.text())
    }
}
