package koharia.connection

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import koharia.testing.RegistryMigrationRuntimeFixture
import org.junit.Test

class RegistryMigrationRuntimeDeviceTest {
    @Test
    fun registeredProfilesAndIsolatedDatabaseMigrationSurviveShrinking() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        RegistryMigrationRuntimeFixture.run(instrumentation.targetContext) { status ->
            instrumentation.sendStatus(0, Bundle().apply { putString("registry_migration_runtime", status) })
        }
    }
}
