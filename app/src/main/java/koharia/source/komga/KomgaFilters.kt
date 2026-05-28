package koharia.source.komga

import koharia.komga.api.dto.AuthorDto
import koharia.komga.api.dto.LibraryDto
import eu.kanade.tachiyomi.source.model.Filter

class TypeSelect :
    Filter.Select<String>(
        "Search for",
        arrayOf(
            KomgaSource.TYPE_SERIES,
            KomgaSource.TYPE_READ_LISTS,
            KomgaSource.TYPE_BOOKS,
        ),
    )

class SeriesSort(selection: Selection? = null) :
    Filter.Sort(
        "Sort",
        arrayOf("Relevance", "Alphabetically", "Date added", "Date updated", "Random"),
        selection ?: Selection(0, true),
    )

class UnreadFilter : Filter.CheckBox("Unread", false)

class InProgressFilter : Filter.CheckBox("In Progress", false)

class ReadFilter : Filter.CheckBox("Read", false)

class LibraryFilter(
    libraries: List<LibraryDto>,
    defaultLibraries: Set<String>,
) : UriMultiSelectFilter(
    "Libraries",
    libraries.map {
        UriMultiSelectOption(it.name, it.id).apply {
            state = defaultLibraries.contains(it.id)
        }
    },
)

class UriMultiSelectOption(name: String, val id: String = name) : Filter.CheckBox(name, false)

open class UriMultiSelectFilter(
    name: String,
    entries: List<UriMultiSelectOption>,
) : Filter.Group<UriMultiSelectOption>(name, entries)

class AuthorFilter(val author: AuthorDto) : Filter.CheckBox(author.name, false)

class AuthorGroup(
    role: String,
    authors: List<AuthorFilter>,
) : Filter.Group<AuthorFilter>(role.replaceFirstChar { it.titlecase() }, authors)

class CollectionSelect(
    val collections: List<CollectionFilterEntry>,
) : Filter.Select<String>("Collection", collections.map { it.name }.toTypedArray())

data class CollectionFilterEntry(
    val name: String,
    val id: String? = null,
)
