package koharia.storage

import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.ConnectionProfileManager
import koharia.connection.ConnectionRegistry
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.NetworkStorageDraft
import koharia.source.local.saveNetworkLibraryDraft
import koharia.source.local.withInitialBookshelves
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID

class NetworkStorageRefreshDeviceTest {
    @Test fun freshNestedRootsRefreshAndSmbSurvivesIdleAndClosedShare() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val manager = Injekt.get<ConnectionProfileManager>()
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            val server = if (mode ==
                LibraryStorageMode.SMB
            ) {
                "smb://127.0.0.1:18445/library"
            } else {
                "http://127.0.0.1:18765/"
            }
            val base = NetworkStorageConfiguration(mode = mode, address = server)
            val folder = "refresh-${UUID.randomUUID()}"
            val parent = NetworkStorageRuntime.backend(base, server, "", "")
            parent.createDirectory(folder)
            val profile = manager.add("local-folder", "Refresh fixture")
            try {
                val draft = NetworkStorageDraft(base, "", "").withRoot(
                    if (mode == LibraryStorageMode.SMB) "library/$folder" else folder,
                )
                val saved = saveNetworkLibraryDraft(
                    context,
                    profile.id,
                    draft,
                    base,
                    LocalLibraryConfig().withInitialBookshelves("Comics", "Books"),
                    emptyMap(),
                    true,
                )
                assertEquals(setOf("Comics", "Books"), saved.roots.map { it.relativePath }.toSet())
                val provider = Injekt.get<ConnectionRegistry>().availableProviders().single { it.id == "local-folder" }
                val source = provider.createSource(profile) as LocalFolderSource
                source.refreshLibrary().getOrThrow()
                val local = LocalLibraryPreferences(profile.id, Injekt.get<Json>())
                assertEquals(2, local.rootDirectories(context).size)
                source.refreshLibrary().getOrThrow()
                if (mode == LibraryStorageMode.SMB) {
                    delay(22_000)
                    source.refreshLibrary().getOrThrow()
                    SmbStorageBackend(draft.configuration.address, "", "", "").use { backend ->
                        assertTrue(backend.stat("").directory)
                        val field = SmbStorageBackend::class.java.getDeclaredField("disk").apply { isAccessible = true }
                        (field.get(backend) as com.hierynomus.smbj.share.DiskShare).close()
                        assertTrue(backend.stat("").directory)
                        assertTrue(backend.list("").any { it.path == "Comics" })
                    }
                }
            } finally {
                manager.remove(profile.id).getOrThrow()
                // Only descend into the unique directory created by this test.
                suspend fun removeOwned(path: String) {
                    parent.list(path).forEach { if (it.directory) removeOwned(it.path) else parent.delete(it) }
                    parent.delete(parent.stat(path))
                }
                removeOwned(folder)
                parent.close()
            }
        }
    }
}
