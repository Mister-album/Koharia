package koharia.connection

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.LibraryUpdateRetirementFixture
import org.junit.Test

class LibraryUpdateRetirementDeviceTest {
    @Test
    fun upgradeAndRestoredIntervalsNeverRecreatePolling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LibraryUpdateRetirementFixture.run(instrumentation.targetContext) { status ->
            instrumentation.sendStatus(2, Bundle().apply { putString("retirement_stage", status) })
        }
    }
}
