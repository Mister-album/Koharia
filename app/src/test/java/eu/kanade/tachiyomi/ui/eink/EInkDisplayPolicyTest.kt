package eu.kanade.tachiyomi.ui.eink

import eu.kanade.domain.ui.EInkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderEInkPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.transition.PageTransitionEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.presentation.core.motion.EInkDisplayPolicy

class EInkDisplayPolicyTest {

    @Test
    fun `global preferences default to disabled conservative refresh`() {
        val preferences = EInkPreferences(InMemoryPreferenceStore())

        assertFalse(preferences.enabled.get())
        assertFalse(preferences.appRefreshEnabled.get())
        assertEquals(1, preferences.appRefreshInterval.get())
        assertEquals(100, preferences.appRefreshDurationMillis.get())
        assertEquals(EInkPreferences.RefreshColor.BLACK, preferences.appRefreshColor.get())
    }

    @Test
    fun `enabled policy overrides motion without changing stored reader values`() {
        val store = InMemoryPreferenceStore()
        val readerPreferences = ReaderPreferences(store)
        readerPreferences.pagerPageTransitionEffect.set(PageTransitionEffect.CURL.value)
        readerPreferences.webtoonSmoothScroll.set(true)
        readerPreferences.doubleTapAnimSpeed.set(500)
        val policy = EInkDisplayPolicy(enabled = true)

        assertEquals(
            PageTransitionEffect.NONE.value,
            policy.effectivePageTransition(
                readerPreferences.pagerPageTransitionEffect.get(),
                PageTransitionEffect.NONE.value,
            ),
        )
        assertFalse(policy.effectiveSmoothScroll(readerPreferences.webtoonSmoothScroll.get()))
        assertEquals(0, policy.effectiveAnimationDuration(readerPreferences.doubleTapAnimSpeed.get()))
        assertEquals(PageTransitionEffect.CURL.value, readerPreferences.pagerPageTransitionEffect.get())
        assertTrue(readerPreferences.webtoonSmoothScroll.get())
        assertEquals(500, readerPreferences.doubleTapAnimSpeed.get())
    }

    @Test
    fun `reader flash extraction preserves existing preference keys`() {
        val preferences = ReaderEInkPreferences(InMemoryPreferenceStore())

        assertEquals("pref_reader_flash", preferences.flashOnPageChange.key())
        assertEquals("pref_reader_flash_duration", preferences.flashDurationMillis.key())
        assertEquals("pref_reader_flash_interval", preferences.flashPageInterval.key())
        assertEquals("pref_reader_flash_mode", preferences.flashColor.key())
    }
}
