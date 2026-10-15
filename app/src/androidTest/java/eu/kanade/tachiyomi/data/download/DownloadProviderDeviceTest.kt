package eu.kanade.tachiyomi.data.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import koharia.connection.ConnectionDownloadStorageAdapter
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionRegistry
import koharia.connection.LibraryConnectionProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadProviderDeviceTest {
    @Test
    fun registeredProvidersRetainDownloadedFilesAcrossCacheInvalidation() = withRoot { provider, downloads ->
        val registered = Injekt.get<ConnectionRegistry>().availableProviders()
        assertEquals(
            setOf("komga", "kavita", "smanga", "lanraragi", "suwayomi", "local-folder"),
            registered.map { it.id }.toSet(),
        )
        for ((index, registration) in registered.withIndex()) {
            val profile = LibraryConnectionProfile(Long.MIN_VALUE + 7200 + index, registration.id, "Directory fixture")
            val source = registration.createSource(profile)
            try {
                val adapter = source as ConnectionDownloadStorageAdapter
                val sourceDir = File(downloads, adapter.downloadDirectoryName()).apply { mkdirs() }
                val mangaTitle = "Directory lookup fixture"
                val mangaDir = File(sourceDir, provider.getMangaDirName(mangaTitle)).apply { mkdirs() }
                val chapterName = provider.getChapterDirName("First", null, "/first") + ".cbz"
                val chapter = File(mangaDir, chapterName).apply { writeText("owned test payload") }
                repeat(2) {
                    assertTrue(provider.findOwnedSourceDirs(source).any { it.uri == UniFile.fromFile(sourceDir)!!.uri })
                    val found = provider.findChapterDir("First", null, "/first", mangaTitle, source)
                    assertEquals(registration.id, UniFile.fromFile(chapter)!!.uri, found?.uri)
                    assertEquals("owned test payload", chapter.readText())
                    provider.invalidateSourceDirCache()
                }
            } finally {
                (source as? AutoCloseable)?.close()
            }
        }
    }

    @Test
    fun legacyMigrationPreservesSharedFallbackAndInvalidatesOtherAliases() = withRoot { provider, downloads ->
        val shared = File(downloads, "shared").apply { mkdirs() }
        File(shared, "keep.txt").writeText("shared payload")
        val source = DirectorySource("current", listOf("current", "legacy", "shared"), setOf("current", "legacy"))
        assertEquals(listOf("shared"), provider.findSourceDirs(source).map { it.name })
        assertTrue(shared.exists())
        assertFalse(File(downloads, "current").exists())

        val legacy = File(downloads, "legacy").apply { mkdirs() }
        File(legacy, "keep.txt").writeText("legacy payload")
        val alias = DirectorySource("legacy", listOf("legacy"), setOf("legacy"))
        assertEquals(listOf("legacy"), provider.findSourceDirs(alias).map { it.name })
        provider.invalidateSourceDirCache()
        assertEquals(listOf("legacy"), provider.findSourceDirs(alias).map { it.name })
        assertEquals(listOf("current", "shared"), provider.findSourceDirs(source).map { it.name })
        assertTrue(provider.findSourceDirs(alias).isEmpty())
        assertFalse(legacy.exists())
        assertEquals("legacy payload", File(downloads, "current/keep.txt").readText())
        assertEquals("shared payload", File(shared, "keep.txt").readText())
    }

    @Test
    fun downloadCreationMigrationInvalidatesAnEarlierEmptyListing() = withRoot { provider, downloads ->
        val source = DirectorySource("current", listOf("current", "legacy"), setOf("current", "legacy"))
        assertTrue(provider.findSourceDirs(source).isEmpty())
        val legacy = File(downloads, "legacy").apply { mkdirs() }
        File(legacy, "keep.txt").writeText("legacy payload")

        assertTrue(provider.getMangaDir("Book", source).isSuccess)

        assertEquals(listOf("current"), provider.findSourceDirs(source).map { it.name })
        assertFalse(legacy.exists())
        assertEquals("legacy payload", File(downloads, "current/keep.txt").readText())
        assertTrue(File(downloads, "current/Book").isDirectory)
    }

    private fun withRoot(verify: (DownloadProvider, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val root = File(context.cacheDir, "download-provider-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        val preferences = InMemoryPreferenceStore()
        val folders = object : FolderProvider {
            override fun directory() = root
            override fun path() = UniFile.fromFile(root)!!.uri.toString()
        }
        val storage = StorageManager(context, StoragePreferences(folders, preferences))
        val provider = DownloadProvider(
            context,
            storage,
            LibraryPreferences(preferences),
            ConnectionPreferences(preferences, Json),
            ConnectionRegistry(emptyList()),
        )
        try {
            val downloads = File(root, "downloads").apply { mkdirs() }
            verify(provider, downloads)
        } finally {
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            assertTrue(root.deleteRecursively())
        }
    }

    private class DirectorySource(
        override val name: String,
        private val directories: List<String>,
        private val owned: Set<String>,
    ) : Source, ConnectionDownloadStorageAdapter {
        override val id = Long.MIN_VALUE + 7299
        override val usesSharedDownloadStorage = false
        override fun downloadDirectoryName() = name
        override fun downloadDirectoryNames() = directories
        override fun ownedDownloadDirectoryNames() = owned
        override fun legacyDownloadDirectoryNames() = owned.filter { it != name }
    }
}
