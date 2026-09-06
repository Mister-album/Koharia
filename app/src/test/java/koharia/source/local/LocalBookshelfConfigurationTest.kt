package koharia.source.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalBookshelfConfigurationTest {
    private val initial = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
    private val booksId = initial.defaultBookshelfId(LocalLibraryContentType.BOOKS)
    private val comicsId = initial.defaultBookshelfId(LocalLibraryContentType.COMICS)

    @Test
    fun `new connection automatically contains both libraries without requiring directories`() {
        assertEquals(setOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS), initial.enabledContentTypes)
        assertEquals(2, initial.bookshelves.size)
        assertEquals(LocalLibraryOrganizationMode.SERIES, initial.bookshelf(comicsId)?.organizationMode)
        assertEquals(LocalLibraryOrganizationMode.INDIVIDUAL_FILES, initial.bookshelf(booksId)?.organizationMode)
        assertTrue(initial.roots.isEmpty())
    }

    @Test
    fun `existing library names modes and bindings are preserved`() {
        val existing = initial.copy(
            setupCompleted = true,
            enabledContentTypes = setOf(LocalLibraryContentType.BOOKS),
            bookshelves = listOf(LocalBookshelf("custom", "My books", LocalLibraryContentType.BOOKS)),
            roots = listOf(LocalLibraryRootConfig(id = "old", bookshelfId = "custom")),
        )
        assertSame(existing, existing.withInitialBookshelves("New comics", "New books"))
        assertEquals(existing.roots, existing.bookshelfRoots("custom"))
    }

    @Test
    fun `directory inherits its owning library instead of choosing a separate type`() {
        val next = initial.withBookshelfDirectory(
            booksId,
            LocalLibraryRootConfig(id = "book-root", treeUri = "file:///books"),
        )
        val root = next.roots.single()
        assertEquals(booksId, root.bookshelfId)
        assertEquals(LocalLibraryContentType.BOOKS, root.contentType)
        assertEquals(listOf(root), next.bookshelfRoots(booksId))
        assertTrue(next.bookshelfRoots(comicsId).isEmpty())
    }

    @Test
    fun `replacement changes only the selected library directory`() {
        val original = initial
            .withBookshelfDirectory(booksId, LocalLibraryRootConfig(id = "books", treeUri = "file:///books"))
            .withBookshelfDirectory(comicsId, LocalLibraryRootConfig(id = "comics", treeUri = "file:///comics"))
        val updated = original.withBookshelfDirectory(
            booksId,
            LocalLibraryRootConfig(id = "new-books", treeUri = "file:///new-books"),
            "books",
        )
        assertEquals(listOf("new-books"), updated.bookshelfRoots(booksId).map { it.id })
        assertEquals(original.bookshelfRoots(comicsId), updated.bookshelfRoots(comicsId))
        assertThrows(IllegalArgumentException::class.java) {
            original.withBookshelfDirectory(
                booksId,
                LocalLibraryRootConfig(id = "bad", treeUri = "file:///bad"),
                "comics",
            )
        }
    }

    @Test
    fun `same SAF directory cannot be bound twice through different tree grants`() {
        val original = initial.withBookshelfDirectory(
            booksId,
            LocalLibraryRootConfig(
                id = "books",
                treeUri = "content://documents/tree/primary%3ALibrary",
                relativePath = "Books",
            ),
        )
        val sameDirectory =
            LocalLibraryRootConfig(id = "duplicate", treeUri = "content://documents/tree/primary%3ALibrary%2FBooks")
        assertThrows(IllegalArgumentException::class.java) { original.withBookshelfDirectory(comicsId, sameDirectory) }
        assertSame(original, original.withBookshelfDirectory(booksId, sameDirectory, "books"))
    }

    @Test
    fun `adding a previously disabled type allows choosing its initial mode without changing the existing library`() {
        val saved = initial.copy(enabledContentTypes = setOf(LocalLibraryContentType.BOOKS), setupCompleted = true)
            .enabledLibraryConfiguration()
        val newShelf = LocalBookshelf("new-comics", "Comics", LocalLibraryContentType.COMICS)
        val draft = saved.copy(
            enabledContentTypes = saved.enabledContentTypes + LocalLibraryContentType.COMICS,
            bookshelves = saved.bookshelves + newShelf,
        )
        assertTrue(draft.canEditBookshelfMode(newShelf.id, saved, emptyMap()))
        assertTrue(!draft.canEditBookshelfMode(booksId, saved, emptyMap()))
        assertEquals(
            saved.bookshelvesFor(LocalLibraryContentType.BOOKS),
            draft.bookshelvesFor(LocalLibraryContentType.BOOKS),
        )
        assertTrue(!draft.canEditBookshelfMode(newShelf.id, draft, emptyMap()))
        val withDirectory = draft.withBookshelfDirectory(
            newShelf.id,
            LocalLibraryRootConfig(id = "root", treeUri = "file:///comics"),
        )
        assertTrue(!withDirectory.canEditBookshelfMode(newShelf.id, saved, emptyMap()))
        assertTrue(!draft.canEditBookshelfMode(newShelf.id, saved, mapOf("item" to newShelf.id)))
    }
}
