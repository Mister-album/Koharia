package koharia.source.local

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import koharia.cover.SharedCoverFiles
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LocalCustomCoverMaintenanceDeviceTest {
    @Test
    fun savedDirectoryHistoryRestoresCoverAndRechecksConfigurationBeforeCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "cover-identity-test-").toFile()
        val sourceId = 9_400_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        try {
            val base = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            val shelf = base.defaultBookshelfId(LocalLibraryContentType.BOOKS)
            val root = LocalLibraryRootConfig(id = "original", treeUri = directory.toURI().toString())
            val config = base.withBookshelfDirectory(shelf, root).copy(setupCompleted = true)
            preferences.setConfig(config)
            val url = LocalLibraryLocator.entryUrl(sourceId, root.id, "Book.epub")
            preferences.rememberCustomCover(url)
            val covers = directory.resolve("covers").apply { mkdirs() }
            val files = SharedCoverFiles(checkNotNull(UniFile.fromFile(covers))) { true }
            val key = SharedCoverFiles.key(sourceId, url)
            files.write(key, "saved image".byteInputStream())

            preferences.saveLibraryDraft(config.withoutRoot(root.id), emptyMap())
            val reloaded = LocalLibraryPreferences(sourceId, Json)
            val removed = checkNotNull(reloaded.coverMaintenanceConfig())
            assertEquals(root.id, removed.detachedRoots.single().id)
            assertTrue(url in reloaded.customCoverUrls())
            assertTrue(isRemovedLocalCover(sourceId, url, removed) { _, _ -> error("Detached") })
            val restored = removed.withBookshelfDirectory(shelf, root.copy(id = "new-random-id"))
            reloaded.saveLibraryDraft(restored, emptyMap())
            assertFalse(reloaded.withUnchangedConfig(removed) { files.delete(key) })
            assertEquals(root.id, reloaded.getConfig().roots.single().id)
            assertTrue(reloaded.getConfig().detachedRoots.isEmpty())
            assertEquals("saved image", files.find(key)!!.openInputStream().use { it.readBytes().decodeToString() })

            reloaded.removeRoot(root.id)
            val detached = checkNotNull(reloaded.coverMaintenanceConfig())
            assertTrue(reloaded.withUnchangedConfig(detached) { files.delete(key) })
            assertNull(files.find(key))
        } finally {
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test
    fun incompleteSafListingsCannotDeleteCustomCovers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "app.koharia.dev.devicefixture")
        val authority = "${instrumentation.context.packageName}.localscan"
        val control = Uri.parse("content://$authority")
        val root = checkNotNull(UniFile.fromUri(context, DocumentsContract.buildTreeDocumentUri(authority, "root")))
        val resolver = context.contentResolver
        try {
            resolver.call(control, "fixture:grant", context.packageName, null)
            assertTrue(root.canRead())
            for (mode in listOf("loading", "failure")) {
                resolver.call(control, "fixture:reset", mode, null)
                assertNull(localCoverEntryExists(context, root, "missing.cbz"))
            }
            resolver.call(control, "fixture:reset", "empty", null)
            assertEquals(false, localCoverEntryExists(context, root, "missing.cbz"))
        } finally {
            resolver.call(control, "fixture:reset", "ready", null)
            resolver.call(control, "fixture:revoke", context.packageName, null)
        }
    }
}
