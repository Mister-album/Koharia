package koharia.lanraragi

import androidx.test.ext.junit.runners.AndroidJUnit4
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.ui.more.MoreScreenModel
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
    fun addingSecondUnconfiguredServerWhileMoreScreenObservesConnectionsDoesNotCrash() = runBlocking(Dispatchers.IO) {
        val manager: ConnectionProfileManager = Injekt.get()
        val preferences: ConnectionPreferences = Injekt.get()
        val sources: SourceManager = Injekt.get()
        val originalActive = preferences.activeConnectionId.get()
        val created = mutableListOf<Long>()
        var model: MoreScreenModel? = null
        try {
            val first = manager.add(LanraragiConnectionProvider.ID, "Draft regression first")
            created += first.id
            preferences.activeConnectionId.set(first.id)
            model = MoreScreenModel()
            model.refreshUser().join()
            val second = manager.add(LanraragiConnectionProvider.ID, "Draft regression second")
            created += second.id
            preferences.activeConnectionId.set(second.id)
            val source = withTimeout(10_000) {
                while (sources.get(second.id) !is LanraragiSource) delay(50)
                sources.get(second.id) as LanraragiSource
            }
            assertFalse(source.hasValidConnection())
            assertNull(source.getAccount())
            delay(100)
            model.refreshUser().join()
            assertNull(model.user.value)
            LanraragiPreferences(second.id).save("invalid-address", "")
            assertNull(source.getAccount())
            model.refreshUser().join()
            assertNull(model.user.value)
            preferences.activeConnectionId.set(first.id)
            delay(100)
            model.refreshUser().join()
            assertNull(model.user.value)
        } finally {
            model?.screenModelScope?.cancel()
            if (manager.profiles().any { it.id == originalActive }) preferences.activeConnectionId.set(originalActive)
            created.asReversed().forEach { manager.remove(it).getOrThrow() }
        }
    }
}
