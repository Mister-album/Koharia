package koharia.epub

import koharia.connection.LibraryContentScope

internal enum class LibraryReaderRoute { PAGES, NATIVE_TEXT, PDF_REFLOW }

internal fun libraryReaderRoute(
    scope: LibraryContentScope,
    extension: String?,
    nativeText: Boolean,
    pageCompatible: Boolean,
): LibraryReaderRoute = when {
    extension.equals("pdf", true) && scope == LibraryContentScope.BOOK -> LibraryReaderRoute.PDF_REFLOW
    extension.equals("pdf", true) -> LibraryReaderRoute.PAGES
    nativeText && (scope == LibraryContentScope.BOOK || !pageCompatible) -> LibraryReaderRoute.NATIVE_TEXT
    else -> LibraryReaderRoute.PAGES
}
