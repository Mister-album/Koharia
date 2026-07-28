package koharia.komga.domain.repository

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import koharia.komga.api.KomgaApiClient
import koharia.komga.api.dto.AuthorDto
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.CollectionDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.PageDto
import koharia.komga.api.dto.ReadListDto
import koharia.komga.api.dto.SeriesDto
import koharia.komga.api.dto.formatChapterName
import koharia.komga.api.dto.toChapterMemo
import koharia.komga.api.dto.toSManga
import koharia.source.komga.AuthorGroup
import koharia.source.komga.CollectionSelect
import koharia.source.komga.InProgressFilter
import koharia.source.komga.KomgaCachePolicy
import koharia.source.komga.LibraryFilter
import koharia.source.komga.OneshotFilter
import koharia.source.komga.ReadFilter
import koharia.source.komga.ReadingStateGroup
import koharia.source.komga.SeriesSort
import koharia.source.komga.TYPE_ALL_INDEX
import koharia.source.komga.TYPE_BOOKS_INDEX
import koharia.source.komga.TYPE_READ_LISTS_INDEX
import koharia.source.komga.TypeSelect
import koharia.source.komga.UnreadFilter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KomgaRepository(
    private val baseUrl: String,
    private val apiClient: KomgaApiClient,
) {

    fun popularMangaRequest(
        page: Int,
        defaultLibraries: Set<String>,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ) = apiClient.popularRequest(page, defaultLibraries, cachePolicy)

    fun latestUpdatesRequest(
        page: Int,
        defaultLibraries: Set<String>,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ) = apiClient.latestRequest(page, defaultLibraries, cachePolicy)

    fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
        defaultLibraries: Set<String>,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): Request {
        val type = filters.searchType().takeUnless { it == KomgaApiClient.SearchType.ALL }
            ?: KomgaApiClient.SearchType.SERIES
        val supportsBookFilters = type != KomgaApiClient.SearchType.READ_LISTS
        val supportsSeriesFilters = type == KomgaApiClient.SearchType.SERIES
        return apiClient.searchRequest(
            page = page,
            query = query,
            type = type,
            defaultLibraries = defaultLibraries,
            selectedLibraries = filters.selectedLibraries(),
            collectionId = filters.collectionId(),
            sortIndex = filters.sortSelection().first,
            sortAscending = filters.sortSelection().second,
            readStatuses = filters.readStatuses().takeIf { supportsBookFilters }.orEmpty(),
            statuses = filters.multiSelectIds("Status").takeIf { supportsSeriesFilters }.orEmpty(),
            genres = filters.multiSelectIds("Genres").takeIf { supportsSeriesFilters }.orEmpty(),
            tags = filters.multiSelectIds("Tags").takeIf { supportsBookFilters }.orEmpty(),
            publishers = filters.multiSelectIds("Publishers").takeIf { supportsSeriesFilters }.orEmpty(),
            authors = filters.selectedAuthors().takeIf { supportsSeriesFilters }.orEmpty(),
            oneshot = filters.oneshot().takeIf { supportsSeriesFilters },
            cachePolicy = cachePolicy,
        )
    }

    suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
        defaultLibraries: Set<String>,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): MangasPage {
        val type = filters.searchType()
        if (type == KomgaApiClient.SearchType.READ_LISTS) {
            return apiClient.execute(
                searchMangaRequest(page, query, filters, defaultLibraries, cachePolicy),
            ).let(::parseMangasPage)
        }

        val normalizedQuery = normalizeSearchQuery(query)
        if (query.isNotBlank() && normalizedQuery.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        return when (type) {
            KomgaApiClient.SearchType.ALL -> coroutineScope {
                val books = async {
                    getSearchMangaPage(
                        page = page,
                        query = normalizedQuery,
                        type = KomgaApiClient.SearchType.BOOKS,
                        filters = filters,
                        defaultLibraries = defaultLibraries,
                        forceRelevanceSort = true,
                        cachePolicy = cachePolicy,
                    )
                }
                val series = async {
                    getSearchMangaPage(
                        page = page,
                        query = normalizedQuery,
                        type = KomgaApiClient.SearchType.SERIES,
                        filters = filters,
                        defaultLibraries = defaultLibraries,
                        forceRelevanceSort = true,
                        cachePolicy = cachePolicy,
                    )
                }
                mergeSearchPages(books.await(), series.await())
            }
            KomgaApiClient.SearchType.BOOKS,
            KomgaApiClient.SearchType.SERIES,
            -> getSearchMangaPage(
                page = page,
                query = normalizedQuery,
                type = type,
                filters = filters,
                defaultLibraries = defaultLibraries,
                forceRelevanceSort = false,
                cachePolicy = cachePolicy,
            )
            KomgaApiClient.SearchType.READ_LISTS -> error("Handled above")
        }
    }

    private suspend fun getSearchMangaPage(
        page: Int,
        query: String,
        type: KomgaApiClient.SearchType,
        filters: FilterList,
        defaultLibraries: Set<String>,
        forceRelevanceSort: Boolean,
        cachePolicy: KomgaCachePolicy,
    ): MangasPage {
        val isSeries = type == KomgaApiClient.SearchType.SERIES
        val selectedLibraries = filters.selectedLibraries()
        val sortSelection = if (forceRelevanceSort) 0 to true else filters.sortSelection()
        val readStatuses = filters.readStatuses()
        val statuses = filters.multiSelectIds("Status").takeIf { isSeries }.orEmpty()
        val genres = filters.multiSelectIds("Genres").takeIf { isSeries }.orEmpty()
        val tags = filters.multiSelectIds("Tags")
        val publishers = filters.multiSelectIds("Publishers").takeIf { isSeries }.orEmpty()
        val authors = filters.selectedAuthors()
        val oneshot = filters.oneshot()
        val collectionId = filters.collectionId().takeIf { isSeries }
        val modernRequest = apiClient.searchListRequest(
            page = page,
            query = query,
            type = type,
            defaultLibraries = defaultLibraries,
            selectedLibraries = selectedLibraries,
            collectionId = collectionId,
            sortIndex = sortSelection.first,
            sortAscending = sortSelection.second,
            readStatuses = readStatuses,
            statuses = statuses,
            genres = genres,
            tags = tags,
            publishers = publishers,
            authors = authors,
            oneshot = oneshot,
            cachePolicy = cachePolicy,
        )
        val legacyRequest = apiClient.searchRequest(
            page = page,
            query = query,
            type = type,
            defaultLibraries = defaultLibraries,
            selectedLibraries = selectedLibraries,
            collectionId = collectionId,
            sortIndex = sortSelection.first,
            sortAscending = sortSelection.second,
            readStatuses = readStatuses,
            statuses = statuses,
            genres = genres,
            tags = tags,
            publishers = publishers,
            authors = authors.takeIf { isSeries }.orEmpty(),
            oneshot = oneshot.takeIf { isSeries },
            cachePolicy = cachePolicy,
        )
        return apiClient.executeSearch(
            type = type,
            modernRequest = modernRequest,
            legacyRequest = legacyRequest,
            legacyCompatible = isSeries || (authors.isEmpty() && oneshot == null),
        ).let(::parseMangasPage)
    }

    fun parseMangasPage(response: okhttp3.Response): MangasPage {
        val data = response.use {
            when {
                apiClient.isReadList(it.request.url.toString()) -> apiClient.parsePageWrapper<ReadListDto>(it)
                apiClient.isBook(it.request.url.toString()) -> apiClient.parsePageWrapper<BookDto>(it)
                else -> apiClient.parsePageWrapper<SeriesDto>(it)
            }
        }

        val mangas = when {
            response.request.url.toString().contains(
                "/api/v1/readlists",
            ) -> data.content.filterIsInstance<ReadListDto>().map {
                it.toSManga(baseUrl)
            }
            response.request.url.toString().contains("/api/v1/books") -> data.content.filterIsInstance<BookDto>().map {
                it.toSManga(baseUrl)
            }
            else -> data.content.filterIsInstance<SeriesDto>().map { it.toSManga(baseUrl) }
        }
        return MangasPage(mangas, !data.last)
    }

    fun mangaDetailsRequest(manga: SManga, cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default) =
        apiClient.detailsRequest(manga.url, cachePolicy)

    fun mangaDetailsParse(response: okhttp3.Response): SManga =
        response.use {
            when {
                apiClient.isReadList(it.request.url.toString()) -> apiClient.parse<ReadListDto>(it).toSManga(baseUrl)
                apiClient.isBook(it.request.url.toString()) -> apiClient.parse<BookDto>(it).toSManga(baseUrl)
                else -> apiClient.parse<SeriesDto>(it).toSManga(baseUrl)
            }
        }

    fun chapterListRequest(manga: SManga, cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default) =
        apiClient.chapterListRequest(manga.url, apiClient.isBook(manga.url), cachePolicy)

    fun chapterListParse(response: okhttp3.Response, chapterNameTemplate: String): List<SChapter> =
        response.use {
            if (apiClient.isBook(it.request.url.toString())) {
                val book = apiClient.parse<BookDto>(it)
                return listOf(book.toChapter(baseUrl, chapterNameTemplate, true, 1F))
            }

            val books = apiClient.parsePageWrapper<BookDto>(it).content
            val isFromReadList = apiClient.isReadList(it.request.url.toString())
            books
                .mapIndexed { index, book ->
                    val number = if (isFromReadList) index + 1F else book.metadata.numberSort
                    book.toChapter(baseUrl, chapterNameTemplate, isFromReadList, number)
                }
                .sortedByDescending { chapter -> chapter.chapter_number }
        }

    fun pageListRequest(chapter: SChapter, cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default) =
        apiClient.pageListRequest(chapter.url, cachePolicy)

    fun pageListParse(response: okhttp3.Response): List<Page> =
        response.use {
            apiClient.parse<List<PageDto>>(it).map { page ->
                val url = "${response.request.url}/${page.number}" +
                    if (page.mediaType !in SUPPORTED_IMAGE_TYPES) "?convert=png" else ""
                Page(page.number, imageUrl = url)
            }
        }

    suspend fun fetchFilterOptions(forceRefresh: Boolean = false): KomgaFilterOptions = withIOContext {
        val cachePolicy = if (forceRefresh) KomgaCachePolicy.NetworkFirst else KomgaCachePolicy.Default
        val libraryOrders = apiClient.getLibraryOrders(cachePolicy)
        KomgaFilterOptions(
            libraries = apiClient.getLibraries(cachePolicy)
                .sortedBy { libraryOrders[it.id] ?: Int.MAX_VALUE },
            collections = apiClient.getCollections(cachePolicy),
            genres = apiClient.getGenres(cachePolicy),
            tags = apiClient.getTags(cachePolicy),
            publishers = apiClient.getPublishers(cachePolicy),
            authors = apiClient.getAuthors(cachePolicy),
        )
    }

    private fun BookDto.toChapter(
        baseUrl: String,
        chapterNameTemplate: String,
        isFromReadList: Boolean,
        chapterNumber: Float,
    ): SChapter {
        val formattedName = formatChapterName(chapterNameTemplate, isFromReadList)
        return SChapter.create().apply {
            this.chapter_number = chapterNumber
            url = "$baseUrl/api/v1/books/$id"
            name = formattedName
            scanlator = metadata.authors.filter { it.role == "translator" }.joinToString { it.name }
            memo = toChapterMemo(
                baseUrl,
                embeddedFileSize = trailingEmbeddedFileSize(
                    chapterNameTemplate = chapterNameTemplate,
                    formattedName = formattedName,
                    isFromReadList = isFromReadList,
                ),
            )
            date_upload = when {
                metadata.releaseDate != null -> parseDate(metadata.releaseDate)
                created != null -> parseDateTime(created)
                else -> parseDateTime(fileLastModified)
            }
        }
    }

    private fun BookDto.trailingEmbeddedFileSize(
        chapterNameTemplate: String,
        formattedName: String,
        isFromReadList: Boolean,
    ): String? {
        val markedTemplate = chapterNameTemplate
            .replace("{size}", FILE_SIZE_MARKER)
            .replace("{sizeBytes}", FILE_SIZE_MARKER)
        if (markedTemplate == chapterNameTemplate) return null

        val markedSuffix = TRAILING_PARENTHESIZED_VALUE.find(
            formatChapterName(markedTemplate, isFromReadList),
        )?.groupValues?.getOrNull(1)
        if (markedSuffix?.contains(FILE_SIZE_MARKER) != true) return null

        return TRAILING_PARENTHESIZED_VALUE.find(formattedName)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }

    data class KomgaFilterOptions(
        val libraries: List<LibraryDto>,
        val collections: List<CollectionDto>,
        val genres: Set<String>,
        val tags: Set<String>,
        val publishers: Set<String>,
        val authors: Map<String, List<AuthorDto>>,
    )

    companion object {
        private const val FILE_SIZE_MARKER = "__KOHARIA_FILE_SIZE__"
        private val TRAILING_PARENTHESIZED_VALUE = Regex("\\(\\s*([^()]*)\\s*\\)\\s*$")

        val formatterDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val formatterDateTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone =
                TimeZone.getTimeZone("UTC")
        }
        val SUPPORTED_IMAGE_TYPES =
            setOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/jxl", "image/heif", "image/avif")

        fun parseDate(date: String): Long = try {
            formatterDate.parse(date)?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }

        fun parseDateTime(date: String): Long = try {
            formatterDateTime.parse(date)?.time ?: 0L
        } catch (_: ParseException) {
            0L
        }
    }
}

