package koharia.suwayomi

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

/**
 * Client-side filters the source listing supports beyond the source's own filter list. They narrow
 * the pages the server returns, so they combine with the source filters instead of replacing them.
 */
data class SuwayomiBrowseFilters(
    /** Drops series the server already tracks in the library, the way the browse-hide setting does. */
    val hideInLibrary: Boolean = false,
) {
    val active: Boolean get() = hideInLibrary

    companion object {
        val NONE = SuwayomiBrowseFilters()
    }
}

/** Applies the client-side filters and the chosen sort to one page of source results. */
fun applySuwayomiBrowseFilters(
    mangas: List<SuwayomiManga>,
    filters: SuwayomiBrowseFilters,
    sort: SuwayomiBrowseSort,
): List<SuwayomiManga> {
    val filtered = if (filters.hideInLibrary) mangas.filterNot { it.inLibrary } else mangas
    return when (sort) {
        SuwayomiBrowseSort.SOURCE -> filtered
        // Only meaningful within a page; the server's own ordering decides across pages.
        SuwayomiBrowseSort.TITLE_ASCENDING -> filtered.sortedBy { it.title.lowercase() }
        SuwayomiBrowseSort.TITLE_DESCENDING -> filtered.sortedByDescending { it.title.lowercase() }
    }
}

/** Client-side ordering for series the server returns in source order. */
enum class SuwayomiBrowseSort { SOURCE, TITLE_ASCENDING, TITLE_DESCENDING }

/**
 * Library shelf filters, matching the main library's narrowing: each condition is a tri-state so a
 * series can be required, excluded, or ignored. Everything here is derived from fields the server
 * already returns with the shelf, so no extra request is needed to filter.
 */
data class SuwayomiLibraryFilters(
    val downloaded: SuwayomiTriState = SuwayomiTriState.IGNORE,
    val unread: SuwayomiTriState = SuwayomiTriState.IGNORE,
    val started: SuwayomiTriState = SuwayomiTriState.IGNORE,
    val bookmarked: SuwayomiTriState = SuwayomiTriState.IGNORE,
    val completed: SuwayomiTriState = SuwayomiTriState.IGNORE,
    /** Minimum number of chapters the series must have; null means any. */
    val minimumChapters: Int? = null,
    /** Case-insensitive substring matches; blank means the field is not constrained. */
    val title: String = "",
    val author: String = "",
    val artist: String = "",
    /** Required genres, lower-cased. Empty means every genre is accepted. */
    val genres: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() = listOf(downloaded, unread, started, bookmarked, completed).count { it != SuwayomiTriState.IGNORE } +
            (if (minimumChapters != null) 1 else 0) +
            listOf(title, author, artist).count { it.isNotBlank() } +
            (if (genres.isNotEmpty()) 1 else 0)

    val active: Boolean get() = activeCount > 0

    companion object {
        val NONE = SuwayomiLibraryFilters()
    }
}

enum class SuwayomiTriState { IGNORE, INCLUDE, EXCLUDE }

/** The shelf conditions, mapped to the tri-state fields of [SuwayomiLibraryFilters]. */
enum class SuwayomiLibraryFilter(val labelRes: StringResource) {
    DOWNLOADED(MR.strings.label_downloaded),
    UNREAD(MR.strings.action_filter_unread),
    STARTED(MR.strings.label_started),
    BOOKMARKED(MR.strings.action_filter_bookmarked),
    COMPLETED(MR.strings.completed),
}

fun SuwayomiLibraryFilters.triStateOf(key: SuwayomiLibraryFilter): SuwayomiTriState = when (key) {
    SuwayomiLibraryFilter.DOWNLOADED -> downloaded
    SuwayomiLibraryFilter.UNREAD -> unread
    SuwayomiLibraryFilter.STARTED -> started
    SuwayomiLibraryFilter.BOOKMARKED -> bookmarked
    SuwayomiLibraryFilter.COMPLETED -> completed
}

fun SuwayomiLibraryFilters.with(
    key: SuwayomiLibraryFilter,
    state: SuwayomiTriState,
): SuwayomiLibraryFilters = when (key) {
    SuwayomiLibraryFilter.DOWNLOADED -> copy(downloaded = state)
    SuwayomiLibraryFilter.UNREAD -> copy(unread = state)
    SuwayomiLibraryFilter.STARTED -> copy(started = state)
    SuwayomiLibraryFilter.BOOKMARKED -> copy(bookmarked = state)
    SuwayomiLibraryFilter.COMPLETED -> copy(completed = state)
}

/** One shelf entry's filterable facts, resolved from the server payload. */
data class SuwayomiLibraryFacts(
    val title: String,
    val author: String?,
    val artist: String?,
    val genres: List<String>,
    val status: String?,
    val unreadCount: Int,
    val totalChapters: Int,
    val downloadedCount: Int,
    val bookmarkedCount: Int,
    val hasProgress: Boolean,
) {
    val completed: Boolean get() = status.equals("COMPLETED", ignoreCase = true)
}

/**
 * Applies the shelf filters. A series leaves the shelf as soon as one condition rejects it, and an
 * [SuwayomiTriState.EXCLUDE] condition rejects exactly the series an include would keep.
 */
