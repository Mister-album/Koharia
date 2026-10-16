package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.ui.reader.transition.PageTurnSensitivity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReaderCommonPreferencesTest {
    @Test
    fun `page turn sensitivity defaults to existing behavior without changing swipe enablement`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())

        assertEquals(PageTurnSensitivity.NORMAL, preferences.pageTurnSensitivity.get())
        assertFalse(preferences.pageTurnSensitivity.isSet())
        preferences.pageTurnSensitivity.set(PageTurnSensitivity.HIGHEST)
        assertEquals(PageTurnSensitivity.HIGHEST, preferences.pageTurnSensitivity.get())
        assertTrue(preferences.swipePageTurns.get())
        preferences.swipePageTurns.set(false)
        assertEquals(PageTurnSensitivity.HIGHEST, preferences.pageTurnSensitivity.get())
    }

    @Test
    fun `reading status keeps the existing visibility key and adds independent common options`() {
        val store = InMemoryPreferenceStore()
        val preferences = ReaderPreferences(store)

        assertTrue(preferences.showPageNumber.get())
        assertEquals(ReaderStatusPosition.BOTTOM, preferences.readerStatusPosition.get())
        assertTrue(preferences.showReaderChapterTitle.get())
        assertTrue(preferences.showReaderClock.get())
        assertTrue(preferences.showReaderBattery.get())
        assertTrue(preferences.showReaderPages.get())
        preferences.showReaderChapterTitle.set(false)
        preferences.showReaderPages.set(false)
        assertFalse(preferences.showReaderChapterTitle.get())
        assertFalse(preferences.showReaderPages.get())
        assertTrue(preferences.showReaderClock.get())
        assertTrue(preferences.showReaderBattery.get())

        preferences.showPageNumber.set(false)
        assertFalse(preferences.showPageNumber.get())
        val existing = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("pref_show_page_number_key", false, false),
            ),
        )
        assertFalse(ReaderPreferences(existing).showPageNumber.get())
    }

    @Test
    fun `comic custom background keeps legacy theme values and stores a selected color`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())

        assertEquals(1, preferences.readerTheme.get())
        preferences.readerCustomBackgroundColor.set(0xFFCCBBAA.toInt())
        preferences.readerTheme.set(ReaderPreferences.CUSTOM_BACKGROUND_THEME)

        assertEquals(ReaderPreferences.CUSTOM_BACKGROUND_THEME, preferences.readerTheme.get())
        assertEquals(0xFFCCBBAA.toInt(), preferences.readerCustomBackgroundColor.get())
    }
}
