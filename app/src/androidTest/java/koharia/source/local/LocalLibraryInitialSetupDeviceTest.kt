package koharia.source.local

import android.os.Bundle
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import koharia.connection.ConnectionPreferences
import koharia.connection.ConnectionProfileManager
import koharia.connection.LibraryConnectionProfile
import koharia.connection.LibraryContentScope
import koharia.storage.LibraryStorageMode
import koharia.storage.NetworkStorageConfiguration
import koharia.storage.NetworkStoragePreferences
import koharia.storage.NetworkStorageRuntime
import koharia.testing.FixtureActivityLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LocalLibraryInitialSetupDeviceTest {
    @Test
    fun networkInitialFailureRetriesIntoPersistentEmptyShelf(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.koharia.dev.devicefixture", context.packageName)
        val mode = LibraryStorageMode.valueOf(
            checkNotNull(InstrumentationRegistry.getArguments().getString("shelfMode")),
        )
        require(mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB))
        val manager = Injekt.get<ConnectionProfileManager>()
        val sources = Injekt.get<SourceManager>()
        val selected = Injekt.get<ConnectionPreferences>().activeConnectionId
        val downloaded = Injekt.get<BasePreferences>().downloadedOnly
        val oldSelected = selected.get()
        val hadSelected = selected.isSet()
        val oldDownloaded = downloaded.get()
        val hadDownloaded = downloaded.isSet()
        val profile = manager.add("local-folder", "Empty network scan $mode ${System.nanoTime()}")
        fun checkpoint(phase: String): JSONObject {
            val connection = URL("http://127.0.0.1:19963/checkpoint?mode=$mode&phase=$phase")
                .openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                assertEquals(200, connection.responseCode)
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        fun status(
            value: String,
        ) = instrumentation.sendStatus(2, Bundle().apply { putString("shelf_cache", "$mode;$value") })
        try {
            downloaded.set(false)
            checkpoint("fail")
            manager.commitConfiguration(profile.id) { draft ->
                val network = NetworkStoragePreferences(profile.id, draft)
                network.save(
                    NetworkStorageConfiguration(
                        mode = mode,
                        address = if (mode == LibraryStorageMode.WEBDAV) {
                            "http://127.0.0.1:19961/"
                        } else {
                            "smb://127.0.0.1:19962/fixture/"
                        },
                        rootIdentity = "7ca12ea3-261e-4159-9c96-3f96385d4a73",
                    ),
                    "root",
                    "fixture-199",
                )
                LocalLibraryPreferences(profile.id, Json, draft).setConfig(
                    LocalLibraryConfig(
                        roots = listOf(
                            LocalLibraryRootConfig(
                                id = "empty-root",
                                treeUri = DocumentsContract.buildDocumentUri(
                                    NetworkStorageRuntime.authority(context),
                                    "${profile.id}|${network.account}|Books",
                                ).toString(),
                                contentType = LocalLibraryContentType.BOOKS,
                                bookshelfId = "empty-shelf",
                            ),
                        ),
                        bookshelves = listOf(
                            LocalBookshelf(
                                "empty-shelf",
                                "Empty fixture shelf",
                                LocalLibraryContentType.BOOKS,
                                LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                            ),
                        ),
                    ),
                )
            }
            selected.set(profile.id)
            var source = sources.get(profile.id) as LocalFolderSource
            val preferences = LocalLibraryPreferences(profile.id, Json)
            val initial = saveLocalLibraryConfiguration(preferences, preferences.getConfig(), emptyMap()) {
                source.refreshLibrary()
            }
            assertTrue(initial?.isFailure == true)
            assertEquals(0L, preferences.getIndex().scannedAt)
            assertTrue(preferences.getIndex().items.isEmpty())
            assertTrue(preferences.getConfig().setupCompleted)
            status("first-setup-scan-failed;cache-missing;configuration-retained")
            checkpoint("allow")
            source.refreshLibrary().getOrThrow()
            val empty = preferences.getIndex()
            assertTrue(empty.scannedAt > 0)
            assertTrue(empty.items.isEmpty())
            assertFalse(source.needsInitialScan())
            status("explicit-retry-published-valid-empty")
            val before = checkpoint("empty-published")
            val previous = source
            manager.commitConfiguration(profile.id) { }
            source = sources.get(profile.id) as LocalFolderSource
            assertTrue(source !== previous)
            FixtureActivityLauncher.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        TachiyomiTheme {
                            Navigator(LocalLibraryScreen(profile.id, LibraryContentScope.ALL, null, false))
                        }
                    }
                }
                val label = context.stringResource(MR.strings.local_library_empty_scan_cache)
                withTimeout(15_000) {
                    while (!hasEmptyLabel(instrumentation.uiAutomation.rootInActiveWindow, label)) delay(50)
                }
                delay(2_500)
                val after = checkpoint("warm-source-screen")
                assertEquals(before.getInt("catalogue"), after.getInt("catalogue"))
                assertEquals(empty, preferences.getIndex())
                assertFalse(source.needsInitialScan())
                assertEquals(0, after.getInt("mutations"))
                status("new-source-screen-empty;catalogue=0;full-index-retained;writes=0")
            }
        } finally {
            try {
                manager.remove(profile.id).getOrThrow()
                Injekt.get<MangaRepository>().deleteMangaBySourceId(profile.id)
            } finally {
                if (hadSelected) selected.set(oldSelected) else selected.delete()
                if (hadDownloaded) downloaded.set(oldDownloaded) else downloaded.delete()
                status("owned-profile-sql-removed;preferences-restored")
            }
        }
    }

    private fun hasEmptyLabel(node: AccessibilityNodeInfo?, label: String): Boolean {
        if (node == null) return false
        node.refresh()
        if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
            return true
        }
        return (0 until node.childCount).any { hasEmptyLabel(node.getChild(it), label) }
    }

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
                val result = saveLocalLibraryConfiguration(preferences, config, emptyMap(), scan = scan)
                assertTrue(result?.isSuccess == true)
                assertEquals(1, scans)
                assertEquals(1, source.browseIndexedLibrary(query = "").size)
                directory.resolve("Books/later.txt").writeText("Added outside the app")
                assertNull(saveLocalLibraryConfiguration(preferences, preferences.getConfig(), emptyMap(), scan = scan))
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
