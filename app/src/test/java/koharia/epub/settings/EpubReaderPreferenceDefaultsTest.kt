package koharia.epub.settings

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class EpubReaderPreferenceDefaultsTest {

    @Test
    fun `volume keys default on only for EPUB`() {
        val store = InMemoryPreferenceStore()
        val epubPreference = EpubLayoutPreferences(store).readWithVolumeKeys
        val comicPreference = ReaderPreferences(store).readWithVolumeKeys

        assertTrue(epubPreference.get())
        assertFalse(comicPreference.get())
        assertNotEquals(epubPreference.key(), comicPreference.key())
    }

    @Test
    fun `EPUB typography defaults to standard spacing with publisher styles`() {
        val preferences = EpubLayoutPreferences(InMemoryPreferenceStore())

        assertEquals(EpubLayoutPreferences.SpacingMode.STANDARD, preferences.spacingMode.get())
        assertEquals(1.7f, preferences.lineHeight.get())
        assertEquals(0.05f, preferences.paragraphSpacing.get())
        assertTrue(preferences.publisherStyles.get())
    }

    @Test
    fun `legacy free global orientation is exposed as default`() {
        val store = InMemoryPreferenceStore()
        store.getInt("pref_default_orientation_type_key").set(ReaderOrientation.FREE.flagValue)

        val orientation = ReaderPreferences(store).defaultOrientationType

        assertEquals(ReaderOrientation.DEFAULT.flagValue, orientation.get())
        orientation.set(ReaderOrientation.FREE.flagValue)
        assertEquals(ReaderOrientation.DEFAULT.flagValue, orientation.get())
    }
}