internal fun normalizeSearchQuery(query: String): String {
    val trimmedQuery = query.trim()
    if (ADVANCED_FIELD_QUERY.containsMatchIn(trimmedQuery) || BOOLEAN_GROUP_QUERY.containsMatchIn(trimmedQuery)) {
        return trimmedQuery
    }
    return trimmedQuery
        .replace(SEARCH_DELIMITERS, " ")
        .replace(REPEATED_WHITESPACE, " ")
        .trim()
}

internal fun mergeSearchPages(books: MangasPage, series: MangasPage): MangasPage {
    val mangas = buildList(books.mangas.size + series.mangas.size) {
        val maxSize = maxOf(books.mangas.size, series.mangas.size)
        repeat(maxSize) { index ->
            books.mangas.getOrNull(index)?.let(::add)
            series.mangas.getOrNull(index)?.let(::add)
        }
    }
    return MangasPage(mangas, books.hasNextPage || series.hasNextPage)
}

private fun FilterList.searchType(): KomgaApiClient.SearchType = when {
    collectionId() != null -> KomgaApiClient.SearchType.SERIES
    filterIsInstance<TypeSelect>().firstOrNull()?.state == TYPE_READ_LISTS_INDEX ->
        KomgaApiClient.SearchType.READ_LISTS
    filterIsInstance<TypeSelect>().firstOrNull()?.state == TYPE_BOOKS_INDEX -> KomgaApiClient.SearchType.BOOKS
    filterIsInstance<TypeSelect>().firstOrNull()?.state == TYPE_ALL_INDEX -> KomgaApiClient.SearchType.ALL
    else -> KomgaApiClient.SearchType.SERIES
}

