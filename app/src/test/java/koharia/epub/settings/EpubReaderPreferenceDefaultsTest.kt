package koharia.epub.settings

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
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
}
