package koharia.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionEntryOpeningAdapter
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import koharia.source.local.LocalFolderSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class StorageModeContractTest {
    @Test fun everyModeUsesLocalProviderAndSharedSeriesAndSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val provider = Injekt.get<ConnectionRegistry>().availableProviders().single { it.id == "local-folder" }
        for (mode in LibraryStorageMode.entries) {
            val id = -System.nanoTime()
            val preferences = NetworkStoragePreferences(id)
            preferences.save(NetworkStorageConfiguration(mode = mode), "", "")
            val profile = LibraryConnectionProfile(id, provider.id, "Storage mode fixture")
            val source = provider.createSource(profile)
            try {
                assertTrue(source is LocalFolderSource)
                source as LocalFolderSource
                assertNotNull(provider.createSettingsScreen(profile))
                assertTrue(source.mangaBehavior.supportsChapterCoverGrid)
                assertEquals(mode != LibraryStorageMode.LOCAL, source.mangaBehavior.allowsChapterDownloads)
                assertEquals(mode != LibraryStorageMode.LOCAL, source.supportsFileTransfers)
                assertEquals(2, (source as ConnectionEntryOpeningAdapter).entryOpeningSettings().size)
            } finally {
                (source as? AutoCloseable)?.close()
                sourcePreferences("source_$id").edit().clear().commit()
            }
        }
    }
}
