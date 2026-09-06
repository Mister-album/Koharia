package koharia.source.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LocalBookshelfConfigurationDeviceTest {
    @Test
    fun savingDirectoryReplacementOnlyInvalidatesItsCacheAndPreservesFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "shelf-config-test-").toFile()
        val sourceId = 9_300_000_000_000L + System.currentTimeMillis()
        val preferences = LocalLibraryPreferences(sourceId, Json)
        try {
            val base = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
            val bookShelf = base.defaultBookshelfId(LocalLibraryContentType.BOOKS)
            val comicShelf = base.defaultBookshelfId(LocalLibraryContentType.COMICS)
            val oldBook = directory.resolve("old/book.txt").apply {
                parentFile!!.mkdirs()
                writeText("preserved book")
            }
            val comic = directory.resolve("comics/issue.cbz").apply {
                parentFile!!.mkdirs()
                writeText("preserved comic")
            }
            val replacement = directory.resolve("new").apply { mkdirs() }
            val config = base.copy(setupCompleted = true)
                .withBookshelfDirectory(
                    bookShelf,
                    LocalLibraryRootConfig(id = "old", treeUri = oldBook.parentFile!!.toURI().toString()),
                )
                .withBookshelfDirectory(
                    comicShelf,
                    LocalLibraryRootConfig(id = "keep", treeUri = comic.parentFile!!.toURI().toString()),
                )
            preferences.setConfig(config)
            val oldKey = LocalLibraryLocator.itemKey("old", "book.txt")
            val keepKey = LocalLibraryLocator.itemKey("keep", "issue.cbz")
            val items = listOf(
                LocalLibraryItem(
                    oldKey,
                    "old",
                    "book.txt",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryItem.Kind.FILE_ENTRY,
                    "txt",
                    1,
                    1,
                ),
                LocalLibraryItem(
                    keepKey,
                    "keep",
                    "issue.cbz",
                    LocalLibraryContentType.COMICS,
                    LocalLibraryItem.Kind.FILE_ENTRY,
                    "cbz",
                    1,
                    1,
                ),
            )
            preferences.setIndex(LocalLibraryIndex(scannedAt = 1, items = items))
            preferences.setBookshelfAssignment(oldKey, bookShelf)
            preferences.setBookshelfAssignment(keepKey, comicShelf)
            preferences.setMetadataOverride(oldKey, LocalMetadataOverride(title = "old title"))
            preferences.setMetadataOverride(keepKey, LocalMetadataOverride(title = "keep title"))
            val draft = config.withBookshelfDirectory(
                bookShelf,
                LocalLibraryRootConfig(id = "new", treeUri = replacement.toURI().toString()),
                "old",
            )
            assertEquals(2, preferences.getIndex().items.size)
            preferences.saveLibraryDraft(draft, preferences.getBookshelfAssignments())
            assertEquals(listOf(keepKey), preferences.getIndex().items.map { it.itemKey })
            assertFalse(oldKey in preferences.getBookshelfAssignments())
            assertEquals(comicShelf, preferences.getBookshelfAssignments()[keepKey])
            assertEquals("keep title", preferences.getMetadataOverrides()[keepKey]?.title)
            assertEquals("preserved book", oldBook.readText())
            assertEquals("preserved comic", comic.readText())
            assertTrue(replacement.isDirectory)
            assertEquals("new", preferences.getConfig().bookshelfRoots(bookShelf).single().id)
        } finally {
            context.getSharedPreferences("source_$sourceId", 0).edit().clear().commit()
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            directory.deleteRecursively()
        }
    }
}
