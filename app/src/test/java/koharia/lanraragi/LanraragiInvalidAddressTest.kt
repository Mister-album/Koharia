package koharia.lanraragi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.mihon.injekt.patchInjekt
import eu.kanade.domain.base.BasePreferences
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import koharia.connection.ConnectionNetworkMonitor
import koharia.connection.LibraryConnectionProfile
import koharia.domain.lanraragi.LanraragiRepository
import koharia.source.lanraragi.LanraragiSource
import koharia.source.lanraragi.LanraragiSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class LanraragiInvalidAddressTest {
    @Test
    fun `invalid configuration produces a terminal address error without touching catalogue or network`() = runTest {
        val previousInjekt = Injekt
        patchInjekt()
        val values = mutableMapOf("internal_address" to "http://192.0.2.5:3000/")
        val preferences = mockk<SharedPreferences> {
            every { getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
        }
        val application = mockk<Application> {
            every { getSharedPreferences("source_42", Context.MODE_PRIVATE) } returns preferences
        }
        val repository = mockk<LanraragiRepository>()
        val monitor = mockk<ConnectionNetworkMonitor> {
            every { available } returns MutableStateFlow(false)
        }
        val downloadedOnly = mockk<Preference<Boolean>> { every { get() } returns false }
        val basePreferences = mockk<BasePreferences> { every { this@mockk.downloadedOnly } returns downloadedOnly }
        Injekt.addSingleton(application)
        Injekt.addSingleton<Json>(Json)
        Injekt.addSingleton(repository)
        Injekt.addSingleton(monitor)
        Injekt.addSingleton(basePreferences)
        Injekt.addSingleton(LanraragiCatalogSyncCoordinator())
        var source: LanraragiSource? = null
        try {
            val active = LanraragiSource(application, LibraryConnectionProfile(42, "lanraragi", "Fixture"))
            source = active
            active.status.value = LanraragiSyncStatus(count = 7, completedAt = 123)
            for (address in listOf("", "invalid-address", "https://fixture.test/?key=dummy")) {
                values["address"] = address
                active.startRefresh(explicit = true)
                val error = active.status.value.error
                assertEquals(LanraragiException.Reason.ADDRESS, (error as LanraragiException).reason)
                assertFalse(active.status.value.running)
                assertEquals(7, active.status.value.count)
                assertEquals(123L, active.status.value.completedAt)
                active.refreshIfStale()
                assertSame(error, active.status.value.error)
                assertSame(error, active.refreshLibrary().exceptionOrNull())
            }
            coVerify { repository wasNot Called }

            // A valid saved configuration can recover while keeping an existing cache and avoiding a fetch.
            values["address"] = "https://fixture.test/"
            coEvery { repository.lastSync(42) } returns 123
            active.refreshIfStale()
            assertNull(active.status.value.error)
            assertFalse(active.status.value.running)
            assertEquals(7, active.status.value.count)
            coVerify(exactly = 1) { repository.lastSync(42) }
            coVerify(exactly = 0) { repository.begin(any(), any()) }
            coVerify(exactly = 0) { repository.abort(any(), any()) }
        } finally {
            source?.close()
            Injekt = previousInjekt
        }
    }
}
