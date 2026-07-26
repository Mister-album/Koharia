package koharia.epub.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.ReadingProgression as PublicationReadingProgression

class EpubPreferencesBridgeTest {

    @Test
    fun `start alignment disables Readium hyphenation`() {
        val preferences = textAlignmentPreferences(EpubLayoutPreferences.TextAlignment.START)

        assertEquals(TextAlign.START, preferences.textAlign)
        assertEquals(false, preferences.hyphens)
    }

    @Test
    fun `justified alignment enables Readium hyphenation`() {
        val preferences = textAlignmentPreferences(EpubLayoutPreferences.TextAlignment.JUSTIFY)

        assertEquals(TextAlign.JUSTIFY, preferences.textAlign)
        assertEquals(true, preferences.hyphens)
    }

    @Test
    fun `physical edge alignments are submitted without hyphenation`() {
        val left = textAlignmentPreferences(EpubLayoutPreferences.TextAlignment.LEFT)
        val right = textAlignmentPreferences(EpubLayoutPreferences.TextAlignment.RIGHT)

        assertEquals(TextAlign.LEFT, left.textAlign)
        assertEquals(false, left.hyphens)
        assertEquals(TextAlign.RIGHT, right.textAlign)
        assertEquals(false, right.hyphens)
    }

    @Test
    fun `publisher styles omit saved alignment and hyphenation overrides`() {
        val preferences = textAlignmentPreferences(
            textAlignment = EpubLayoutPreferences.TextAlignment.JUSTIFY,
            publisherStyles = true,
        )

        assertEquals(null, preferences.textAlign)
        assertEquals(null, preferences.hyphens)
    }

    @Test
    fun `left to right mode is submitted to Readium`() {
        val progression = EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT.toReadiumReadingProgression()

        assertEquals(ReadingProgression.LTR, progression)
    }

    @Test
    fun `right to left mode is submitted to Readium`() {
        val progression = EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT.toReadiumReadingProgression()

        assertEquals(ReadingProgression.RTL, progression)
    }

    @Test
    fun `right to left pagination keeps CJK text horizontal`() {
        val preferences = resolveReadiumFlowPreferences(
            readingMode = EpubLayoutPreferences.ReadingMode.PAGINATED,
            pageDirection = EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT,
        )

        assertEquals(ReadingProgression.RTL, preferences.readingProgression)
        assertEquals(false, preferences.scroll)
        assertEquals(false, preferences.verticalText)
    }

    @Test
    fun `continuous scroll keeps CJK text horizontal after right to left pagination`() {
        val preferences = resolveReadiumFlowPreferences(
            readingMode = EpubLayoutPreferences.ReadingMode.SCROLL,
            pageDirection = EpubLayoutPreferences.PageDirection.RIGHT_TO_LEFT,
        )

        assertEquals(ReadingProgression.RTL, preferences.readingProgression)
        assertEquals(true, preferences.scroll)
        assertEquals(false, preferences.verticalText)
    }

    @Test
    fun `native CJK vertical publication keeps its original direction when not overridden`() {
        val preferences = resolveReadiumFlowPreferences(
            readingMode = EpubLayoutPreferences.ReadingMode.PAGINATED,
            pageDirection = EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT,
            pageDirectionExplicit = false,
            publicationMetadata = Metadata(
                languages = listOf("ja"),
                readingProgression = PublicationReadingProgression.RTL,
            ),
        )

        assertEquals(ReadingProgression.RTL, preferences.readingProgression)
        assertEquals(true, preferences.verticalText)
    }

    @Test
    fun `explicit LTR overrides native CJK vertical publication`() {
        val preferences = resolveReadiumFlowPreferences(
            readingMode = EpubLayoutPreferences.ReadingMode.PAGINATED,
            pageDirection = EpubLayoutPreferences.PageDirection.LEFT_TO_RIGHT,
            pageDirectionExplicit = true,
            publicationMetadata = Metadata(
                languages = listOf("ja"),
                readingProgression = PublicationReadingProgression.RTL,
            ),
        )

        assertEquals(ReadingProgression.LTR, preferences.readingProgression)
        assertEquals(false, preferences.verticalText)
    }

    private fun textAlignmentPreferences(
        textAlignment: EpubLayoutPreferences.TextAlignment,
        publisherStyles: Boolean = false,
    ) = resolveReadiumTextAlignmentPreferences(
        textAlignment = textAlignment,
        publisherStyles = publisherStyles,
    )
}
