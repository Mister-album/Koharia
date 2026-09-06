package koharia.epub

import koharia.connection.LibraryContentScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryReaderRouteTest {
    @Test
    fun `the same PDF follows its owning library`() {
        assertEquals(LibraryReaderRoute.PDF_REFLOW, libraryReaderRoute(LibraryContentScope.BOOK, "PDF", false, true))
        assertEquals(LibraryReaderRoute.PAGES, libraryReaderRoute(LibraryContentScope.COMIC, "PDF", false, true))
        assertEquals(LibraryReaderRoute.PAGES, libraryReaderRoute(LibraryContentScope.ALL, "pdf", false, true))
    }

    @Test
    fun `book libraries prefer native EPUB and comic libraries preserve image paging`() {
        assertEquals(LibraryReaderRoute.NATIVE_TEXT, libraryReaderRoute(LibraryContentScope.BOOK, "epub", true, true))
        assertEquals(LibraryReaderRoute.PAGES, libraryReaderRoute(LibraryContentScope.COMIC, "epub", true, true))
        assertEquals(LibraryReaderRoute.NATIVE_TEXT, libraryReaderRoute(LibraryContentScope.ALL, "epub", true, false))
        assertEquals(LibraryReaderRoute.PAGES, libraryReaderRoute(LibraryContentScope.BOOK, "cbz", false, true))
    }
}
