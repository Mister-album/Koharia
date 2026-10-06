package koharia.connection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Uses the production registry so newly registered providers cannot evade shared entry contracts. */
@RunWith(AndroidJUnit4::class)
class ConnectionProviderContractTest {
    @Test
    fun registeredProvidersExposeSettingsBrowseAndApplicableSeriesControls(): Unit = runBlocking {
        assertEquals(
            "app.koharia.dev.devicefixture",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
        val providers = Injekt.get<ConnectionRegistry>().availableProviders()
        val expected = setOf("komga", "local-folder", "lanraragi", "smanga", "kavita", "suwayomi")
        assertEquals(
            "Update the contract inventory when registration changes",
            expected,
            providers.map {
                it.id
            }.toSet(),
        )
        for ((index, provider) in providers.withIndex()) {
            val profile = LibraryConnectionProfile(Long.MIN_VALUE + index, provider.id, "Contract fixture")
            assertNotNull(provider.id, provider.createSettingsScreen(profile))
            val source = provider.createSource(profile)
            try {
                assertEquals(profile.id, source.id)
                assertEquals(profile, source.connectionProfile)
                assertTrue(provider.id, source is ConnectionBrowseAdapter)
                assertTrue(provider.id, source is ConnectionMangaBehaviorAdapter)
                assertTrue(
                    provider.id,
                    (source as ConnectionMangaBehaviorAdapter).mangaBehavior.supportsChapterCoverGrid,
                )
                val browse = source as ConnectionBrowseAdapter
                val entrySettings = (source as? ConnectionEntryOpeningAdapter)?.entryOpeningSettings().orEmpty()
                when (provider.id) {
                    "komga" -> {
                        assertEquals(1, entrySettings.size)
                        assertEquals(EntryOpenMode.entries, entrySettings.single().modes)
                        assertTrue(
                            entrySettings.single().preference.key().endsWith("entry_open_mode_komga_single_book"),
                        )
                    }
                    "local-folder" -> {
                        assertEquals(2, entrySettings.size)
                        assertEquals(EntryOpenMode.entries, entrySettings.first().modes)
                        assertEquals(listOf(EntryOpenMode.READER, EntryOpenMode.DETAILS), entrySettings.last().modes)
                    }
                    "lanraragi" -> {
                        assertEquals(1, entrySettings.size)
                        assertEquals("archive_open_mode", entrySettings.single().preference.key())
                        assertEquals(EntryOpenMode.entries, entrySettings.single().modes)
                    }
                    // These providers currently open standard series details directly.
                    "smanga", "kavita", "suwayomi" -> assertTrue(entrySettings.isEmpty())
                    else -> error("Review entry-opening behavior for ${provider.id}")
                }
                if (provider.id != "local-folder") {
                    assertTrue(provider.id, source is HttpSource)
                    assertTrue(provider.id, source is ConnectionDownloadStorageAdapter)
                    val allowsDownloads =
                        (source as ConnectionMangaBehaviorAdapter).mangaBehavior.allowsChapterDownloads
                    if (source is koharia.source.kavita.KavitaSource) {
                        assertEquals(source.preferences.capabilities.downloads, allowsDownloads)
                    } else {
                        assertTrue(provider.id, allowsDownloads)
                    }
                    assertTrue(provider.id, browse.seriesSettingsAvailable().first())
                }
            } finally {
                (source as? AutoCloseable)?.close()
            }
        }
    }
}
