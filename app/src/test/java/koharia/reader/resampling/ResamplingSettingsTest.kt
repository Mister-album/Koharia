package koharia.reader.resampling

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.SessionPreferenceStore

class ResamplingSettingsTest {
    @Test
    fun `legacy thresholds cannot restrict any kernel or change its effective settings`() {
        val legacyThreshold = InMemoryPreferenceStore.InMemoryPreference("reader_moire_reduction_threshold", 50, 50)
        val store = InMemoryPreferenceStore(sequenceOf(legacyThreshold))
        val preferences = ReaderPreferences(store)
        preferences.moireReduction.set(true)
        for (kernel in ResamplingKernel.entries) {
            preferences.resamplingKernel.set(kernel)
            val expected = preferences.resamplingSettings()
            for (threshold in listOf(0, 25, 33, 50, 75, 100)) {
                legacyThreshold.set(threshold)
                assertEquals(expected, preferences.resamplingSettings())
                assertTrue(preferences.resamplingSettings().options.shouldFilter(.75f))
                assertTrue(preferences.resamplingSettings().options.shouldFilter(1.024f))
                assertEquals(threshold, store.getInt("reader_moire_reduction_threshold", 50).get())
            }
        }
    }

    @Test
    fun `unset algorithm uses recommended default without overwriting legacy threshold`() {
        val fresh = ReaderPreferences(InMemoryPreferenceStore())
        assertFalse(fresh.moireReduction.get())
        assertEquals(ResamplingKernel.CATMULL_ROM, fresh.resamplingKernel.get())
        assertEquals(ResamplingQuality.BALANCED, fresh.resamplingQuality.get())
        val store = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("reader_moire_reduction", true, false),
                InMemoryPreferenceStore.InMemoryPreference("reader_moire_reduction_threshold", 75, 50),
            ),
        )
        val preferences = ReaderPreferences(store)
        assertEquals(ResamplingKernel.CATMULL_ROM, preferences.resamplingSettings().kernel)
        assertEquals(75, store.getInt("reader_moire_reduction_threshold", 50).get())
        assertEquals(ResamplingQuality.BALANCED, preferences.resamplingQuality.get())
    }

    @Test
    fun `explicit algorithm and quality survive disabling and reenabling`() {
        for (kernel in ResamplingKernel.entries) {
            for (quality in ResamplingQuality.entries) {
                val preferences = ReaderPreferences(
                    InMemoryPreferenceStore(
                        sequenceOf(
                            InMemoryPreferenceStore.InMemoryPreference("reader_resampling_kernel", kernel, kernel),
                            InMemoryPreferenceStore.InMemoryPreference("reader_resampling_quality", quality, quality),
                            InMemoryPreferenceStore.InMemoryPreference("reader_moire_reduction_threshold", 75, 50),
                        ),
                    ),
                )
                preferences.moireReduction.set(true)
                preferences.moireReduction.set(false)
                preferences.moireReduction.set(true)
                assertEquals(kernel, preferences.resamplingSettings().kernel)
                assertEquals(quality, preferences.resamplingSettings().quality)
            }
        }
    }

    @Test
    fun `session changes remain isolated until persistence is requested`() {
        val store = InMemoryPreferenceStore()
        val session = SessionPreferenceStore(store, false)
        val preferences = ReaderPreferences(session)
        preferences.moireReduction.set(true)
        preferences.resamplingKernel.set(ResamplingKernel.LANCZOS3)
        preferences.resamplingQuality.set(ResamplingQuality.SPEED)
        assertFalse(ReaderPreferences(store).moireReduction.get())
        assertEquals(ResamplingKernel.LANCZOS3, preferences.resamplingSettings().kernel)
        assertEquals(ResamplingKernel.CATMULL_ROM, ReaderPreferences(store).resamplingKernel.get())
    }

    @Test
    fun `quality changes input density without changing kernel or output grid`() {
        for (kernel in ResamplingKernel.entries) {
            for (scale in listOf(.01, .18, .42, .75, 1.0, 1.024, 2.0, 4.0, 10.0)) {
                val plans = ResamplingQuality.entries.map { quality ->
                    RegionResamplingPlan(scale, options = ResamplingOptions(kernel = kernel, quality = quality))
                }
                assertTrue(plans.zipWithNext().all { (a, b) -> a.inputSample >= b.inputSample })
                assertEquals(1, plans.map { it.outputBoundary(1531) }.distinct().size)
                plans.forEach { plan ->
                    assertEquals(kernel, plan.options.kernel)
                    val sample = plan.inputSample
                    assertEquals(1, Integer.bitCount(sample))
                    if (scale >= 1.0) assertEquals(1, sample)
                    if (sample > 1) assertTrue(1.0 / sample >= scale * plan.options.quality.inputDensity)
                    val sourceRadius = kernel.radius * maxOf(sample.toDouble(), 1.0 / scale)
                    assertTrue(plan.inputStart(10000) <= 10000 / scale - sourceRadius)
                    assertTrue(plan.inputEnd(10000, Int.MAX_VALUE) >= 10000 / scale + sourceRadius)
                }
            }
        }
    }

    @Test
    fun `irrelevant settings do not invalidate rendered images`() {
        assertEquals(
            ReaderResamplingSettings.resolve(false, ResamplingKernel.MITCHELL, ResamplingQuality.BALANCED),
            ReaderResamplingSettings.resolve(false, ResamplingKernel.LANCZOS3, ResamplingQuality.DETAIL),
        )
    }
}
