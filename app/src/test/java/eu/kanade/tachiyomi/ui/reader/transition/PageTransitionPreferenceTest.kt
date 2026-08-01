package eu.kanade.tachiyomi.ui.reader.transition

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import koharia.epub.settings.EpubLayoutPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class PageTransitionPreferenceTest {

    @Test
    fun `transition values are stable and unique`() {
        assertEquals(0, PageTransitionEffect.SLIDE.value)
        assertEquals(6, PageTransitionEffect.NONE.value)
        assertEquals(PageTransitionEffect.entries.size, PageTransitionEffect.entries.map { it.value }.toSet().size)
        assertTrue(PageTransitionEffect.entries.all { PageTransitionEffect.fromPreference(it.value) == it })
    }

    @Test
    fun `legacy enabled transition migrates to slide and smooth scrolling`() {
        val store = legacyTransitionStore(true)

        val preferences = ReaderPreferences(store)

        assertEquals(PageTransitionEffect.SLIDE.value, preferences.pagerPageTransitionEffect.get())
        assertTrue(preferences.webtoonSmoothScroll.get())
        assertTrue(preferences.pagerPageTransitionEffect.isSet())
        assertTrue(preferences.webtoonSmoothScroll.isSet())
    }

    @Test
    fun `legacy disabled transition migrates to no animation`() {
        val store = legacyTransitionStore(false)

        val preferences = ReaderPreferences(store)

        assertEquals(PageTransitionEffect.NONE.value, preferences.pagerPageTransitionEffect.get())
        assertEquals(false, preferences.webtoonSmoothScroll.get())
    }

    @Test
    fun `comic and EPUB transition preferences are independent`() {
        val store = InMemoryPreferenceStore()
        val comic = ReaderPreferences(store)
        val epub = EpubLayoutPreferences(store)

        comic.pagerPageTransitionEffect.set(PageTransitionEffect.COVER.value)
        epub.pageTransitionEffect.set(PageTransitionEffect.FADE.value)

        assertNotEquals(comic.pagerPageTransitionEffect.key(), epub.pageTransitionEffect.key())
        assertEquals(PageTransitionEffect.COVER.value, comic.pagerPageTransitionEffect.get())
        assertEquals(PageTransitionEffect.FADE.value, epub.pageTransitionEffect.get())
    }

    @Test
    fun `page turn origin is normalized without losing its cause`() {
        val origin = PageTurnOrigin(-0.2f, 1.4f, PageTurnCause.TAP).normalized()

        assertEquals(0f, origin.xFraction)
        assertEquals(1f, origin.yFraction)
        assertEquals(PageTurnCause.TAP, origin.cause)
    }

    private fun legacyTransitionStore(enabled: Boolean) = InMemoryPreferenceStore(
        sequenceOf(
            InMemoryPreference(
                key = "pref_enable_transitions_key",
                data = enabled,
                defaultValue = true,
            ),
        ),
    )
}
