package koharia.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

class EpubLocatorProgressStabilityTest {

    @Test
    fun `provisional locator keeps restored progress metrics for same resource`() {
        val restored = locator(
            href = "item/xhtml/p-003.xhtml",
            progression = 0.25,
            position = 21,
            totalProgression = 0.05865102639296188,
        )
        val provisional = locator(
            href = "https://readium_package/item/xhtml/p-003.xhtml",
            progression = 0.25,
            position = 13,
            totalProgression = 0.32432432432432434,
        )

        val stable = provisional.preserveProgressMetricsFrom(restored)

        assertEquals(0.25, stable.locations.progression)
        assertEquals(21, stable.locations.position)
        assertEquals(0.05865102639296188, stable.locations.totalProgression)
    }

    @Test
    fun `locator for another resource is not rewritten`() {
        val restored = locator("item/xhtml/p-003.xhtml", 0.25, 21, 0.058)
        val provisional = locator("item/xhtml/p-004.xhtml", 0.0, 27, 0.35)

        val stable = provisional.preserveProgressMetricsFrom(restored)

        assertSame(provisional, stable)
    }

    @Test
    fun `resources with the same filename in different directories stay distinct`() {
        val restored = locator("chapter.xhtml", 0.25, 21, 0.058)
        val provisional = locator("appendix/chapter.xhtml", 0.25, 27, 0.35)

        val stable = provisional.preserveProgressMetricsFrom(restored)

        assertSame(provisional, stable)
    }

    @Test
    fun `known served resource prefix maps to publication resource`() {
        val restored = locator("item/chapter.xhtml", 0.25, 21, 0.058)
        val served = locator(
            "https://example.invalid/books/1/resource/item/chapter.xhtml",
            0.25,
            13,
            0.32,
        )

        val stable = served.preserveProgressMetricsFrom(restored)

        assertEquals(21, stable.locations.position)
        assertEquals(0.058, stable.locations.totalProgression)
    }

    @Test
    fun `authoritative positions replace provisional global metrics`() {
        val provisional = locator("item/chapter.xhtml", 0.5, 99, 0.99)
        val authoritative = locator("item/chapter.xhtml", 0.5, 11, 0.2)

        val aligned = provisional.alignToEpubPositions(listOf(authoritative))

        assertEquals(0.5, aligned.locations.progression)
        assertEquals(11, aligned.locations.position)
        assertEquals(0.2, aligned.locations.totalProgression)
    }

    @Test
    fun `locator without previous progress is not rewritten`() {
        val provisional = locator("item/xhtml/p-003.xhtml", 0.25, 13, 0.324)

        val stable = provisional.preserveProgressMetricsFrom(null)

        assertSame(provisional, stable)
    }

    private fun locator(
        href: String,
        progression: Double,
        position: Int,
        totalProgression: Double,
    ): Locator = Locator(
        href = checkNotNull(Url(href)),
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(
            progression = progression,
            position = position,
            totalProgression = totalProgression,
        ),
    )
}
