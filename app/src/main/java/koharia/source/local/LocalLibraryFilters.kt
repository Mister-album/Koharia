package koharia.source.local

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import koharia.connection.LibraryContentScope
import kotlinx.serialization.Serializable
import tachiyomi.core.common.preference.TriState

@Immutable
@Serializable
data class LocalLibraryFilters(
    val series: String = "",
    val chapter: String = "",
    val author: String = "",
    val artist: String = "",
    val genre: String = "",
    val format: String = "",
    val sort: Int = 0,
    val descending: Boolean = false,
    val foldersFirst: Boolean = false,
    val downloaded: TriState = TriState.DISABLED,
    val unread: TriState = TriState.DISABLED,
    val started: TriState = TriState.DISABLED,
    val bookmarked: TriState = TriState.DISABLED,
    val completed: TriState = TriState.DISABLED,
    val excludedScanlators: Set<String> = emptySet(),
) {
    val isActive: Boolean
        get() = series.isNotBlank() ||
            chapter.isNotBlank() ||
            author.isNotBlank() ||
            artist.isNotBlank() ||
            genre.isNotBlank() ||
            format.isNotBlank() ||
            sort != 0 ||
            descending ||
            foldersFirst ||
            hasEntryStateFilters ||
            completed != TriState.DISABLED

    val hasEntryStateFilters: Boolean
        get() = downloaded != TriState.DISABLED || unread != TriState.DISABLED ||
            started != TriState.DISABLED || bookmarked != TriState.DISABLED || excludedScanlators.isNotEmpty()

    fun toFilterList(scope: LibraryContentScope, bookshelfId: String? = null): FilterList = FilterList(
        LocalLibraryScopeFilter(scope),
        LocalBookshelfFilter(bookshelfId.orEmpty()),
        LocalSeriesFilter(series.trim()),
        LocalChapterFilter(chapter.trim()),
        LocalAuthorFilter(author.trim()),
        LocalArtistFilter(artist.trim()),
        LocalGenreFilter(genre.trim()),
        LocalFormatFilter(format.trim()),
    )
}

internal fun FilterList.localLibraryFilters(): LocalLibraryFilters = LocalLibraryFilters(
    series = filterIsInstance<LocalSeriesFilter>().firstOrNull()?.state.orEmpty(),
    chapter = filterIsInstance<LocalChapterFilter>().firstOrNull()?.state.orEmpty(),
    author = filterIsInstance<LocalAuthorFilter>().firstOrNull()?.state.orEmpty(),
    artist = filterIsInstance<LocalArtistFilter>().firstOrNull()?.state.orEmpty(),
    genre = filterIsInstance<LocalGenreFilter>().firstOrNull()?.state.orEmpty(),
    format = filterIsInstance<LocalFormatFilter>().firstOrNull()?.state.orEmpty(),
)

internal fun FilterList.localBookshelfId(): String? =
    filterIsInstance<LocalBookshelfFilter>().firstOrNull()?.state?.takeIf(String::isNotBlank)

internal class LocalBookshelfFilter(state: String = "") : Filter.Text("local-library-bookshelf", state)

internal class LocalSeriesFilter(state: String = "") : Filter.Text("local-library-series", state)

internal class LocalChapterFilter(state: String = "") : Filter.Text("local-library-chapter", state)

internal class LocalAuthorFilter(state: String = "") : Filter.Text("local-library-author", state)

internal class LocalArtistFilter(state: String = "") : Filter.Text("local-library-artist", state)

internal class LocalGenreFilter(state: String = "") : Filter.Text("local-library-genre", state)

internal class LocalFormatFilter(state: String = "") : Filter.Text("local-library-format", state)