private fun FilterList.collectionId(): String? {
    if (filterIsInstance<TypeSelect>().firstOrNull()?.state != 0) return null
    return filterIsInstance<CollectionSelect>().firstOrNull()?.collections?.getOrNull(
        filterIsInstance<CollectionSelect>().firstOrNull()?.state ?: 0,
    )?.id
}

private fun FilterList.sortSelection(): Pair<Int, Boolean> {
    val sort = filterIsInstance<SeriesSort>().firstOrNull()?.state ?: return 0 to true
    return sort.index to sort.ascending
}

private fun FilterList.readStatuses(): Set<String> {
    val statuses = mutableSetOf<String>()
    val readingFilters = filterIsInstance<ReadingStateGroup>().firstOrNull()?.state.orEmpty()
    if (readingFilters.filterIsInstance<UnreadFilter>().firstOrNull()?.state == true) {
        statuses += setOf("UNREAD", "IN_PROGRESS")
    }
    if (readingFilters.filterIsInstance<InProgressFilter>().firstOrNull()?.state == true) {
        statuses += "IN_PROGRESS"
    }
    if (readingFilters.filterIsInstance<ReadFilter>().firstOrNull()?.state == true) {
        statuses += "READ"
    }
    return statuses
}

private fun FilterList.oneshot(): Boolean? =
    filterIsInstance<ReadingStateGroup>()
        .firstOrNull()
        ?.state
        ?.filterIsInstance<OneshotFilter>()
        ?.firstOrNull()
        ?.state
        ?.takeIf { it }

