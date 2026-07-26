package koharia.epub.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.data.EmptyContainer
import org.readium.r2.shared.util.resource.Resource

class EpubXhtmlCompatibilityTest {

    @Test
    fun `publication compatibility installation is idempotent`() {
        val builder = Publication.Builder(
            manifest = Manifest(metadata = Metadata()),
            container = EmptyContainer<Resource>(),
        )

        builder.installEpubXhtmlCompatibility()
        val installedContainer = builder.container
        builder.installEpubXhtmlCompatibility()

        assertSame(installedContainer, builder.container)
    }

    @Test
    fun `valueless image alt is repaired for strict xhtml parsing`() {
        val source = "<p><img src=\"../image/p019.jpg\" class=\"fit\" alt /></p>"

        val result = source.normalizeEpubXhtmlForCompatibility()

        assertEquals(
            "<p><img src=\"../image/p019.jpg\" class=\"fit\" alt=\"\" /></p>",
            result.content,
        )
        assertEquals(1, result.repairedAttributes)
    }

    @Test
    fun `multiple html boolean attributes are repaired`() {
        val source = "<input disabled checked data-ready />"

        val result = source.normalizeEpubXhtmlForCompatibility()

        assertEquals(
            "<input disabled=\"\" checked=\"\" data-ready=\"\" />",
            result.content,
        )
        assertEquals(3, result.repairedAttributes)
    }

    @Test
    fun `valid quoted and namespaced attributes are preserved`() {
        val source = "<html xmlns=\"http://www.w3.org/1999/xhtml\" epub:type=\"chapter\"><p>Text</p></html>"

        val result = source.normalizeEpubXhtmlForCompatibility()

        assertEquals(source, result.content)
        assertEquals(0, result.repairedAttributes)
    }

    @Test
    fun `attribute shaped text inside quotes comments and cdata is not changed`() {
        val source =
            "<p title=\"keep alt /> here\">Text</p>" +
                "<!-- <img alt /> -->" +
                "<![CDATA[<img alt />]]>"

        val result = source.normalizeEpubXhtmlForCompatibility()

        assertEquals(source, result.content)
        assertEquals(0, result.repairedAttributes)
    }

    @Test
    fun `processing instructions and doctypes are preserved`() {
        val source =
            "<?xml version=\"1.0\"?>" +
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"xhtml11.dtd\">" +
                "<html><body /></html>"

        val result = source.normalizeEpubXhtmlForCompatibility()

        assertEquals(source, result.content)
        assertEquals(0, result.repairedAttributes)
    }
}
