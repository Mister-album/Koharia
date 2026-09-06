package koharia.source.local

import io.mockk.coEvery
import io.mockk.mockk
import koharia.connection.ConnectionLibraryShelf
import koharia.connection.ConnectionLibraryShelfAdapter
import koharia.connection.LibraryContentScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga

class LocalLibraryItemActionsTest {
    @Test
    fun `batch move offers only destinations compatible with every item`() = runTest {
        val adapter = mockk<ConnectionLibraryShelfAdapter>()
        val first = Manga.create().copy(url = "one")
        val second = Manga.create().copy(url = "two")
        val shared = ConnectionLibraryShelf("shared", "Shared", LibraryContentScope.BOOK)
        val other = shared.copy(id = "other", name = "Other")
        coEvery { adapter.compatibleLibraryShelves(first.url) } returns listOf(other, shared)
        coEvery { adapter.compatibleLibraryShelves(second.url) } returns listOf(shared)
        assertEquals(listOf(shared), commonLocalLibraryShelves(adapter, listOf(first, second)))
        assertEquals(listOf(other, shared), commonLocalLibraryShelves(adapter, listOf(first)))
    }

    @Test
    fun `mixed incompatible items have no shared destination`() = runTest {
        val adapter = mockk<ConnectionLibraryShelfAdapter>()
        val book = Manga.create().copy(url = "book")
        val comic = Manga.create().copy(url = "comic")
        coEvery { adapter.compatibleLibraryShelves(book.url) } returns
            listOf(ConnectionLibraryShelf("books", "Books", LibraryContentScope.BOOK))
        coEvery { adapter.compatibleLibraryShelves(comic.url) } returns
            listOf(ConnectionLibraryShelf("comics", "Comics", LibraryContentScope.COMIC))
        assertTrue(commonLocalLibraryShelves(adapter, listOf(book, comic)).isEmpty())
        assertTrue(commonLocalLibraryShelves(adapter, emptyList()).isEmpty())
    }
}
