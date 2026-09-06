package koharia.source.local

import koharia.connection.LibraryConnectionProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalLibraryInitialSetupTest {
    private val config = LocalLibraryConfig().withInitialBookshelves("Comics", "Books")
    private val local = LibraryConnectionProfile(1, LocalFolderConnectionProvider.ID, "Local")

    @Test
    fun `first local connection creates directories even when network connections already exist`() {
        assertTrue(shouldCreateInitialLocalDirectories(1, config, listOf(local)))
        assertTrue(
            shouldCreateInitialLocalDirectories(
                1,
                config,
                listOf(LibraryConnectionProfile(2, "komga", "Server"), local),
            ),
        )
    }

    @Test
    fun `later or already configured local connections are untouched`() {
        assertFalse(shouldCreateInitialLocalDirectories(2, config, listOf(local, local.copy(id = 2))))
        assertFalse(shouldCreateInitialLocalDirectories(1, config.copy(setupCompleted = true), listOf(local)))
        assertFalse(
            shouldCreateInitialLocalDirectories(
                1,
                config.copy(roots = listOf(LocalLibraryRootConfig(id = "old"))),
                listOf(local),
            ),
        )
    }

    @Test
    fun `both default libraries require an explicit organization choice`() {
        val comics = config.defaultBookshelfId(LocalLibraryContentType.COMICS)
        val books = config.defaultBookshelfId(LocalLibraryContentType.BOOKS)
        assertFalse(config.hasSelectedDefaultModes(emptyList()))
        assertFalse(config.hasSelectedDefaultModes(listOf(comics)))
        assertFalse(config.hasSelectedDefaultModes(listOf(books)))
        assertFalse(config.hasSelectedDefaultModes(listOf("other", comics)))
        assertTrue(config.hasSelectedDefaultModes(listOf(comics, books)))
        assertTrue(config.copy(setupCompleted = true).hasSelectedDefaultModes(emptyList()))
    }

    @Test
    fun `only enabled libraries require a mode and both disabled cannot continue`() {
        val comics = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.COMICS))
        val books = config.copy(enabledContentTypes = setOf(LocalLibraryContentType.BOOKS))
        assertTrue(comics.hasSelectedDefaultModes(listOf(comics.defaultBookshelfId(LocalLibraryContentType.COMICS))))
        assertTrue(books.hasSelectedDefaultModes(listOf(books.defaultBookshelfId(LocalLibraryContentType.BOOKS))))
        assertFalse(books.hasSelectedDefaultModes(listOf(config.defaultBookshelfId(LocalLibraryContentType.COMICS))))
        val none = config.copy(enabledContentTypes = emptySet())
        assertFalse(none.hasSelectedDefaultModes(config.bookshelves.map { it.id }))
        assertThrows(IllegalArgumentException::class.java) { none.enabledLibraryConfiguration() }
    }

    @Test
    fun `disabled draft libraries and roots are not revived when saved configuration migrates`() {
        val draft = config.copy(
            enabledContentTypes = setOf(LocalLibraryContentType.BOOKS),
            roots = listOf(
                LocalLibraryRootConfig(id = "comics", contentType = LocalLibraryContentType.COMICS),
                LocalLibraryRootConfig(id = "books", contentType = LocalLibraryContentType.BOOKS),
            ),
        )
        val saved = draft.enabledLibraryConfiguration().migrate(1)
        assertEquals(setOf(LocalLibraryContentType.BOOKS), saved.enabledContentTypes)
        assertEquals(listOf(LocalLibraryContentType.BOOKS), saved.bookshelves.map { it.contentType })
        assertEquals(listOf("books"), saved.roots.map { it.id })
        assertEquals(2, draft.bookshelves.size)
        assertEquals(2, draft.roots.size)
    }
}
