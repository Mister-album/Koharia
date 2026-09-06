package koharia.source.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LocalLibraryInitialSetupDeviceTest {
    @Test
    fun creationScansOnceBeforeReturningAndLaterSavesKeepCachedContents() = runBlocking(Dispatchers.IO) {
        withDataDirectory { directory ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val sourceId = 9_400_000_000_000L + System.currentTimeMillis()
            val preferences = LocalLibraryPreferences(sourceId, Json)
            val mangas: MangaRepository = Injekt.get()
            try {
                val draft = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
                    .copy(enabledContentTypes = setOf(LocalLibraryContentType.BOOKS)).enabledLibraryConfiguration()
                val config =
                    prepareInitialLocalDirectories(context, draft, directory.toURI().toString(), directory.path, Json)
                directory.resolve("Books/first.txt").writeText("First book")
                val source =
                    LocalFolderSource(
                        context,
                        sourceId,
                        "Setup scan",
                        LibraryConnectionProfile(sourceId, "local-folder", "Setup scan"),
                    )
                var scans = 0
                val scan: suspend () -> Result<koharia.connection.ConnectionLibraryRefreshResult> = {
                    scans++
                    assertTrue(preferences.getConfig().setupCompleted)
                    source.refreshLibrary()
                }
                val result = saveLocalLibraryConfiguration(preferences, config, emptyMap(), scan)
                assertTrue(result?.isSuccess == true)
                assertEquals(1, scans)
                assertEquals(1, source.browseIndexedLibrary(query = "").size)
                directory.resolve("Books/later.txt").writeText("Added outside the app")
                assertNull(saveLocalLibraryConfiguration(preferences, preferences.getConfig(), emptyMap(), scan))
                assertEquals(1, scans)
                assertEquals(1, source.browseIndexedLibrary(query = "").size)
                source.refreshLibrary().getOrThrow()
                assertEquals(2, source.browseIndexedLibrary(query = "").size)
            } finally {
                mangas.deleteMangaBySourceId(sourceId)
                context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            }
        }
    }

    @Test
    fun scanFailureKeepsCreatedConfigurationAndDoesNotRestartOnEverySave() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceId = 9_400_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        try {
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            val result = saveLocalLibraryConfiguration(preferences, config, emptyMap()) {
                Result.failure(java.io.IOException("Unavailable directory"))
            }
            assertTrue(result?.isFailure == true)
            assertTrue(preferences.getConfig().setupCompleted)
            assertNull(
                saveLocalLibraryConfiguration(preferences, preferences.getConfig(), emptyMap()) {
                    error("Existing connections must not scan automatically")
                },
            )
        } finally {
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
        }
    }

    @Test
    fun onlyEnabledLibraryGetsAFolderAndEnablingAnotherAddsOnlyItsMissingFolder() {
        for (type in listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)) {
            withDataDirectory { directory ->
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val initial = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
                val single = initial.copy(enabledContentTypes = setOf(type))
                val result =
                    prepareInitialLocalDirectories(context, single, directory.toURI().toString(), directory.path, Json)
                val name = if (type == LocalLibraryContentType.COMICS) "Comics" else "Books"
                val otherName = if (name == "Comics") "Books" else "Comics"
                assertTrue(directory.resolve(name).isDirectory)
                assertFalse(directory.resolve(otherName).exists())
                assertEquals(listOf(type), result.roots.map { it.contentType })
                val both = prepareInitialLocalDirectories(
                    context,
                    result.copy(enabledContentTypes = initial.enabledContentTypes),
                    directory.toURI().toString(),
                    directory.path,
                    Json,
                )
                assertTrue(directory.resolve(otherName).isDirectory)
                assertEquals(result.roots.single().id, both.roots.first { it.contentType == type }.id)
                assertEquals(2, both.roots.size)
            }
        }
    }

    @Test
    fun createsBothDirectoriesInDataDirectoryAndBindsThemWithoutScanning() {
        withDataDirectory { directory ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            val result =
                prepareInitialLocalDirectories(context, config, directory.toURI().toString(), directory.path, Json)
            assertEquals(setOf("Comics", "Books"), result.roots.map { it.relativePath }.toSet())
            assertTrue(directory.resolve("Comics").isDirectory)
            assertTrue(directory.resolve("Books").isDirectory)
            assertTrue(directory.resolve(".koharia/library.json").isFile)
            result.roots.forEach { root ->
                assertEquals(directory.toURI().toString(), root.treeUri)
                assertEquals(result.defaultBookshelfId(root.contentType), root.bookshelfId)
                assertTrue(root.managed)
            }
            assertSame(result, prepareInitialLocalDirectories(context, result, "", "", Json))
            assertFalse(result.setupCompleted)
        }
    }

    @Test
    fun reusesExistingFoldersAndManifestWithoutOverwritingBooks() {
        withDataDirectory { directory ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            val first =
                prepareInitialLocalDirectories(context, config, directory.toURI().toString(), directory.path, Json)
            val book = directory.resolve("Books/existing.txt").apply { writeText("existing book") }
            val manifest = directory.resolve(".koharia/library.json").readBytes()
            val second =
                prepareInitialLocalDirectories(context, config, directory.toURI().toString(), directory.path, Json)
            assertEquals(first.libraryId, second.libraryId)
            assertEquals("existing book", book.readText())
            assertTrue(manifest.contentEquals(directory.resolve(".koharia/library.json").readBytes()))
            assertEquals(2, second.roots.size)
        }
    }

    @Test
    fun conflictingFilesFailWithoutOverwriteAndCanBeRetried() {
        withDataDirectory { directory ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            val conflicting = directory.resolve("Books").apply { writeText("keep this file") }
            assertTrue(
                runCatching {
                    prepareInitialLocalDirectories(context, config, directory.toURI().toString(), directory.path, Json)
                }.isFailure,
            )
            assertEquals("keep this file", conflicting.readText())
            assertFalse(directory.resolve("Comics").exists())
            assertTrue(conflicting.renameTo(directory.resolve("preserved.txt")))
            val result =
                prepareInitialLocalDirectories(context, config, directory.toURI().toString(), directory.path, Json)
            assertEquals(2, result.roots.size)
            assertEquals("keep this file", directory.resolve("preserved.txt").readText())
        }
    }

    private inline fun withDataDirectory(block: (java.io.File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "initial-local-setup-").toFile()
        try {
            block(directory)
        } finally {
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
