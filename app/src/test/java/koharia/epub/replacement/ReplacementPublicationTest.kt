package koharia.epub.replacement

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import koharia.domain.textreplacement.EffectiveReplacementRule
import koharia.domain.textreplacement.EffectiveRuleSet
import koharia.domain.textreplacement.ReplacementRule
import koharia.domain.textreplacement.ReplacementScope
import koharia.epub.locator.replacementCompatibleHref
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource

class ReplacementPublicationTest {
    @Test
    fun `evicted mapping is rebuilt from original resource and remains pinned during scanning`() = runTest {
        val session = replacement()
        val bytes = "<html><body><p id='p'>old tail</p></body></html>".toByteArray()
        session.transform(bytes, "visible.xhtml", false)
        repeat(10) { session.transform(bytes, "chapter-$it.xhtml", false) }
        val href = mockk<Url>()
        every { href.toString() } returns "visible.xhtml"
        val resource = mockk<Resource>(relaxed = true)
        coEvery { resource.read(any()) } returns Try.success(bytes)
        val publication = mockk<Publication> { every { readingOrder } returns emptyList() }
        var originalReads = 0
        session.originalResource = {
            originalReads++
            resource
        }
        session.ensureMapped(publication, href, active = true)
        repeat(50) { session.transform(bytes, "scanned-$it.xhtml", false) }
        session.ensureMapped(publication, href, active = true)
        assertEquals(1, originalReads)
        val mapped = session.mapLocator(
            Locator(href, MediaType.HTML, text = Locator.Text(highlight = "expanded")),
            toOriginal = true,
        )
        assertEquals("old", mapped.text.highlight)
        assertTrue(session.sample("visible.xhtml").contains("old tail"))
    }

    @Test
    fun `mapped character CFIs are discarded while structural anchors survive`() {
        val href = mockk<Url>()
        every { href.toString() } returns "chapter.xhtml#epubcfi(/6/2:5)"
        val legacy = Locator(href, MediaType.HTML)
        assertEquals("chapter.xhtml#epubcfi(/6/2:5)", legacy.replacementCompatibleHref())
        val mapped = legacy.copy(
            locations = Locator.Locations(otherLocations = mapOf("kohariaTextCoordinates" to "original")),
        )
        assertEquals("chapter.xhtml", mapped.replacementCompatibleHref())
        every { href.toString() } returns "chapter.xhtml#anchor"
        assertEquals("chapter.xhtml#anchor", mapped.replacementCompatibleHref())
    }

    @Test
    fun `deleted bookmark text falls forward to next retained element`() {
        val session = ReplacementPublication(
            EffectiveRuleSet(
                listOf(
                    EffectiveReplacementRule(ReplacementRule(pattern = "old"), ReplacementScope.BOOK),
                ),
            ),
        )
        session.transform(
            "<html><body><p id='gone'>old</p><p id='next'>tail</p></body></html>".toByteArray(),
            "chapter.xhtml",
            false,
        )
        val href = mockk<Url>()
        every { href.toString() } returns "chapter.xhtml"
        val locator = Locator(
            href,
            MediaType.HTML,
            locations = Locator.Locations(
                otherLocations = mapOf(
                    "domRange" to mapOf(
                        "start" to mapOf("cssSelector" to "#gone", "textNodeIndex" to 0, "charOffset" to 1),
                    ),
                ),
            ),
        )
        val mapped = session.mapLocator(locator, toOriginal = false)
        val start = (mapped.locations.otherLocations["domRange"] as Map<*, *>)["start"] as Map<*, *>
        assertEquals("#next", start["cssSelector"])
        assertEquals(0, start["charOffset"])
    }

    @Test
    fun `same filename in a different directory cannot reuse a source map`() {
        val session = replacement()
        session.transform("<html><body><p>old</p></body></html>".toByteArray(), "appendix/chapter.xhtml", false)
        val href = mockk<Url>()
        every { href.toString() } returns "chapter.xhtml"
        val locator = Locator(href, MediaType.HTML, text = Locator.Text(highlight = "expanded"))
        assertEquals(locator, session.mapLocator(locator, toOriginal = true))
    }

    private fun replacement() = ReplacementPublication(
        EffectiveRuleSet(
            listOf(
                EffectiveReplacementRule(
                    ReplacementRule(pattern = "old", replacement = "expanded"),
                    ReplacementScope.BOOK,
                ),
            ),
        ),
    )

    @Test
    fun `display range and quote map back to original and survive reopening`() {
        val resource = "<html><body><p id='p'>old tail</p></body></html>".toByteArray()
        val first = replacement()
        first.transform(resource, "chapter.xhtml", false)
        val href = mockk<Url>()
        every { href.toString() } returns "chapter.xhtml"
        val display = Locator(
            href = href,
            mediaType = MediaType.HTML,
            locations = Locator.Locations(
                otherLocations = mapOf(
                    "domRange" to mapOf(
                        "start" to mapOf(
                            "cssSelector" to "#p",
                            "textNodeIndex" to 0,
                            "charOffset" to 9,
                        ),
                    ),

                ),
            ),
            text = Locator.Text(highlight = "tail"),
        )
        val original = first.mapLocator(display, toOriginal = true)
        val point = (
            original.locations.otherLocations["domRange"] as Map<
                *,
                *,
                >
            )["start"] as Map<
            *,
            *,
            >
        assertEquals(4, point["charOffset"])
        assertEquals("tail", original.text.highlight)
        assertEquals(original, first.mapLocator(original, toOriginal = true))
        val reopened = replacement()
        reopened.transform(resource, "chapter.xhtml", false)
        val mapped = reopened.mapLocator(original, toOriginal = false)
        val restoredPoint = (
            mapped.locations.otherLocations["domRange"] as Map<
                *,
                *,
                >
            )["start"] as Map<
            *,
            *,
            >
        assertEquals(9, restoredPoint["charOffset"])
        assertEquals("tail", mapped.text.highlight)
        assertEquals(mapped, reopened.mapLocator(mapped, toOriginal = false))
    }

    @Test
    fun `UTF16 resources retain encoding and raw bytes are never mutated`() {
        val xml = "<html><body><p>old</p></body></html>"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + xml.toByteArray(Charsets.UTF_16LE)
        val original = bytes.clone()
        val transformed = replacement().transform(bytes, "chapter.xhtml", true)
        assertArrayEquals(original, bytes)
        assertEquals(0xFF.toByte(), transformed.first())
        assertTrue(
            transformed.copyOfRange(
                2,
                transformed.size,
            ).toString(Charsets.UTF_16LE).contains("expanded"),
        )
    }

    @Test
    fun `decoding failure returns complete original and identifies failed resource`() {
        val session = replacement()
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertArrayEquals(bytes, session.transform(bytes, "chapter.xhtml?secret=never-log", true))
        assertEquals(1, session.errors.value.size)
        assertFalse(session.errors.value.single().contains("secret"))
    }

    @Test
    fun `no rules preserve content byte for byte`() {
        val bytes = "<not-even-html>old".toByteArray()
        assertArrayEquals(
            bytes,
            ReplacementPublication(EffectiveRuleSet.EMPTY).transform(
                bytes,
                "a.html",
                false,
            ),
        )
    }
}
