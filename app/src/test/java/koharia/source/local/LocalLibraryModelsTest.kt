package koharia.source.local

import koharia.connection.LibraryContentScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLibraryModelsTest {

    @Test
    fun `locator round trips encoded relative paths`() {
        val relativePath = "Comics/Series Name/Volume 01.cbz"
        val rootId = "comic-root"
        val url = LocalLibraryLocator.chapterUrl(42L, rootId, relativePath)

        assertEquals(relativePath, LocalLibraryLocator.relativePath(url, 42L))
        assertEquals(rootId, LocalLibraryLocator.location(url, 42L)?.rootId)
        assertNull(LocalLibraryLocator.relativePath(url, 43L))
    }

    @Test
    fun `locator normalizes separators and traversal segments`() {
        assertEquals("Series/Chapter.cbz", LocalLibraryLocator.normalize("/Series/./Chapter.cbz/"))
    }

    @Test
    fun `item keys are stable for equivalent paths`() {
        assertEquals(
            LocalLibraryLocator.itemKey("root", "Series/Chapter.cbz"),
            LocalLibraryLocator.itemKey("root", "/Series\\Chapter.cbz/"),
        )
    }

    @Test
    fun `item keys isolate identical paths in different roots`() {
        assertNotEquals(
            LocalLibraryLocator.itemKey("comic-root", "Same/Volume.cbz"),
            LocalLibraryLocator.itemKey("book-root", "Same/Volume.cbz"),
        )
    }

    @Test
    fun `version two index restores with no pending chapter refreshes`() {
        val stored = """
            {
              "schemaVersion": 2,
              "scannedAt": 123,
              "items": []
            }
        """.trimIndent()

        val restored = Json.decodeFromString<LocalLibraryIndex>(stored)

        assertEquals(2, restored.schemaVersion)
        assertEquals(123L, restored.scannedAt)
        assertTrue(restored.pendingChapterRefreshItemKeys.isEmpty())
    }

    @Test
    fun `version one locator remains readable`() {
        val location = LocalLibraryLocator.location(
            "koharia-local-v1://42/Comics/Series%20Name/Volume%2001.cbz",
            42L,
        )

        assertNull(location?.rootId)
        assertEquals("Comics/Series Name/Volume 01.cbz", location?.relativePath)
    }

    @Test
    fun `managed version one config migrates to comic and book roots`() {
        val migrated = LocalLibraryConfig(
            treeUri = "content://library",
            displayPath = "Library",
            contentType = LocalLibraryContentType.MIXED,
            layout = LocalLibraryLayout.KOHARIA,
            schemaVersion = 1,
        ).migrate(42L)

        assertEquals(7, migrated.schemaVersion)
        assertTrue(migrated.setupCompleted)
        assertEquals(listOf("Comics", "Books"), migrated.roots.map { it.relativePath })
        assertEquals(
            listOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS),
            migrated.roots.map { it.contentType },
        )
        assertTrue(migrated.roots.all { it.managed })
        assertEquals("content://library", migrated.managedBaseTreeUri)
    }

    @Test
    fun `compatible version one config migrates to one typed root`() {
        val migrated = LocalLibraryConfig(
            treeUri = "content://comics",
            displayPath = "My comics",
            contentType = LocalLibraryContentType.COMICS,
            layout = LocalLibraryLayout.COMPATIBLE,
            schemaVersion = 1,
        ).migrate(42L)

        assertEquals(1, migrated.roots.size)
        assertTrue(migrated.setupCompleted)
        assertEquals(LocalLibraryContentType.COMICS, migrated.roots.single().contentType)
        assertEquals("", migrated.roots.single().relativePath)
        assertEquals("", migrated.treeUri)
    }

    @Test
    fun `serialized version one config decodes before migration`() {
        val stored = """
            {
              "treeUri": "content://books",
              "displayPath": "Books",
              "contentType": "BOOKS",
              "layout": "COMPATIBLE",
              "metadataStorage": "DATABASE",
              "libraryId": "library-id",
              "schemaVersion": 1
            }
        """.trimIndent()

        val migrated = Json.decodeFromString<LocalLibraryConfig>(stored).migrate(7L)

        assertEquals("library-id", migrated.libraryId)
        assertEquals("content://books", migrated.roots.single().treeUri)
        assertEquals(LocalLibraryContentType.BOOKS, migrated.roots.single().contentType)
        assertTrue(migrated.setupCompleted)
    }

    @Test
    fun `new config remains in setup until explicitly completed`() {
        val migrated = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(
                    id = "root",
                    treeUri = "content://comics",
                    displayPath = "Comics",
                    contentType = LocalLibraryContentType.COMICS,
                ),
            ),
            setupCompleted = false,
            schemaVersion = 3,
        ).migrate(42L)

        assertEquals(7, migrated.schemaVersion)
        assertEquals(false, migrated.setupCompleted)
    }

    @Test
    fun `version two configured roots skip the new setup wizard`() {
        val migrated = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(
                    id = "root",
                    treeUri = "content://books",
                    displayPath = "Books",
                    contentType = LocalLibraryContentType.BOOKS,
                ),
            ),
            setupCompleted = false,
            schemaVersion = 2,
        ).migrate(42L)

        assertTrue(migrated.setupCompleted)
        assertEquals(7, migrated.schemaVersion)
    }

    @Test
    fun `incomplete setup state is always serialized explicitly`() {
        val encoded = Json.encodeToString(
            LocalLibraryConfig(
                roots = listOf(
                    LocalLibraryRootConfig(
                        id = "root",
                        treeUri = "content://comics",
                        displayPath = "Comics",
                        contentType = LocalLibraryContentType.COMICS,
                    ),
                ),
                setupCompleted = false,
            ),
        )

        assertTrue(encoded.contains("\"setupCompleted\":false"))
    }

    @Test
    fun `legacy roots without setup field are inferred as configured`() {
        val migrated = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(
                    id = "root",
                    treeUri = "content://comics",
                    displayPath = "Comics",
                    contentType = LocalLibraryContentType.COMICS,
                ),
            ),
            schemaVersion = 3,
        ).migrate(sourceId = 42L, inferConfiguredSetup = true)

        assertTrue(migrated.setupCompleted)
    }

    @Test
    fun `separate comic and book roots expose classified library tabs`() {
        val config = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(id = "comics", contentType = LocalLibraryContentType.COMICS),
                LocalLibraryRootConfig(id = "books", contentType = LocalLibraryContentType.BOOKS),
            ),
        )

        assertEquals(
            setOf(LibraryContentScope.COMIC, LibraryContentScope.BOOK),
            localLibraryContentScopes(config),
        )
    }

    @Test
    fun `comic and book bookshelves stay type isolated`() {
        val config = LocalLibraryConfig(
            bookshelves = listOf(
                LocalBookshelf("comic-custom", "Comics A", LocalLibraryContentType.COMICS),
                LocalBookshelf("book-custom", "Books A", LocalLibraryContentType.BOOKS),
            ),
        )

        assertEquals(
            listOf("comic-custom"),
            config.bookshelvesFor(LocalLibraryContentType.COMICS).map { it.id },
        )
        assertEquals(
            listOf("book-custom"),
            config.bookshelvesFor(LocalLibraryContentType.BOOKS).map { it.id },
        )
    }

    @Test
    fun `a local connection can enable only one content type`() {
        val config = LocalLibraryConfig(
            bookshelves = listOf(
                LocalBookshelf(DEFAULT_BOOKS_BOOKSHELF_ID, "Books", LocalLibraryContentType.BOOKS),
            ),
            enabledContentTypes = setOf(LocalLibraryContentType.BOOKS),
        ).migrate(sourceId = 42L)

        assertEquals(setOf(LocalLibraryContentType.BOOKS), config.enabledContentTypes)
        assertTrue(config.bookshelvesFor(LocalLibraryContentType.COMICS).isEmpty())
        assertEquals(
            listOf(DEFAULT_BOOKS_BOOKSHELF_ID),
            config.bookshelvesFor(LocalLibraryContentType.BOOKS).map { it.id },
        )
        assertEquals(7, config.schemaVersion)
    }

    @Test
    fun `version five libraries keep their built in default bookshelf first`() {
        val migrated = LocalLibraryConfig(
            bookshelves = listOf(
                LocalBookshelf("comic-custom", "Archive", LocalLibraryContentType.COMICS),
                LocalBookshelf("book-custom", "Novels", LocalLibraryContentType.BOOKS),
            ),
            schemaVersion = 5,
        ).migrate(sourceId = 42L)

        assertEquals(
            listOf(DEFAULT_COMICS_BOOKSHELF_ID, "comic-custom"),
            migrated.bookshelvesFor(LocalLibraryContentType.COMICS).map { it.id },
        )
        assertEquals(
            listOf(DEFAULT_BOOKS_BOOKSHELF_ID, "book-custom"),
            migrated.bookshelvesFor(LocalLibraryContentType.BOOKS).map { it.id },
        )
    }

    @Test
    fun `custom bookshelf can become default without changing an existing root mode`() {
        val root = LocalLibraryRootConfig(
            id = "root",
            contentType = LocalLibraryContentType.BOOKS,
        )
        val config = LocalLibraryConfig(
            roots = listOf(root),
            bookshelves = listOf(
                LocalBookshelf(
                    DEFAULT_BOOKS_BOOKSHELF_ID,
                    "Series",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.SERIES,
                ),
                LocalBookshelf(
                    "individual",
                    "Loose books",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                ),
            ),
        )

        val updated = config.withDefaultBookshelf(LocalLibraryContentType.BOOKS, "individual")

        assertEquals("individual", updated.defaultBookshelfId(LocalLibraryContentType.BOOKS))
        assertEquals(DEFAULT_BOOKS_BOOKSHELF_ID, updated.roots.single().bookshelfId)
        assertEquals(LocalLibraryOrganizationMode.SERIES, updated.organizationMode(updated.roots.single()))
    }

    @Test
    fun `deleting default bookshelf promotes first remaining compatible bookshelf`() {
        val config = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(
                    id = "root",
                    contentType = LocalLibraryContentType.COMICS,
                    bookshelfId = DEFAULT_COMICS_BOOKSHELF_ID,
                ),
            ),
            bookshelves = listOf(
                LocalBookshelf(DEFAULT_COMICS_BOOKSHELF_ID, "Default", LocalLibraryContentType.COMICS),
                LocalBookshelf("archive", "Archive", LocalLibraryContentType.COMICS),
                LocalBookshelf(
                    "loose",
                    "Loose",
                    LocalLibraryContentType.COMICS,
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                ),
            ),
        )

        val removal = checkNotNull(
            config.withoutBookshelf(DEFAULT_COMICS_BOOKSHELF_ID, mapOf("item" to DEFAULT_COMICS_BOOKSHELF_ID)),
        )

        assertEquals("archive", removal.config.defaultBookshelfId(LocalLibraryContentType.COMICS))
        assertEquals("archive", removal.config.roots.single().bookshelfId)
        assertEquals("archive", removal.assignments["item"])
    }

    @Test
    fun `last bookshelf and referenced bookshelf without compatible replacement cannot be deleted`() {
        val onlyShelf = LocalLibraryConfig(
            bookshelves = listOf(LocalBookshelf("only", "Only", LocalLibraryContentType.COMICS)),
        )
        assertFalse(onlyShelf.canRemoveBookshelf("only", emptyMap()))

        val incompatibleReplacement = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(
                    id = "root",
                    contentType = LocalLibraryContentType.BOOKS,
                    bookshelfId = "series",
                ),
            ),
            bookshelves = listOf(
                LocalBookshelf("series", "Series", LocalLibraryContentType.BOOKS),
                LocalBookshelf(
                    "individual",
                    "Loose",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                ),
            ),
        )
        assertFalse(incompatibleReplacement.canRemoveBookshelf("series", emptyMap()))
    }

    @Test
    fun `bookshelves keep independent organization modes`() {
        val config = LocalLibraryConfig(
            bookshelves = listOf(
                LocalBookshelf(
                    DEFAULT_COMICS_BOOKSHELF_ID,
                    "",
                    LocalLibraryContentType.COMICS,
                    LocalLibraryOrganizationMode.SERIES,
                ),
                LocalBookshelf(
                    DEFAULT_BOOKS_BOOKSHELF_ID,
                    "",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                ),
            ),
        ).migrate(sourceId = 42L)

        assertEquals(
            LocalLibraryOrganizationMode.SERIES,
            config.bookshelvesFor(LocalLibraryContentType.COMICS).single().organizationMode,
        )
        assertEquals(
            LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
            config.bookshelvesFor(LocalLibraryContentType.BOOKS).single().organizationMode,
        )
    }

    @Test
    fun `root organization mode follows its assigned bookshelf`() {
        val root = LocalLibraryRootConfig(
            id = "books",
            contentType = LocalLibraryContentType.BOOKS,
            bookshelfId = "loose-books",
        )
        val config = LocalLibraryConfig(
            roots = listOf(root),
            bookshelves = listOf(
                LocalBookshelf(
                    "loose-books",
                    "Loose books",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                ),
            ),
        )

        assertEquals(LocalLibraryOrganizationMode.INDIVIDUAL_FILES, config.organizationMode(root))
    }

    @Test
    fun `file entry index survives serialization`() {
        val index = LocalLibraryIndex(
            items = listOf(
                LocalLibraryItem(
                    itemKey = "entry",
                    rootId = "books",
                    relativePath = "Nested/Book.epub",
                    contentType = LocalLibraryContentType.BOOKS,
                    kind = LocalLibraryItem.Kind.FILE_ENTRY,
                    format = "epub",
                    sizeBytes = 42L,
                    modifiedAt = 7L,
                ),
            ),
        )

        val restored = Json.decodeFromString<LocalLibraryIndex>(Json.encodeToString(index))

        assertEquals(4, restored.schemaVersion)
        assertEquals(LocalLibraryItem.Kind.FILE_ENTRY, restored.items.single().kind)
        assertEquals("Nested/Book.epub", restored.items.single().relativePath)
    }

    @Test
    fun `root image directory locator uses a reserved stable path`() {
        val url = LocalLibraryLocator.entryUrl(
            sourceId = 42L,
            rootId = "images",
            relativePath = LocalLibraryLocator.ROOT_DIRECTORY_ENTRY,
        )

        assertEquals(
            LocalLibraryLocator.ROOT_DIRECTORY_ENTRY,
            LocalLibraryLocator.relativePath(url, 42L),
        )
    }

    @Test
    fun `renamed default bookshelves survive serialization and migration`() {
        val original = LocalLibraryConfig(
            bookshelves = listOf(
                LocalBookshelf(
                    DEFAULT_COMICS_BOOKSHELF_ID,
                    "My comics",
                    LocalLibraryContentType.COMICS,
                ),
                LocalBookshelf(
                    DEFAULT_BOOKS_BOOKSHELF_ID,
                    "My books",
                    LocalLibraryContentType.BOOKS,
                ),
                LocalBookshelf("comic-custom", "Archive", LocalLibraryContentType.COMICS),
            ),
        )

        val restored = Json.decodeFromString<LocalLibraryConfig>(
            Json.encodeToString(original),
        ).migrate(sourceId = 42L)

        assertEquals(
            listOf(DEFAULT_COMICS_BOOKSHELF_ID, "comic-custom"),
            restored.bookshelvesFor(LocalLibraryContentType.COMICS).map { it.id },
        )
        assertEquals(
            listOf("My comics", "Archive"),
            restored.bookshelvesFor(LocalLibraryContentType.COMICS).map { it.name },
        )
        assertEquals(
            "My books",
            restored.bookshelvesFor(LocalLibraryContentType.BOOKS).first().name,
        )
    }

    @Test
    fun `invalid default bookshelf type is discarded during migration`() {
        val migrated = LocalLibraryConfig(
            bookshelves = listOf(
                LocalBookshelf(
                    DEFAULT_COMICS_BOOKSHELF_ID,
                    "Wrong type",
                    LocalLibraryContentType.BOOKS,
                ),
            ),
        ).migrate(sourceId = 42L)

        assertEquals("", migrated.bookshelvesFor(LocalLibraryContentType.COMICS).first().name)
        assertEquals(
            listOf(DEFAULT_BOOKS_BOOKSHELF_ID),
            migrated.bookshelvesFor(LocalLibraryContentType.BOOKS).map { it.id },
        )
    }

    @Test
    fun `item assignment overrides directory and otherwise uses default`() {
        val root = LocalLibraryRootConfig(
            id = "root",
            contentType = LocalLibraryContentType.COMICS,
            bookshelfId = "directory-shelf",
        )
        val config = LocalLibraryConfig(
            roots = listOf(root),
            bookshelves = listOf(
                LocalBookshelf("directory-shelf", "Directory", LocalLibraryContentType.COMICS),
                LocalBookshelf("item-shelf", "Item", LocalLibraryContentType.COMICS),
            ),
        )

        assertEquals("directory-shelf", config.effectiveBookshelfId(root, "item", emptyMap()))
        assertEquals("item-shelf", config.effectiveBookshelfId(root, "item", mapOf("item" to "item-shelf")))
        assertEquals(
            "directory-shelf",
            config.effectiveBookshelfId(root.copy(bookshelfId = "missing"), "item", emptyMap()),
        )
    }

    @Test
    fun `item assignment cannot cross organization modes`() {
        val root = LocalLibraryRootConfig(
            id = "root",
            contentType = LocalLibraryContentType.BOOKS,
            bookshelfId = "individual",
        )
        val config = LocalLibraryConfig(
            roots = listOf(root),
            bookshelves = listOf(
                LocalBookshelf(
                    "individual",
                    "Loose books",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.INDIVIDUAL_FILES,
                ),
                LocalBookshelf(
                    "series",
                    "Book series",
                    LocalLibraryContentType.BOOKS,
                    LocalLibraryOrganizationMode.SERIES,
                ),
            ),
        )

        assertEquals(
            "individual",
            config.effectiveBookshelfId(root, "item", mapOf("item" to "series")),
        )
    }

    @Test
    fun `mixed or incomplete roots keep a unified library tab`() {
        val mixed = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(id = "mixed", contentType = LocalLibraryContentType.MIXED),
            ),
        )
        val comicsOnly = LocalLibraryConfig(
            roots = listOf(
                LocalLibraryRootConfig(id = "comics", contentType = LocalLibraryContentType.COMICS),
            ),
        )

        assertEquals(setOf(LibraryContentScope.ALL), localLibraryContentScopes(mixed))
        assertEquals(setOf(LibraryContentScope.ALL), localLibraryContentScopes(comicsOnly))
    }
}
