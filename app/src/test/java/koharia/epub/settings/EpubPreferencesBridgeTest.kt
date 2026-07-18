package koharia.epub.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.readium.r2.navigator.preferences.ReadingProgression

class EpubPreferencesBridgeTest {

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
}
