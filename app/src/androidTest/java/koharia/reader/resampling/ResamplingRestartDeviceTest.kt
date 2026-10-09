package koharia.reader.resampling

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.SharedAppPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import tachiyomi.core.common.preference.AndroidPreferenceStore

/** Run write and verify in separate instrumentation processes with the same unique preference name. */
class ResamplingRestartDeviceTest {
    @Test
    fun explicitProcessRestartRetainsAlgorithmAndQuality() {
        val arguments = InstrumentationRegistry.getArguments()
        val name = arguments.getString("resamplingRestartPrefs")
        assumeNotNull(name)
        require(checkNotNull(name).startsWith("interpolation-restart-"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val storage = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val preferences = SharedAppPreferences(
            context.applicationContext as Application,
            AndroidPreferenceStore(context, storage),
        ).readerPreferences()
        when (arguments.getString("resamplingRestartPhase")) {
            "write" -> {
                preferences.moireReduction.set(true)
                preferences.resamplingKernel.set(ResamplingKernel.LANCZOS3)
                preferences.resamplingQuality.set(ResamplingQuality.SPEED)
                assertTrue(storage.edit().putBoolean("written", true).commit())
            }
            "verify" -> try {
                assertTrue(storage.getBoolean("written", false))
                assertTrue(preferences.moireReduction.get())
                assertEquals(ResamplingKernel.LANCZOS3, preferences.resamplingKernel.get())
                assertEquals(ResamplingQuality.SPEED, preferences.resamplingQuality.get())
            } finally {
                context.deleteSharedPreferences(name)
            }
            else -> error("Specify write or verify")
        }
    }
}