fun suwayomiLibraryFiltersMatch(filters: SuwayomiLibraryFilters, facts: SuwayomiLibraryFacts): Boolean {
    fun allows(state: SuwayomiTriState, holds: Boolean): Boolean = when (state) {
        SuwayomiTriState.IGNORE -> true
        SuwayomiTriState.INCLUDE -> holds
        SuwayomiTriState.EXCLUDE -> !holds
    }
    if (!allows(filters.downloaded, facts.downloadedCount > 0)) return false
    if (!allows(filters.unread, facts.unreadCount > 0)) return false
    // A series counts as started once anything has been read or some progress was recorded.
    if (!allows(filters.started, facts.hasProgress)) return false
    if (!allows(filters.bookmarked, facts.bookmarkedCount > 0)) return false
    if (!allows(filters.completed, facts.completed)) return false
    filters.minimumChapters?.let { if (facts.totalChapters < it) return false }
    if (filters.title.isNotBlank() && !facts.title.contains(filters.title.trim(), ignoreCase = true)) return false
    if (filters.author.isNotBlank() && !facts.author.orEmpty().contains(filters.author.trim(), true)) return false
    if (filters.artist.isNotBlank() && !facts.artist.orEmpty().contains(filters.artist.trim(), true)) return false
    if (filters.genres.isNotEmpty()) {
        val available = facts.genres.mapTo(hashSetOf()) { it.lowercase() }
        if (!available.containsAll(filters.genres)) return false
    }
    return true
}

/** Every genre present on the shelf, lower-cased and sorted, for the genre filter's options. */
fun suwayomiShelfGenres(genres: Iterable<String>): List<String> =
    genres.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.lowercase() }.distinct().sorted()

/**
 * Compact, forward-compatible storage for the shelf filters: `key=value` pairs separated by `;`,
 * with list values comma-separated. An unknown key is ignored so an older build still reads a newer
 * value instead of discarding the whole filter set.
 */
object SuwayomiLibraryFiltersCodec {
    /** `%`, `;` and `,` are structural, so free text and genre names escape them before joining. */
    private fun escape(value: String): String = value
        .replace("%", "%25")
        .replace(";", "%3b")
        .replace(",", "%2c")

    private fun unescape(value: String): String = value
        .replace("%3b", ";", ignoreCase = true)
        .replace("%2c", ",", ignoreCase = true)
        .replace("%25", "%", ignoreCase = true)

    fun encode(filters: SuwayomiLibraryFilters): String = buildList {
        fun add(key: String, value: String) {
            if (value.isNotEmpty()) add("$key=$value")
        }
        add("downloaded", filters.downloaded.name)
        add("unread", filters.unread.name)
        add("started", filters.started.name)
        add("bookmarked", filters.bookmarked.name)
        add("completed", filters.completed.name)
        add("minChapters", filters.minimumChapters?.toString().orEmpty())
        add("title", escape(filters.title))
        add("author", escape(filters.author))
        add("artist", escape(filters.artist))
        // The list separator stays structural; only the genre names themselves are escaped.
        add("genres", filters.genres.joinToString(",") { escape(it) })
    }.joinToString(";")

    fun decode(value: String): SuwayomiLibraryFilters {
        val pairs = value.split(';')
            .mapNotNull { entry ->
                val separator = entry.indexOf('=')
                if (separator <= 0) null else entry.take(separator) to entry.substring(separator + 1)
            }
            .toMap()
        fun triState(key: String): SuwayomiTriState = pairs[key]
            ?.let { stored -> SuwayomiTriState.entries.firstOrNull { it.name == stored } }
            ?: SuwayomiTriState.IGNORE
        return SuwayomiLibraryFilters(
            downloaded = triState("downloaded"),
            unread = triState("unread"),
            started = triState("started"),
            bookmarked = triState("bookmarked"),
            completed = triState("completed"),
            minimumChapters = pairs["minChapters"]?.toIntOrNull()?.takeIf { it > 0 },
            title = unescape(pairs["title"].orEmpty()),
            author = unescape(pairs["author"].orEmpty()),
            artist = unescape(pairs["artist"].orEmpty()),
            genres = pairs["genres"]?.split(',')
                ?.mapNotNull { unescape(it).trim().takeIf(String::isNotEmpty)?.lowercase() }
                ?.toSet()
                .orEmpty(),
        )
    }
}

/**
 * Whether the server will honour the source filters for this listing mode. `fetchSourceManga` only
 * forwards them to the source's search endpoint, so popular and latest pages ignore them entirely.
 */
fun suwayomiFiltersRequireSearch(mode: SuwayomiSourceMangaType): Boolean = mode != SuwayomiSourceMangaType.SEARCH

/** Count of source filters the reader moved away from the server-provided default. */
fun activeSuwayomiFilterCount(filters: List<SuwayomiFilterState>): Int = filters.sumOf { state ->
    when (state) {
        is GroupState -> activeSuwayomiFilterCount(state.children)
        else -> if (state.diverged) 1 else 0
    }
}

/**
 * Number of client-side narrowing settings in effect, so the filter chip can report them next to the
 * source filters instead of looking inactive while a narrowing is applied.
 */
fun activeSuwayomiBrowseFilterCount(filters: SuwayomiBrowseFilters, sort: SuwayomiBrowseSort): Int =
    (if (filters.active) 1 else 0) + (if (sort != SuwayomiBrowseSort.SOURCE) 1 else 0)
