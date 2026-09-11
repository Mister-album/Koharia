package koharia.lanraragi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import koharia.connection.ConnectionProfileManager
import koharia.lanraragi.ui.LanraragiLibraryScreenModel
import koharia.source.lanraragi.LanraragiConnectionProvider
import koharia.source.lanraragi.LanraragiPreferences
import koharia.source.lanraragi.LanraragiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class LanraragiBrowseDeviceTest {
    @Test
    fun searchSelectsServerAutomaticallyAndFallsBackWithoutDiscardingCompleteCache() = runBlocking(Dispatchers.IO) {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val sourceManager = Injekt.get<SourceManager>()
        val profile = Injekt.get<ConnectionProfileManager>().add(LanraragiConnectionProvider.ID, "Browse regression")
        LanraragiPreferences(profile.id).save("http://127.0.0.1:38709/v80/lrr/", "fixture-key")
        val source = withTimeout(10_000) {
            while (sourceManager.get(profile.id) !is LanraragiSource) delay(100)
            sourceManager.get(profile.id) as LanraragiSource
        }
        val base = Injekt.get<BasePreferences>()
        val oldDownloaded = base.downloadedOnly.get()
        base.downloadedOnly.set(false)
        control(false)
        source.refreshLibrary().getOrThrow()
        val original = source.repository.entries(source.id)
        val model = LanraragiLibraryScreenModel(source, "server-only")
        try {
            withTimeout(15_000) { while (model.state.value.advancedResults == null) delay(100) }
            assertEquals(setOf("0000000000000000000000000000000000000005"), model.state.value.advancedResults)
            model.search("not-in-server")
            withTimeout(15_000) { while (model.state.value.advancedResults == null) delay(100) }
            assertTrue(model.state.value.advancedResults!!.isEmpty())
            control(true)
            model.search("Fixture book 1")
            delay(400)
            withTimeout(15_000) { while (model.state.value.searching) delay(100) }
            assertNull(model.state.value.advancedResults)
            assertNull(model.state.value.error)
            assertEquals(
                listOf("Fixture book 1"),
                filterLanraragiCatalog(original, emptyList(), model.state.value.filter.copy(grouped = false))
                    .map { it.title },
            )
            val lastSuccessful = source.repository.lastSync(source.id)
            assertTrue(source.refreshLibrary().isFailure)
            assertEquals(lastSuccessful, source.repository.lastSync(source.id))
            assertEquals(original, source.repository.entries(source.id))

            source.networkAvailable.value = false
            model.search("server-only")
            delay(350)
            assertNull(model.state.value.advancedResults)
            control(false)
            source.networkAvailable.value = true
            withTimeout(15_000) { while (model.state.value.advancedResults == null) delay(100) }
            assertNotNull(model.state.value.advancedResults)
            model.setToolbarQuery("server-only")
            model.exitSearch()
            delay(400)
            assertNull(model.state.value.advancedResults)
            assertNull(model.state.value.toolbarQuery)
            model.refresh()
            withTimeout(15_000) { while (source.repository.lastSync(source.id) <= lastSuccessful) delay(100) }
            assertEquals(original, source.repository.entries(source.id))
        } finally {
            model.screenModelScope.cancel()
            control(false)
            base.downloadedOnly.set(oldDownloaded)
        }
    }

    private fun control(offline: Boolean) {
        val request = Request.Builder().url(
            "http://127.0.0.1:38709/_fixture/control?version=v80&offline=$offline",
        ).build()
        OkHttpClient().newCall(request).execute().use { assertTrue(it.isSuccessful) }
    }
}
