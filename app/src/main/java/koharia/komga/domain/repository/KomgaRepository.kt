package koharia.komga.domain.repository

import eu.kanade.tachiyomi.network.GET
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
import koharia.source.komga.SeriesSort
import koharia.source.komga.TypeSelect
import koharia.source.komga.UnreadFilter
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
    ) =
        apiClient.searchRequest(
            page = page,
            query = query,
            type = filters.searchType(),
            defaultLibraries = defaultLibraries,
            selectedLibraries = filters.selectedLibraries(),
            collectionId = filters.collectionId(),
            sortIndex = filters.sortSelection().first,
            sortAscending = filters.sortSelection().second,
            readStatuses = filters.readStatuses(),
            statuses = filters.multiSelectIds("Status"),
            genres = filters.multiSelectIds("Genres"),
            tags = filters.multiSelectIds("Tags"),
            publishers = filters.multiSelectIds("Publishers"),
            authors = filters.selectedAuthors(),
            oneshot = filters.oneshot(),
            cachePolicy = cachePolicy,
        )

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
                .filter { book -> book.media.mediaProfile != "EPUB" || book.media.epubDivinaCompatible }
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
        return SChapter.create().apply {
            this.chapter_number = chapterNumber
            url = "$baseUrl/api/v1/books/$id"
            name = formatChapterName(chapterNameTemplate, isFromReadList)
            scanlator = metadata.authors.filter { it.role == "translator" }.joinToString { it.name }
            memo = toChapterMemo(baseUrl)
            date_upload = when {
                metadata.releaseDate != null -> parseDate(metadata.releaseDate)
                created != null -> parseDateTime(created)
                else -> parseDateTime(fileLastModified)
            }
        }
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

private fun FilterList.searchType(): KomgaApiClient.SearchType = when {
    collectionId() != null -> KomgaApiClient.SearchType.SERIES
    filterIsInstance<TypeSelect>().firstOrNull()?.state == 1 -> KomgaApiClient.SearchType.READ_LISTS
    filterIsInstance<TypeSelect>().firstOrNull()?.state == 2 -> KomgaApiClient.SearchType.BOOKS
    else -> KomgaApiClient.SearchType.SERIES
}

private fun FilterList.collectionId(): String? =
    filterIsInstance<CollectionSelect>().firstOrNull()?.collections?.getOrNull(
        filterIsInstance<CollectionSelect>().firstOrNull()?.state ?: 0,
    )?.id

private fun FilterList.sortSelection(): Pair<Int, Boolean> {
    val sort = filterIsInstance<SeriesSort>().firstOrNull()?.state ?: return 0 to true
    return sort.index to sort.ascending
}

private fun FilterList.readStatuses(): Set<String> {
    val statuses = mutableSetOf<String>()
    if (filterIsInstance<UnreadFilter>().firstOrNull()?.state == true) {
        statuses += setOf("UNREAD", "IN_PROGRESS")
    }
    if (filterIsInstance<InProgressFilter>().firstOrNull()?.state == true) {
        statuses += "IN_PROGRESS"
    }
    if (filterIsInstance<ReadFilter>().firstOrNull()?.state == true) {
        statuses += "READ"
    }
    return statuses
}

private fun FilterList.oneshot(): Boolean? =
    filterIsInstance<OneshotFilter>().firstOrNull()?.state?.takeIf { it }

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
