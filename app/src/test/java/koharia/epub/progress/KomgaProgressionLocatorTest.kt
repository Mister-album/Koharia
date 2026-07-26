package koharia.epub.progress

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

class KomgaProgressionLocatorTest {

    @Test
    fun `resource end is aligned to last server position`() {
        val locator = locator("item/chapter.xhtml", progression = 1.0, position = 14)
        val positions = listOf(
            locator("item/chapter.xhtml", progression = 0.0, position = 10),
            locator("item/chapter.xhtml", progression = 0.48, position = 11),
            locator("item/chapter.xhtml", progression = 0.92, position = 12),
            locator("item/next.xhtml", progression = 0.0, position = 13),
        )

        val aligned = locator.alignToKomgaPositions(positions)

        assertEquals(0.92, aligned.locations.progression)
        assertEquals(12, aligned.locations.position)
    }

    @Test
    fun `arbitrary progression uses preceding server position`() {
        val locator = locator("item/chapter.xhtml", progression = 0.7, position = 12)
        val positions = listOf(
            locator("item/chapter.xhtml", progression = 0.2, position = 10),
            locator("item/chapter.xhtml", progression = 0.6, position = 11),
            locator("item/chapter.xhtml", progression = 0.8, position = 12),
        )

        val aligned = locator.alignToKomgaPositions(positions)

        assertEquals(0.6, aligned.locations.progression)
        assertEquals(11, aligned.locations.position)
    }

    @Test
    fun `href aliases with query and fragment share positions`() {
        val locator = locator(
            "https://readium_package/item/chapter.xhtml?cache=1#section",
            progression = 0.75,
            position = 12,
        )
        val positions = listOf(
            locator("item/chapter.xhtml", progression = 0.5, position = 11),
        )

        val aligned = locator.alignToKomgaPositions(positions)

        assertEquals(0.5, aligned.locations.progression)
        assertEquals(11, aligned.locations.position)
    }

    @Test
    fun `exact server position remains unchanged`() {
        val locator = locator("item/chapter.xhtml", progression = 0.5, position = 11)

        val aligned = locator.alignToKomgaPositions(listOf(locator))

        assertSame(locator, aligned)
    }

    @Test
    fun `matching progression still replaces stale global position fields`() {
        val locator = locator("item/chapter.xhtml", progression = 0.5, position = 99)
        val serverPosition = locator("item/chapter.xhtml", progression = 0.5, position = 11)

        val aligned = locator.alignToKomgaPositions(listOf(serverPosition))

        assertEquals(0.5, aligned.locations.progression)
        assertEquals(11, aligned.locations.position)
        assertEquals(0.11, aligned.locations.totalProgression)
    }

    @Test
    fun `same filename in another directory is not a matching resource`() {
        val locator = locator("chapter.xhtml", progression = 0.5, position = 10)
        val positions = listOf(locator("appendix/chapter.xhtml", progression = 0.5, position = 20))

        val aligned = locator.alignToKomgaPositions(positions)

        assertSame(locator, aligned)
    }

    @Test
    fun `locator without matching resource remains unchanged`() {
        val locator = locator("item/chapter.xhtml", progression = 1.0, position = 14)
        val positions = listOf(locator("item/other.xhtml", progression = 0.5, position = 11))

        val aligned = locator.alignToKomgaPositions(positions)

        assertSame(locator, aligned)
    }

    private fun locator(
        href: String,
        progression: Double,
        position: Int,
    ): Locator = Locator(
        href = checkNotNull(Url(href)),
        mediaType = MediaType.XHTML,
        locations = Locator.Locations(
            progression = progression,
            position = position,
            totalProgression = position / 100.0,
        ),
    )
}
