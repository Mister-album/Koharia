package koharia.lanraragi

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.LanraragiAutomaticDownloadFixture
import org.junit.Test

class LanraragiAutomaticDownloadDeviceTest {
    @Test
    fun explicitRefreshKeepsNotificationsAndDownloadsReadablePages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        LanraragiAutomaticDownloadFixture.run(instrumentation.targetContext) { status ->
            instrumentation.sendStatus(2, Bundle().apply { putString("automatic_stage", status) })
        }
    }
}
