package koharia.komga.api

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import koharia.komga.api.dto.AuthorDto
import koharia.komga.api.dto.ClientSettingDto
import koharia.komga.api.dto.CollectionDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.PageWrapperDto
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class KomgaApiClient(
    private val baseUrl: String,
    private val headers: Headers,
    private val client: OkHttpClient,
    @PublishedApi internal val json: Json,
) {

    fun popularRequest(page: Int, defaultLibraries: Set<String>): Request {
        return searchRequest(
            page = page,
            query = "",
            type = SearchType.SERIES,
            defaultLibraries = defaultLibraries,
            sortIndex = 1,
            sortAscending = true,
        )
    }

    fun latestRequest(page: Int, defaultLibraries: Set<String>): Request {
        return searchRequest(
            page = page,
            query = "",
            type = SearchType.SERIES,
            defaultLibraries = defaultLibraries,
            sortIndex = 3,
            sortAscending = false,
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

        val sortCriteria = when (sortIndex) {
            0 -> "relevance"
            1 -> if (typePath == "series") "metadata.titleSort" else "name"
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
    }

    fun detailsRequest(url: String): Request = GET(url, headers)

    fun chapterListRequest(url: String, isBook: Boolean): Request {
        return if (isBook) {
            GET("$url?unpaged=true&media_status=READY&deleted=false", headers)
        } else {
            GET("$url/books?unpaged=true&media_status=READY&deleted=false", headers)
        }
    }

    fun pageListRequest(url: String): Request = GET("$url/pages", headers)

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

    suspend fun getLibraries(): List<LibraryDto> =
        client.newCall(GET("$baseUrl/api/v1/libraries", headers)).executeAndParse()

    suspend fun getLibraryOrders(): Map<String, Int> {
        val settings = client.newCall(GET("$baseUrl/api/v1/client-settings/user/list", headers))
            .executeAndParse<Map<String, ClientSettingDto>>()
        val librariesValue = settings["webui.libraries"]?.value?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val librariesOrder = json.parseToJsonElement(librariesValue).jsonObject

        return librariesOrder.mapNotNull { (libraryId, value) ->
            value.jsonObject["order"]?.jsonPrimitive?.content?.toIntOrNull()?.let { order ->
                libraryId to order
            }
        }.toMap()
    }

    suspend fun getCollections(): List<CollectionDto> =
        client.newCall(GET("$baseUrl/api/v1/collections?unpaged=true", headers)).executeAndParse<PageWrapperDto<CollectionDto>>().content

    suspend fun getGenres(): Set<String> =
        client.newCall(GET("$baseUrl/api/v1/genres", headers)).executeAndParse()

    suspend fun getTags(): Set<String> =
        client.newCall(GET("$baseUrl/api/v1/tags", headers)).executeAndParse()

    suspend fun getPublishers(): Set<String> =
        client.newCall(GET("$baseUrl/api/v1/publishers", headers)).executeAndParse()

    suspend fun getAuthors(): Map<String, List<AuthorDto>> =
        client.newCall(GET("$baseUrl/api/v1/authors", headers)).executeAndParse<List<AuthorDto>>().groupBy { it.role }

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
    }
}
