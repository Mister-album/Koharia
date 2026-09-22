package koharia.lanraragi

import androidx.test.ext.junit.runners.AndroidJUnit4
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class LanraragiDraftConnectionDeviceTest {
    @Test
    fun addingSecondUnconfiguredServerKeepsDraftAccountAccessSafe() = runBlocking(Dispatchers.IO) {
        val manager: ConnectionProfileManager = Injekt.get()
        val preferences: ConnectionPreferences = Injekt.get()
        val sources: SourceManager = Injekt.get()
        val originalActive = preferences.activeConnectionId.get()
        val created = mutableListOf<Long>()
        try {
            val first = manager.add(LanraragiConnectionProvider.ID, "Draft regression first")
            created += first.id
            preferences.activeConnectionId.set(first.id)
            val second = manager.add(LanraragiConnectionProvider.ID, "Draft regression second")
            created += second.id
            preferences.activeConnectionId.set(second.id)
            val source = withTimeout(10_000) {
                while (sources.get(second.id) !is LanraragiSource) delay(50)
                sources.get(second.id) as LanraragiSource
            }
            assertFalse(source.hasValidConnection())
            assertNull(source.getAccount())
            LanraragiPreferences(second.id).save("invalid-address", "")
            assertNull(source.getAccount())
        } finally {
            if (manager.profiles().any { it.id == originalActive }) preferences.activeConnectionId.set(originalActive)
            created.asReversed().forEach { manager.remove(it).getOrThrow() }
        }
    }
}
