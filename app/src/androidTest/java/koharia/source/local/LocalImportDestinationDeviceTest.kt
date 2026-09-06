package koharia.source.local

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import koharia.connection.ConnectionMediaImportItem
import koharia.connection.ConnectionMediaImportRequest
import koharia.connection.LibraryConnectionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalImportDestinationDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun parentTreeGrantAllowsDocumentUriDestinationAndRealImport() = runBlocking(Dispatchers.IO) {
        val permission = context.contentResolver.persistedUriPermissions.firstOrNull {
            it.isReadPermission && it.isWritePermission && it.uri.authority == "com.android.externalstorage.documents"
        }
        assumeTrue("Requires an external-storage tree grant", permission != null)
        val granted = checkNotNull(permission)
        val base = checkNotNull(UniFile.fromUri(context, granted.uri))
        val folderName = "import-access-test-${UUID.randomUUID()}"
        val owned = checkNotNull(base.createDirectory(folderName))
        val expectedDocumentId = "${DocumentsContract.getTreeDocumentId(granted.uri)}/$folderName"
        check(DocumentsContract.getDocumentId(owned.uri) == expectedDocumentId)
        val input = Files.createTempFile(context.cacheDir.toPath(), "import-source-", ".txt").toFile()
        val sourceId = 9_500_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        val mangas: MangaRepository = Injekt.get()
        try {
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                granted.uri,
                DocumentsContract.getTreeDocumentId(granted.uri),
            ).toString()
            assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri.toString() == documentUri })
            configure(preferences, documentUri, folderName)
            val source = source(sourceId)
            val destination = source.mediaImportDestinations().single()
            assertTrue("txt" in destination.supportedExtensions)
            val expected = "A book imported through a parent SAF grant.".toByteArray()
            input.writeBytes(expected)
            source.importMedia(
                ConnectionMediaImportRequest(
                    destinationId = destination.id,
                    shelfId = destination.defaultShelfId,
                    seriesName = "",
                    items = listOf(
                        ConnectionMediaImportItem(
                            input.toURI().toString(),
                            "import-test.txt",
                            "text/plain",
                            input.length(),
                            "txt",
                        ),
                    ),
                ),
            ).getOrThrow()
            val imported = checkNotNull(owned.findFile("import-test.txt"))
            assertTrue(imported.openInputStream().use { it.readBytes() }.contentEquals(expected))
            assertEquals(1, source.browseIndexedLibrary(query = "").size)
        } finally {
            mangas.deleteMangaBySourceId(sourceId)
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            check(input.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            input.delete()
            check(DocumentsContract.getDocumentId(owned.uri) == expectedDocumentId)
            assertTrue(owned.delete())
        }
    }

    @Test
    fun rawWritableDirectoryDoesNotNeedASafGrantAndReadonlyIsExcluded() = runBlocking(Dispatchers.IO) {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "import-access-raw-").toFile()
        val sourceId = 9_500_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        try {
            configure(preferences, directory.toURI().toString(), "")
            val source = source(sourceId)
            assertEquals(1, source.mediaImportDestinations().size)
            assertTrue(directory.setWritable(false, false))
            assertTrue(source.mediaImportDestinations().isEmpty())
        } finally {
            directory.setWritable(true, true)
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }

    @Test
    fun inspectConfiguredSourceWithoutModifyingItsFiles() = runBlocking(Dispatchers.IO) {
        val sourceId = InstrumentationRegistry.getArguments().getString("inspectedSourceId")?.toLongOrNull()
        assumeTrue("Optional source inspection requires an id", sourceId != null)
        val id = checkNotNull(sourceId)
        val roots = LocalLibraryPreferences(id, Json).getConfig().roots
        assertTrue(roots.isNotEmpty())
        assertEquals(roots.map { it.id }.toSet(), source(id).mediaImportDestinations().map { it.id }.toSet())
    }

    private fun source(id: Long) = LocalFolderSource(
        context,
        id,
        "Import access",
        LibraryConnectionProfile(id, LocalFolderConnectionProvider.ID, "Import access"),
    )

    private fun configure(preferences: LocalLibraryPreferences, treeUri: String, relativePath: String) {
        val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            .copy(enabledContentTypes = setOf(LocalLibraryContentType.BOOKS)).enabledLibraryConfiguration()
        preferences.setConfig(
            config.copy(
                setupCompleted = true,
                roots = listOf(
                    LocalLibraryRootConfig(
                        id = "destination",
                        treeUri = treeUri,
                        relativePath = relativePath,
                        contentType = LocalLibraryContentType.BOOKS,
                        bookshelfId = config.defaultBookshelfId(LocalLibraryContentType.BOOKS),
                    ),
                ),
            ),
        )
    }
}
