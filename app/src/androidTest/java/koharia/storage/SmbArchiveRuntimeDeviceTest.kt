package koharia.storage

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.SmbArchiveRuntimeFixture
import org.junit.Test

class SmbArchiveRuntimeDeviceTest {
    @Test
    fun registeredNetworkRootReadsArchiveThroughProxyDescriptorAndReopensCache() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        SmbArchiveRuntimeFixture.run(
            instrumentation.targetContext,
            checkNotNull(args.getString("smbArchiveAddress")),
            checkNotNull(args.getString("smbArchiveImageHash")),
        ) { status ->
            instrumentation.sendStatus(0, Bundle().apply { putString("smb_archive_runtime", status) })
        }
    }
}
