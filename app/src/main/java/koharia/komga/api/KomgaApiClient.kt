package koharia.komga.api

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import koharia.komga.api.dto.AuthorDto
import koharia.komga.api.dto.ClientSettingDto
import koharia.komga.api.dto.ClientSettingUpdateDto
import koharia.komga.api.dto.CollectionDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.PageWrapperDto
import koharia.source.komga.KomgaCachePolicy
import koharia.source.komga.komgaCachePolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class KomgaApiClient(
    private val baseUrl: String,
    private val headers: Headers,
    private val client: OkHttpClient,
    @PublishedApi internal val json: Json,
    private val searchCapabilities: KomgaSearchCapabilities = KomgaSearchCapabilities(),
) {

    init {
        searchCapabilities.prepareFor(baseUrl)
    }

    fun popularRequest(
        page: Int,
        defaultLibraries: Set<String>,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): Request {
        return searchRequest(
            page = page,
            query = "",
            type = SearchType.SERIES,
            defaultLibraries = defaultLibraries,
            sortIndex = 1,
            sortAscending = true,
            cachePolicy = cachePolicy,
        )
    }

    fun latestRequest(
        page: Int,
        defaultLibraries: Set<String>,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): Request {
        return searchRequest(
            page = page,
            query = "",
            type = SearchType.SERIES,
            defaultLibraries = defaultLibraries,
            sortIndex = 3,
            sortAscending = false,
            cachePolicy = cachePolicy,
        )
    }

    fun searchRequest(
        page: Int,
        query: String,
        type: SearchType,
        defaultLibraries: Set<String>,
        selectedLibraries: Set<String> = emptySet(),
        collectionId: String? = null,
        sortIndex: Int = 0,
        sortAscending: Boolean = true,
        readStatuses: Set<String> = emptySet(),
        statuses: Set<String> = emptySet(),
        genres: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        publishers: Set<String> = emptySet(),
        authors: List<Pair<String, String>> = emptyList(),
        oneshot: Boolean? = null,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): Request {
        val typePath = when {
            collectionId != null -> "collections/$collectionId/series"
            type == SearchType.READ_LISTS -> "readlists"
            type == SearchType.BOOKS -> "books"
            else -> "series"
        }

        val url = "$baseUrl/api/v1".toHttpUrl().newBuilder()
            .addPathSegments(typePath)
            .addQueryParameter("search", query)
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("deleted", "false")

        val libraries = if (selectedLibraries.isEmpty()) defaultLibraries else selectedLibraries
        if (libraries.isNotEmpty()) {
            url.addQueryParameter("library_id", libraries.joinToString(","))
        }

        readStatuses.forEach { url.addQueryParameter("read_status", it) }
        if (statuses.isNotEmpty()) url.addQueryParameter("status", statuses.joinToString(","))
        if (genres.isNotEmpty()) url.addQueryParameter("genre", genres.joinToString(","))
        if (tags.isNotEmpty()) url.addQueryParameter("tag", tags.joinToString(","))
        if (publishers.isNotEmpty()) url.addQueryParameter("publisher", publishers.joinToString(","))
        authors.forEach { (name, role) ->
            url.addQueryParameter("author", "$name,$role")
        }
        oneshot?.let { url.addQueryParameter("oneshot", it.toString()) }

        val sortCriteria = when (sortIndex) {
            0 -> "relevance".takeIf { query.isNotBlank() }
            1 -> type.alphabeticalSortField()
            2 -> "createdDate"
            3 -> "lastModifiedDate"
            4 -> "random"
            else -> null
        }?.let {
            "$it,${if (sortAscending) "asc" else "desc"}"
        }
        if (sortCriteria != null) {
            url.addQueryParameter("sort", sortCriteria)
        }

        return GET(url.build(), headers)
            .newBuilder()
            .komgaCachePolicy(cachePolicy)
            .build()
    }

    fun searchListRequest(
        page: Int,
        query: String,
        type: SearchType,
        defaultLibraries: Set<String>,
        selectedLibraries: Set<String> = emptySet(),
        collectionId: String? = null,
        sortIndex: Int = 0,
        sortAscending: Boolean = true,
        readStatuses: Set<String> = emptySet(),
        statuses: Set<String> = emptySet(),
        genres: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        publishers: Set<String> = emptySet(),
        authors: List<Pair<String, String>> = emptyList(),
        oneshot: Boolean? = null,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): Request {
        require(type == SearchType.SERIES || type == SearchType.BOOKS)
        val libraries = if (selectedLibraries.isEmpty()) defaultLibraries else selectedLibraries
        val request = KomgaSearchRequest(
            condition = buildSearchCondition(
                type = type,
                libraries = libraries,
                collectionId = collectionId,
                readStatuses = readStatuses,
                statuses = statuses,
                genres = genres,
                tags = tags,
                publishers = publishers,
                authors = authors,
                oneshot = oneshot,
            ),
            fullTextSearch = query.takeIf { it.isNotBlank() },
        )
        val url = "$baseUrl/api/v1/${type.pathSegment}/list".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page - 1).toString())
        val sortCriteria = when (sortIndex) {
            0 -> "relevance".takeIf { query.isNotBlank() }
            1 -> type.alphabeticalSortField()
            2 -> "createdDate"
            3 -> "lastModifiedDate"
            4 -> "random"
            else -> null
        }?.let { "$it,${if (sortAscending) "asc" else "desc"}" }
        sortCriteria?.let { url.addQueryParameter("sort", it) }

        return POST(
            url = url.build().toString(),
            headers = headers,
            body = json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE),
        )
            .newBuilder()
            .komgaCachePolicy(cachePolicy)
            .build()
    }

    suspend fun executeSearch(
        type: SearchType,
        modernRequest: Request,
        legacyRequest: Request,
        legacyCompatible: Boolean,
    ): Response {
        if (searchCapabilities.usesLegacy(type)) {
            check(legacyCompatible) { "This search requires Komga 1.19 or newer" }
            return execute(legacyRequest)
        }

        return try {
            execute(modernRequest)
        } catch (error: HttpException) {
            if (error.code != 404 && error.code != 405) throw error
            searchCapabilities.markLegacy(type)
            check(legacyCompatible) { "This search requires Komga 1.19 or newer" }
            execute(legacyRequest)
        }
    }

    suspend fun execute(request: Request): Response = client.newCall(request).awaitSuccess()

    fun bookReadStatusRequest(bookUrl: String, read: Boolean): Request {
        val builder = Request.Builder()
            .url("$bookUrl/read-progress")
            .headers(headers)

        return if (read) {
            val payload = json.encodeToString(BookReadStatusUpdateDto(completed = true))
            builder.patch(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
        } else {
            builder.delete().build()
        }
    }

    suspend fun setBookReadStatus(bookUrl: String, read: Boolean) {
        client.newCall(bookReadStatusRequest(bookUrl, read)).awaitSuccess().close()
    }

    fun detailsRequest(url: String, cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Request =
        GET(url, headers)
            .newBuilder()
            .komgaCachePolicy(cachePolicy)
            .build()

    fun chapterListRequest(
        url: String,
        isBook: Boolean,
        cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default,
    ): Request {
        val request = if (isBook) {
            GET("$url?unpaged=true&media_status=READY&deleted=false", headers)
        } else {
            GET("$url/books?unpaged=true&media_status=READY&deleted=false", headers)
        }
        return request.newBuilder()
            .komgaCachePolicy(cachePolicy)
            .build()
    }

    fun pageListRequest(url: String, cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Request =
        GET("$url/pages", headers)
            .newBuilder()
            .komgaCachePolicy(cachePolicy)
            .build()

    fun meRequest(): Request = GET("$baseUrl/api/v1/users/me", headers)

    fun bookFileRequest(url: String, rangeStart: Long? = null): Request {
        val request = GET("$url/file", headers)
        return if (rangeStart != null && rangeStart > 0L) {
            request.newBuilder()
                .header("Range", "bytes=$rangeStart-")
                .build()
        } else {
            request
        }
    }

    suspend fun getLibraries(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): List<LibraryDto> =
        client.newCall(
            GET("$baseUrl/api/v1/libraries", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        ).executeAndParse()

    suspend fun updateClientSettings(updates: Map<String, ClientSettingUpdateDto>) {
        val payload = json.encodeToString(updates)
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/v1/client-settings/user")
                .headers(headers)
                .patch(payload.toRequestBody("application/json".toMediaType()))
                .build(),
        ).awaitSuccess()
    }

    suspend fun getLibraryOrders(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Map<String, Int> {
        val settings = client.newCall(
            GET("$baseUrl/api/v1/client-settings/user/list", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        )
            .executeAndParse<Map<String, ClientSettingDto>>()
        val librariesValue = settings["webui.libraries"]?.value?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val librariesOrder = json.parseToJsonElement(librariesValue).jsonObject

        return librariesOrder.mapNotNull { (libraryId, value) ->
            value.jsonObject["order"]?.jsonPrimitive?.content?.toIntOrNull()?.let { order ->
                libraryId to order
            }
        }.toMap()
    }

    suspend fun getCollections(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): List<CollectionDto> =
        client.newCall(
            GET("$baseUrl/api/v1/collections?unpaged=true", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        ).executeAndParse<PageWrapperDto<CollectionDto>>().content

    suspend fun getGenres(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Set<String> =
        client.newCall(
            GET("$baseUrl/api/v1/genres", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        ).executeAndParse()

    suspend fun getTags(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Set<String> =
        client.newCall(
            GET("$baseUrl/api/v1/tags", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        ).executeAndParse()

    suspend fun getPublishers(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Set<String> =
        client.newCall(
            GET("$baseUrl/api/v1/publishers", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        ).executeAndParse()

    suspend fun getAuthors(cachePolicy: KomgaCachePolicy = KomgaCachePolicy.Default): Map<String, List<AuthorDto>> =
        client.newCall(
            GET("$baseUrl/api/v1/authors", headers)
                .newBuilder()
                .komgaCachePolicy(cachePolicy)
                .build(),
        ).executeAndParse<List<AuthorDto>>().groupBy { it.role }

    fun isReadList(url: String): Boolean = url.contains("/api/v1/readlists")

    fun isBook(url: String): Boolean = url.contains("/api/v1/books")

    inline fun <reified T> parse(response: Response): T {
        val element = json.parseToJsonElement(response.body.string())
        return json.decodeFromJsonElement(serializer<T>(), element)
    }

    inline fun <reified T> parsePageContent(response: Response): List<T> {
        val element = json.parseToJsonElement(response.body.string())
        val content = element.jsonObject["content"]?.jsonArray ?: return emptyList()
        return content.map { json.decodeFromJsonElement(serializer<T>(), it) }
    }

    inline fun <reified T> parsePageWrapper(response: Response): PageWrapperDto<T> {
        val element = json.parseToJsonElement(response.body.string())
        val obj = element.jsonObject
        val content = obj["content"]?.jsonArray?.map { json.decodeFromJsonElement(serializer<T>(), it) }.orEmpty()
        return PageWrapperDto(
            content = content,
            empty = obj["empty"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: content.isEmpty(),
            first = obj["first"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            last = obj["last"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            number = obj["number"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            numberOfElements = obj["numberOfElements"]?.jsonPrimitive?.content?.toLongOrNull() ?: content.size.toLong(),
            size = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: content.size.toLong(),
            totalElements = obj["totalElements"]?.jsonPrimitive?.content?.toLongOrNull() ?: content.size.toLong(),
            totalPages = obj["totalPages"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1,
        )
    }

    private inline fun <reified T> okhttp3.Call.executeAndParse(): T {
        val response = execute()
        if (!response.isSuccessful) {
            response.close()
            error("HTTP ${response.code}")
        }
        response.use { return parse(it) }
    }

    enum class SearchType {
        SERIES,
        READ_LISTS,
        BOOKS,
        ALL,
        ;

        val pathSegment: String
            get() = when (this) {
                SERIES -> "series"
                READ_LISTS -> "readlists"
                BOOKS -> "books"
                ALL -> error("All searches use separate series and book requests")
            }

        fun alphabeticalSortField(): String = when (this) {
            SERIES -> "metadata.titleSort"
            BOOKS -> "metadata.title"
            READ_LISTS -> "name"
            ALL -> error("All searches only support relevance sorting")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

@Serializable
private data class BookReadStatusUpdateDto(
    val completed: Boolean,
)
