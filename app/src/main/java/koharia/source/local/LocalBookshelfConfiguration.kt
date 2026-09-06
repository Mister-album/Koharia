package koharia.source.local

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal fun LocalLibraryConfig.withInitialBookshelves(comicsName: String, booksName: String): LocalLibraryConfig {
    if (setupCompleted || roots.isNotEmpty()) return this
    val types = setOf(LocalLibraryContentType.COMICS, LocalLibraryContentType.BOOKS)
    val enabled = copy(enabledContentTypes = types)
    return enabled.copy(
        bookshelves = types.flatMap { type ->
            enabled.bookshelvesFor(type).map { shelf ->
                if (shelf.name.isNotBlank()) {
                    shelf
                } else {
                    shelf.copy(
                        name = if (type == LocalLibraryContentType.COMICS) comicsName else booksName,
                        organizationMode = if (type == LocalLibraryContentType.COMICS) {
                            LocalLibraryOrganizationMode.SERIES
                        } else {
                            LocalLibraryOrganizationMode.INDIVIDUAL_FILES
                        },
                    )
                }
            }
        },
    )
}

internal fun LocalLibraryConfig.rootBookshelfId(root: LocalLibraryRootConfig): String =
    root.bookshelfId.takeIf { bookshelf(it) != null }
        ?: defaultBookshelfId(root.contentType).takeIf(String::isNotBlank)
        ?: bookshelves.firstOrNull()?.id.orEmpty()

internal fun LocalLibraryConfig.bookshelfRoots(shelfId: String): List<LocalLibraryRootConfig> =
    roots.filter { rootBookshelfId(it) == shelfId }

internal fun LocalLibraryConfig.canEditBookshelfMode(
    shelfId: String,
    savedConfig: LocalLibraryConfig,
    assignments: Map<String, String>,
): Boolean = bookshelf(shelfId) != null &&
    !(savedConfig.bookshelf(shelfId) != null && isDefaultBookshelf(shelfId)) &&
    bookshelfRoots(shelfId).isEmpty() && shelfId !in assignments.values

internal fun LocalLibraryConfig.withBookshelfDirectory(
    shelfId: String,
    directory: LocalLibraryRootConfig,
    replacingRootId: String? = null,
): LocalLibraryConfig {
    val shelf = requireNotNull(bookshelf(shelfId))
    require(replacingRootId == null || bookshelfRoots(shelfId).any { it.id == replacingRootId })
    require(
        roots.none {
            it.id != replacingRootId && it.directoryKey() == directory.directoryKey()
        },
    ) { "Directory already belongs to a library" }
    if (roots.any { it.id == replacingRootId && it.directoryKey() == directory.directoryKey() }) return this
    val replacement = directory.copy(bookshelfId = shelf.id, contentType = shelf.contentType)
    return copy(roots = roots.filterNot { it.id == replacingRootId } + replacement)
}

private fun LocalLibraryRootConfig.directoryKey(): String {
    val uri = URI(treeUri)
    val base = if (uri.scheme == "content" && uri.rawPath.orEmpty().startsWith("/tree/")) {
        val documentId = URLDecoder.decode(
            uri.rawPath.substringAfter("/tree/").substringBefore('/'),
            StandardCharsets.UTF_8.name(),
        )
        "${uri.authority}:$documentId"
    } else {
        uri.normalize().toString().trimEnd('/')
    }
    return listOf(base.trimEnd('/'), LocalLibraryLocator.normalize(relativePath))
        .filter(String::isNotBlank).joinToString("/")
}
