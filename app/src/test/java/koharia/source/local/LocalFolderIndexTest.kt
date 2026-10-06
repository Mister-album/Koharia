package koharia.source.local

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalFolderIndexTest {
    @Test
    fun `stable node locator resolves its physical path for remote files`() {
        val item = item("Series/001.cbz").copy(
            kind = LocalLibraryItem.Kind.CHAPTER,
            locatorPath = ".koharia/nodes/chapter-id",
            itemKey = LocalLibraryLocator.itemKey("root", "Series/001.cbz"),
        )
        val index = LocalLibraryIndex(items = listOf(item))
        assertEquals(item, index.itemsByLocator["root" to ".koharia/nodes/chapter-id"])
        assertEquals("Series/001.cbz", index.itemsByLocator["root" to ".koharia/nodes/chapter-id"]?.physicalPath())
    }

    @Test
    fun `upgrading a legacy virtual provider identity keeps its locator and override key`() {
        val old = item("Images/.koharia-image-series").copy(
            format = "directory",
            documentIdentity = "provider:1",
            locatorPath = ".koharia/nodes/old",
            itemKey = LocalLibraryLocator.itemKey("root", ".koharia/nodes/old"),
        )
        val current = old.copy(
            documentIdentity = "provider:1:image-series",
            virtualType = LocalLibraryItem.VirtualType.IMAGE_SERIES,
        )
        val result = reconcileLocalFolders(listOf(current), listOf(old)).single()
        assertEquals(old.locatorPath, result.locatorPath)
        assertEquals(old.itemKey, result.itemKey)
    }

    @Test
    fun `series and chapters retain identities and parent mapping after rename`() {
        val old = reconcileLocalFolders(
            listOf(
                item("Series", true).copy(kind = LocalLibraryItem.Kind.SERIES, folderIdentity = "series-marker"),
                item("Series/01.cbz").copy(kind = LocalLibraryItem.Kind.CHAPTER),
            ),
            emptyList(),
        )
        val moved = reconcileLocalFolders(
            listOf(
                item("Renamed", true).copy(kind = LocalLibraryItem.Kind.SERIES, folderIdentity = "series-marker"),
                item("Renamed/01.cbz").copy(kind = LocalLibraryItem.Kind.CHAPTER),
            ),
            old,
        )
        assertEquals(old.map { it.itemKey }, moved.map { it.itemKey })
        val index = LocalLibraryIndex(items = moved)
        assertEquals(listOf(moved[1]), index.chaptersBySeriesKey[moved[0].itemKey])
    }

    @Test
    fun `virtual comic keeps its identity when pure folder becomes mixed and changes path`() {
        val virtual = item(".koharia-image-series-Images").copy(
            format = "directory",
            virtualType = LocalLibraryItem.VirtualType.IMAGE_SERIES,
            backingPath = "Images",
            documentIdentity = "provider:1:image-series",
        )
        val old = reconcileLocalFolders(listOf(virtual), emptyList())
        val result = reconcileLocalFolders(
            listOf(
                item("Renamed", true).copy(documentIdentity = "provider:1"),
                virtual.copy(relativePath = "Renamed/.koharia-image-series", backingPath = "Renamed"),
            ),
            old,
        )
        val comic = result.single { it.isVirtualImageSeries() }
        assertEquals(old.single().itemKey, comic.itemKey)
        assertEquals("Renamed", comic.physicalPath())
        assertNotEquals(comic.itemKey, result.single { it.kind == LocalLibraryItem.Kind.FOLDER }.itemKey)
    }

    @Test
    fun `old container cannot become a virtual comic solely through same directory format`() {
        val old = reconcileLocalFolders(listOf(item("Images", true)), emptyList())
        val virtual = item("Images").copy(format = "directory", virtualType = LocalLibraryItem.VirtualType.IMAGE_SERIES)
        assertNotEquals(old.single().itemKey, reconcileLocalFolders(listOf(virtual), old).single().itemKey)
    }

    private fun item(path: String, folder: Boolean = false, root: String = "root") = LocalLibraryItem(
        itemKey = LocalLibraryLocator.itemKey(root, path),
        rootId = root,
        relativePath = path,
        kind = if (folder) LocalLibraryItem.Kind.FOLDER else LocalLibraryItem.Kind.FILE_ENTRY,
        format = if (folder) "directory" else "epub",
        sizeBytes = 100,
        modifiedAt = 123,
    )

    @Test
    fun `nested browse shows only direct children while search stays within subtree`() {
        val file = item("a/b/book.epub")
        assertFalse(file.isInFolder(null, null, false))
        assertTrue(file.isInFolder("root", "a/b", false))
        assertTrue(file.isInFolder("root", "a", true))
        assertFalse(file.isInFolder("other", "a", true))
        assertFalse(item("ab/book.epub").isInFolder("root", "a", true))
    }

    @Test
    fun `provider identity preserves locator after external move`() {
        val old = reconcileLocalFolders(
            listOf(item("a.epub").copy(documentIdentity = "provider:1")),
            emptyList(),
        ).single()
        val moved = reconcileLocalFolders(
            listOf(item("b.epub").copy(documentIdentity = "provider:1")),
            listOf(old),
        ).single()
        assertEquals(old.itemKey, moved.itemKey)
        assertEquals(old.locatorPath, moved.locatorPath)
        assertEquals("b.epub", moved.relativePath)
    }

    @Test
    fun `same name size and time at another path never inherit identity`() {
        val old = reconcileLocalFolders(listOf(item("a/book.epub")), emptyList()).single()
        val moved = reconcileLocalFolders(listOf(item("b/book.epub")), listOf(old)).single()
        assertNotEquals(old.itemKey, moved.itemKey)
    }

    @Test
    fun `carried directory marker preserves descendants including image mode`() {
        val old = reconcileLocalFolders(
            listOf(
                item(
                    "a",
                    true,
                ).copy(
                    folderIdentity = "marker",
                    imageComic = true,
                ),
                item("a/book.epub"),
            ),
            emptyList(),
        )
        val moved = reconcileLocalFolders(
            listOf(
                item(
                    "b",
                    true,
                ).copy(folderIdentity = "marker"),
                item("b/book.epub"),
            ),
            old,
        )
        assertEquals(old.map { it.itemKey }, moved.map { it.itemKey })
        assertTrue(moved.first().imageComic)
    }

    @Test
    fun `copied marker does not share identity or progress`() {
        val original = item("a", true).copy(folderIdentity = "marker")
        val old = reconcileLocalFolders(listOf(original, item("a/book.epub")), emptyList())
        val scanned = listOf(original, item("a/book.epub"), original.copy(relativePath = "b"), item("b/book.epub"))
        val result = reconcileLocalFolders(scanned, old).associateBy { it.relativePath }
        assertEquals(old.first().itemKey, result.getValue("a").itemKey)
        assertNotEquals(result.getValue("a").itemKey, result.getValue("b").itemKey)
        assertNotEquals(result.getValue("a/book.epub").itemKey, result.getValue("b/book.epub").itemKey)
    }

    @Test
    fun `ambiguous provider identity and marker do not pick arbitrary old file`() {
        val old = listOf(item("a.epub"), item("b.epub")).map { it.copy(documentIdentity = "provider:duplicate") }
        val result = reconcileLocalFolders(
            listOf(item("c.epub").copy(documentIdentity = "provider:duplicate")),
            old,
        ).single()
        assertFalse(result.itemKey in old.map { it.itemKey })
    }

    @Test
    fun `identities remain isolated by root`() {
        val old = item("a.epub", root = "first").copy(documentIdentity = "provider:1")
        val result = reconcileLocalFolders(
            listOf(
                item(
                    "b.epub",
                    root = "second",
                ).copy(documentIdentity = "provider:1"),
            ),
            listOf(old),
        ).single()
        assertNotEquals(old.itemKey, result.itemKey)
    }

    @Test
    fun `replacement at old path cannot steal a moved document identity`() {
        val old = reconcileLocalFolders(listOf(item("a.epub").copy(documentIdentity = "first")), emptyList())
        val result = reconcileLocalFolders(
            listOf(
                item("a.epub").copy(documentIdentity = "replacement"),
                item("b.epub").copy(documentIdentity = "first"),
            ),
            old,
        ).associateBy { it.relativePath }
        assertNotEquals(old.single().itemKey, result.getValue("a.epub").itemKey)
        assertEquals(old.single().itemKey, result.getValue("b.epub").itemKey)
    }

    @Test
    fun `swapped directories carry their children rather than taking destination progress`() {
        val old = reconcileLocalFolders(
            listOf(
                item("a", true).copy(folderIdentity = "first"),
                item("a/book.epub"),
                item("b", true).copy(folderIdentity = "second"),
                item("b/book.epub"),
            ),
            emptyList(),
        )
        val result = reconcileLocalFolders(
            listOf(
                item("a", true).copy(folderIdentity = "second"),
                item("a/book.epub"),
                item("b", true).copy(folderIdentity = "first"),
                item("b/book.epub"),
            ),
            old,
        ).associateBy { it.relativePath }
        assertEquals(old.first { it.relativePath == "a/book.epub" }.itemKey, result.getValue("b/book.epub").itemKey)
        assertEquals(old.first { it.relativePath == "b/book.epub" }.itemKey, result.getValue("a/book.epub").itemKey)
    }

    @Test
    fun `missing records persist but are not browsable`() {
        val missing = item("book.epub").copy(missing = true)
        val index = LocalLibraryIndex(items = listOf(missing))
        assertTrue(index.libraryItemsByKey.isEmpty())
        assertEquals(missing, index.itemsByKey[missing.itemKey])
    }

    @Test
    fun `stable locator survives serialization and path changes`() {
        val before = reconcileLocalFolders(listOf(item("old.epub")), emptyList()).single()
        val after = before.copy(relativePath = "new.epub")
        val restored = Json.decodeFromString<LocalLibraryItem>(Json.encodeToString(after))
        assertEquals(before.locatorPath, restored.locatorPath)
        assertEquals("new.epub", restored.relativePath)
    }

    @Test
    fun `restored or rebound root keeps locator while discarding provider identities`() {
        val old = reconcileLocalFolders(listOf(item("a.epub").copy(documentIdentity = "old-provider")), emptyList())
        val restored = LocalLibraryIndex(items = old).rebindFolderLocations(setOf("root"), markMissing = true)
        assertTrue(restored.items.single().missing)
        val scanned =
            reconcileLocalFolders(listOf(item("a.epub").copy(documentIdentity = "new-provider")), restored.items)
        assertEquals(old.single().itemKey, scanned.single().itemKey)
        assertFalse(scanned.single().missing)
    }

    @Test
    fun `auxiliary covers and metadata are not comic pages`() {
        listOf(
            "cover.jpg",
            "Folder.PNG",
            "poster.webp",
            "!cover.jpg",
            "metadata.opf",
            "ComicInfo.xml",
            ".koharia",
        ).forEach {
            assertTrue(
                isLocalAuxiliaryFile(it),
                it,
            )
        }
        assertFalse(isLocalAuxiliaryFile("001.jpg"))
        assertFalse(isLocalAuxiliaryFile("cover story.epub"))
    }

    @Test
    fun `overlapping roots reject ancestors but allow siblings`() {
        assertTrue(localDirectoriesOverlap("provider:primary:", "provider:primary:Books"))
        assertTrue(localDirectoriesOverlap("provider:root", "provider:root/books"))
        assertTrue(localDirectoriesOverlap("provider:root/books", "provider:root"))
        assertFalse(localDirectoriesOverlap("provider:root/books", "provider:root/books2"))
    }

    @Test
    fun `binding sibling uses selected document rather than granted tree`() {
        val base = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
        val shelfId = base.defaultBookshelfId(LocalLibraryContentType.COMICS)
        val provider = "content://com.android.externalstorage.documents"
        val existing = LocalLibraryRootConfig(
            id = "existing",
            treeUri = "$provider/tree/primary%3ALibrary/document/primary%3ALibrary%2FSeries",
        )
        val config = base.withBookshelfDirectory(shelfId, existing)
        val sibling = LocalLibraryRootConfig(
            id = "sibling",
            treeUri = "$provider/tree/primary%3ALibrary%2FFolders",
        )
        assertEquals(2, config.withBookshelfDirectory(shelfId, sibling).roots.size)
        listOf("Library", "Library%2FSeries", "Library%2FSeries%2FChild").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                config.withBookshelfDirectory(shelfId, sibling.copy(treeUri = "$provider/tree/primary%3A$path"))
            }
        }
    }

    @Test
    fun `document tree and managed relative path identify the same directory`() {
        val provider = "content://com.android.externalstorage.documents"
        val root = LocalLibraryRootConfig(id = "root", treeUri = "$provider/tree/primary%3ALibrary")
        val direct = root.copy(treeUri = "$provider/tree/primary%3ALibrary%2FComics%2FVolumes")
        val document = root.copy(
            treeUri = "$provider/tree/primary%3ALibrary/document/primary%3ALibrary%2FComics",
            relativePath = "Volumes",
        )
        assertEquals(direct.directoryKey(), document.directoryKey())
        assertEquals(direct.directoryKey(), root.copy(relativePath = "Comics/Volumes").directoryKey())
    }

    @Test
    fun `document URI preserves literal plus and decodes unicode and spaces`() {
        val root = LocalLibraryRootConfig(
            id = "root",
            treeUri = "content://provider/tree/root/document/root%2F%E6%BC%AB%E7%94%BB%20A+B",
        )
        assertEquals("provider:root/漫画 A+B", root.directoryKey())
        assertEquals(root.directoryKey(), root.copy(treeUri = root.treeUri.replace("+", "%2B")).directoryKey())
    }

    @Test
    fun `nested changes invalidate ancestor cover fingerprints but not siblings`() {
        val original = listOf(item("A", true), item("A/B", true), item("A/B/book.epub"), item("Other", true))
        val first = withLocalFolderFingerprints(original).associateBy { it.relativePath }
        val next = withLocalFolderFingerprints(
            original.map {
                if (it.relativePath == "A/B/book.epub") it.copy(fingerprint = "changed") else it
            },
        ).associateBy { it.relativePath }
        assertNotEquals(first.getValue("A").fingerprint, next.getValue("A").fingerprint)
        assertNotEquals(first.getValue("A/B").fingerprint, next.getValue("A/B").fingerprint)
        assertEquals(first.getValue("Other").fingerprint, next.getValue("Other").fingerprint)
    }

    @Test
    fun `folder management rejects traversal and reserved names`() {
        listOf("", ".", "..", ".koharia", "a/b", "a\\b", "a\n", " a").forEach {
            assertThrows(IllegalArgumentException::class.java) { validateLocalName(it) }
        }
        validateLocalName("第一册 02")
    }
}
