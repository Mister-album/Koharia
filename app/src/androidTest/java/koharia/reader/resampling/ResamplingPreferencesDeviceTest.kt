package koharia.reader.resampling

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import koharia.connection.SharedAppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.SessionPreferenceStore

@RunWith(AndroidJUnit4::class)
class ResamplingPreferencesDeviceTest {
    @Test
    fun unknownValuesSessionsAndReopenedSharedSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val name = "resampling-test-${System.nanoTime()}"
        val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val store = AndroidPreferenceStore(context, storage)
            val shared = SharedAppPreferences(context.applicationContext as Application, store)
            val preferences = shared.readerPreferences()
            assertFalse(preferences.moireReduction.get())
            preferences.moireReduction.set(true)
            shared.store().getInt("reader_moire_reduction_threshold", 50).set(75)
            assertEquals(ResamplingKernel.CATMULL_ROM, shared.readerPreferences().resamplingSettings().kernel)
            assertEquals(ResamplingQuality.BALANCED, shared.readerPreferences().resamplingQuality.get())
            preferences.resamplingKernel.set(ResamplingKernel.MITCHELL)
            assertEquals(ResamplingKernel.MITCHELL, shared.readerPreferences().resamplingSettings().kernel)
            shared.store().getString("reader_resampling_kernel", "").set("future")
            shared.store().getString("reader_resampling_quality", "").set("future")
            assertEquals(ResamplingKernel.MITCHELL, shared.readerPreferences().resamplingKernel.get())
            assertEquals(ResamplingQuality.BALANCED, shared.readerPreferences().resamplingQuality.get())
            assertEquals("future", shared.store().getString("reader_resampling_kernel", "").get())
            val overlay = SessionPreferenceStore(shared.store(), false)
            val session = ReaderPreferences(overlay)
            session.resamplingKernel.set(ResamplingKernel.LANCZOS3)
            session.resamplingQuality.set(ResamplingQuality.SPEED)
            assertEquals(ResamplingKernel.MITCHELL, preferences.resamplingKernel.get())
            overlay.setPersistChanges(true)
            assertEquals(ResamplingKernel.LANCZOS3, shared.readerPreferences().resamplingKernel.get())
            assertEquals(ResamplingQuality.SPEED, shared.readerPreferences().resamplingQuality.get())
            assertEquals(75, shared.store().getInt("reader_moire_reduction_threshold", 50).get())
        } finally {
            context.deleteSharedPreferences(name)
        }
    }
}
