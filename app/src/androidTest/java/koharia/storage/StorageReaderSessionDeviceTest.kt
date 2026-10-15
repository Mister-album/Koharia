package koharia.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.sourcePreferences
import koharia.connection.ConnectionPreferences
import koharia.connection.LibraryConnectionProfile
import koharia.source.local.LocalFolderSource
import koharia.source.local.LocalLibraryConfig
import koharia.source.local.LocalLibraryLocator
import koharia.source.local.LocalLibraryPreferences
import koharia.source.local.LocalLibraryRootConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class StorageReaderSessionDeviceTest {
    @Test
    fun nativePdfRetainsLocalOnlyProgressCapability(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val id = System.nanoTime()
        check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
        val preferences = sourcePreferences("source_$id")
        check(preferences.all.isEmpty())
        val folder = java.io.File(context.cacheDir, "native-pdf-capability-$id").canonicalFile
        check(folder.parentFile == context.cacheDir.canonicalFile && !folder.exists() && folder.mkdirs())
        val file = java.io.File(folder, "book.pdf").apply { writeBytes(koharia.pdf.reflowTextPdfFixture()) }
        val bytes = file.readBytes()
        var source: LocalFolderSource? = null
        try {
            val root = LocalLibraryRootConfig(id = "root", treeUri = android.net.Uri.fromFile(folder).toString())
            LocalLibraryPreferences(id, Json).setConfig(LocalLibraryConfig(roots = listOf(root)))
            val owner =
                LocalFolderSource(context, id, "Native fixture", LibraryConnectionProfile(id, "local-folder", "Native"))
            source = owner
            val chapter = Chapter.create().copy(url = LocalLibraryLocator.chapterUrl(id, root.id, file.name))
            assertTrue(owner.localChapterFile(chapter.url) != null)
            assertNull(owner.captureReflowProgressSession(chapter))
            assertTrue(bytes.contentEquals(file.readBytes()))
        } finally {
            source?.close()
            check(preferences.edit().clear().commit())
            check(folder.parentFile == context.cacheDir.canonicalFile && folder.deleteRecursively())
        }
    }

    @Test
    fun missingCatalogueCaptureStaysOfflineAndRetainsNetworkOwner(): Unit = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        for (mode in listOf(LibraryStorageMode.WEBDAV, LibraryStorageMode.SMB)) {
            val id = System.nanoTime()
            check(!Injekt.get<ConnectionPreferences>().isKnownConnectionId(id))
            val preferences = sourcePreferences("source_$id")
            check(preferences.all.isEmpty())
            var source: LocalFolderSource? = null
            try {
                val network = NetworkStoragePreferences(id)
                network.save(
                    NetworkStorageConfiguration(
                        mode = mode,
                        address = if (mode ==
                            LibraryStorageMode.WEBDAV
                        ) {
                            "http://127.0.0.1:1"
                        } else {
                            "smb://127.0.0.1/share"
                        },
                        rootIdentity = "fixture-$id",
                        needsValidation = true,
                    ),
                    "fixture",
                    "fixture",
                )
                val runtime = NetworkStorageRuntime.get(context, id)
                val root = LocalLibraryRootConfig(id = "root", treeUri = runtime.uri("").toString())
                LocalLibraryPreferences(id, Json).setConfig(LocalLibraryConfig(roots = listOf(root)))
                val owner =
                    LocalFolderSource(
                        context,
                        id,
                        "Reader fixture",
                        LibraryConnectionProfile(id, "local-folder", "Reader fixture"),
                    )
                source = owner
                val chapter = Chapter.create().copy(url = LocalLibraryLocator.chapterUrl(id, root.id, "book.epub"))
                // The backend rejects all requests until validation; missing catalogue must not trigger hydration.
                val binding = owner.captureEpubProgressSession(chapter)
                val pages = owner.capturePageProgressSession(chapter)
                val missing = runCatching { owner.captureReflowProgressSession(chapter) }.exceptionOrNull()
                assertTrue(missing is StorageFailure && missing.reason == StorageFailure.Reason.NOT_FOUND)
                pages.checkCurrent()
                assertNull(pages.adapter.pullPageProgress(chapter.url, chapter.memo))
                binding.checkCurrent()
                assertNull(binding.adapter.pullEpubProgress(chapter.url))
                network.save(network.configuration, "fixture", "replacement")
                assertTrue(runCatching { pages.checkCurrent() }.exceptionOrNull() is CancellationException)
                assertTrue(
                    runCatching {
                        pages.adapter.recordLocalPageProgress(chapter.url, 1, 10, 100)
                    }.exceptionOrNull() is CancellationException,
                )
                assertTrue(runCatching { binding.checkCurrent() }.exceptionOrNull() is CancellationException)
                assertTrue(
                    runCatching {
                        binding.adapter.pullEpubProgress(chapter.url)
                    }.exceptionOrNull() is CancellationException,
                )
                val reopened = owner.captureEpubProgressSession(chapter)
                reopened.checkCurrent()
                assertNull(reopened.adapter.pullEpubProgress(chapter.url))
                network.save(network.configuration.copy(persistentIdentity = false), "fixture", "replacement")
                val disabledMissing = runCatching { owner.captureReflowProgressSession(chapter) }.exceptionOrNull()
                assertTrue(
                    disabledMissing is StorageFailure && disabledMissing.reason == StorageFailure.Reason.NOT_FOUND,
                )
                owner.close()
                assertTrue(runCatching { reopened.checkCurrent() }.exceptionOrNull() is CancellationException)
            } finally {
                source?.close()
                NetworkStorageRuntime.invalidate(id)
                check(preferences.edit().clear().commit())
            }
        }
    }
}
