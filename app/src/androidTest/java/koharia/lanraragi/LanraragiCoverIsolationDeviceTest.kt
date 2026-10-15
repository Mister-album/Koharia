package koharia.lanraragi

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.LanraragiCoverIsolationFixture
import org.junit.Test

class LanraragiCoverIsolationDeviceTest {
    @Test
    fun sameUrlFavoritesKeepAccountCoversWhenSwitchingAndDeleting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LanraragiCoverIsolationFixture.run(instrumentation.targetContext) { status ->
            instrumentation.sendStatus(2, Bundle().apply { putString("cover_stage", status) })
        }
    }

    @Test
    fun cleanupKeepsOtherOwnersAndDoesNotAdoptLegacyFiles() {
        LanraragiCoverIsolationFixture.verifyCleanup(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}
