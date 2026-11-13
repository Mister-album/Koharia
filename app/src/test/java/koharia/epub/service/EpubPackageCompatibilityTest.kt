package koharia.epub.service

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.xml.parsers.DocumentBuilderFactory

class EpubPackageCompatibilityTest {
    @Test
    fun `repair misplaced xhtml namespace only on package manifest items`() {
        val input = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata><foreign xmlns="http://www.w3.org/1999/xhtml"><item id="untouched"/></foreign></metadata>
              <manifest>
                <item xmlns="http://www.w3.org/1999/xhtml" id="one" href="one.xhtml" media-type="application/xhtml+xml"/>
                <item id="two" href="two.xhtml" media-type="application/xhtml+xml"/>
                <item xmlns="urn:custom" id="extension"/>
              </manifest>
              <spine><itemref idref="one"/><itemref idref="two"/></spine>
            </package>
        """.trimIndent().toByteArray()
        val normalized = input.normalizeEpubPackageNamespaces()
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(normalized.inputStream())
        assertEquals(2, document.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "item").length)
        assertEquals(1, document.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "item").length)
        assertEquals(1, document.getElementsByTagNameNS("urn:custom", "item").length)
        assertArrayEquals(normalized, normalized.normalizeEpubPackageNamespaces())
    }

    @Test
    fun `valid packages and unrelated namespace documents retain original bytes`() {
        for (input in listOf(
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest><item id="a"/></manifest></package>""",
            """<package xmlns="urn:custom"><manifest><item xmlns="http://www.w3.org/1999/xhtml"/></manifest></package>""",
            """<package xmlns="http://www.idpf.org/2007/opf"><manifest xmlns="urn:custom"><item/></manifest></package>""",
        )) {
            assertArrayEquals(input.toByteArray(), input.toByteArray().normalizeEpubPackageNamespaces())
        }
    }
}