private fun FilterList.selectedLibraries(): Set<String> =
    filterIsInstance<LibraryFilter>().firstOrNull()?.state?.filter { it.state }?.map { it.id }?.toSet().orEmpty()

private fun FilterList.selectedAuthors(): List<Pair<String, String>> =
    filterIsInstance<AuthorGroup>().flatMap { group ->
        group.state.filter { it.state }.map { it.author.name to it.author.role }
    }

private fun FilterList.multiSelectIds(name: String): Set<String> {
    val filter = firstOrNull { it.name == name } as? koharia.source.komga.UriMultiSelectFilter ?: return emptySet()
    return filter.state.filter { it.state }.map { it.id }.toSet()
}

private val SEARCH_DELIMITERS = Regex("""[()\[\]（）【】]""")
private val REPEATED_WHITESPACE = Regex("\\s+")
private val ADVANCED_FIELD_QUERY = Regex(
    "(?:^|\\s)(?:title|isbn|tag|series_tag|book_tag|author|writer|penciller|inker|colorist|letterer|" +
        "cover|editor|translator|publisher|status|reading_direction|age_rating|language|genre|" +
        "sharing_label|total_book_count|book_count|release_date|deleted|oneshot|complete):",
    RegexOption.IGNORE_CASE,
)
private val BOOLEAN_GROUP_QUERY = Regex("\\([^)]*\\b(?:AND|OR|NOT)\\b[^)]*\\)")
