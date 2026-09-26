package koharia.source.komga

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import koharia.komga.api.dto.AuthorDto
import koharia.komga.api.dto.LibraryDto

class TypeSelect :
    Filter.Select<String>(
        "Search for",
        arrayOf(
            KomgaSource.TYPE_SERIES,
            KomgaSource.TYPE_READ_LISTS,
            KomgaSource.TYPE_BOOKS,
            KomgaSource.TYPE_ALL,
        ),
        TYPE_SERIES_INDEX,
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

class OneshotFilter : Filter.CheckBox("Oneshot", false)

class ReadingStateGroup :
    Filter.Group<Filter.CheckBox>(
        "Reading status and type",
        listOf(
            UnreadFilter(),
            InProgressFilter(),
            ReadFilter(),
            OneshotFilter(),
        ),
    )

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

internal const val TYPE_SERIES_INDEX = 0
internal const val TYPE_READ_LISTS_INDEX = 1
internal const val TYPE_BOOKS_INDEX = 2
internal const val TYPE_ALL_INDEX = 3

/** Copy the current options as well as their selections, including options known only offline. */
internal fun FilterList.snapshotKomgaFilters(): FilterList = FilterList(
    map { filter ->
        when (filter) {
            is TypeSelect -> TypeSelect().apply { state = filter.state }
            is SeriesSort -> SeriesSort(filter.state?.copy())
            is CollectionSelect -> CollectionSelect(filter.collections.toList()).apply { state = filter.state }
            is LibraryFilter -> LibraryFilter(
                filter.state.map { LibraryDto(it.id, it.name) },
                filter.state.filter { it.state }.mapTo(mutableSetOf()) { it.id },
            )
            is UriMultiSelectFilter -> UriMultiSelectFilter(
                filter.name,
                filter.state.map { option ->
                    UriMultiSelectOption(option.name, option.id).apply { state = option.state }
                },
            )
            is ReadingStateGroup -> ReadingStateGroup().apply {
                state.zip(filter.state).forEach { (copy, original) -> copy.state = original.state }
            }
            is AuthorGroup -> AuthorGroup(
                filter.name,
                filter.state.map { option ->
                    AuthorFilter(option.author).apply { state = option.state }
                },
            )
            is Filter.Header -> Filter.Header(filter.name)
            is Filter.Separator -> Filter.Separator(filter.name)
            else -> error("Unsupported Komga filter: ${filter.name}")
        }
    },
)
