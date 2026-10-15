package koharia.lanraragi

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.LanraragiContentIsolationFixture
import org.junit.Test

class LanraragiContentIsolationDeviceTest {
    @Test
    fun cachedPagesRemainOwnedAcrossAccountsAndOfflineReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LanraragiContentIsolationFixture.run(instrumentation.targetContext) { status ->
            instrumentation.sendStatus(2, Bundle().apply { putString("content_stage", status) })
        }
    }
}
