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
