package koharia.komga.api

import koharia.komga.api.dto.AuthorDto
import koharia.komga.api.dto.BookDto
import koharia.komga.api.dto.LibraryDto
import koharia.komga.api.dto.PageWrapperDto
import koharia.komga.api.dto.SeriesDto
import koharia.komga.api.dto.UserDto
import koharia.source.komga.KomgaCacheNamespace
import koharia.source.komga.KomgaCachePolicy
import koharia.source.komga.komgaCachePolicy
import koharia.source.komga.komgaRequireNetwork
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream

@Serializable
enum class KomgaOrganizationKind(val path: String) {
    COLLECTION("collections"),
    READ_LIST("readlists"),
}

@Serializable
data class KomgaOrganization(
    val id: String,
    val name: String,
    val ordered: Boolean = true,
    val summary: String = "",
    val seriesIds: List<String> = emptyList(),
    val bookIds: List<String> = emptyList(),
    val filtered: Boolean = false,
    val lastModifiedDate: String = "",
) {
    fun members(kind: KomgaOrganizationKind) =
        if (kind == KomgaOrganizationKind.COLLECTION) seriesIds else bookIds
}

@Serializable
data class KomgaOrganizationThumbnail(val id: String, val type: String, val selected: Boolean)

@Serializable
data class KomgaReadListMatch(
    val readListMatch: KomgaReadListMatchName,
    val requests: List<KomgaReadListMatchRequest> = emptyList(),
    val errorCode: String? = null,
)

@Serializable data class KomgaReadListMatchName(val name: String, val errorCode: String? = null)

@Serializable
data class KomgaReadListMatchRequest(
    val request: KomgaReadListRequestBook,
    val matches: List<KomgaReadListMatchSeries> = emptyList(),
)

@Serializable data class KomgaReadListRequestBook(val series: List<String>, val number: String)

@Serializable
data class KomgaReadListMatchSeries(
    val series: KomgaReadListMatchSeriesInfo,
    val books: List<KomgaReadListMatchBook>,
)

@Serializable data class KomgaReadListMatchSeriesInfo(val seriesId: String, val title: String)

@Serializable
data class KomgaReadListMatchBook(val bookId: String, val number: String, val title: String)

data class KomgaOrganizationQuery(
    val search: String = "",
    val page: Int = 0,
    val size: Int = 40,
    val sort: String = "name,asc",
    val filters: Map<String, List<String>> = emptyMap(),
    val unpaged: Boolean = false,
)

/**
 * Protocol for the server's series collections and book lists; these are never local categories.
 */
class KomgaOrganizationApi(
    private val baseUrl: String,
    private val headers: Headers,
    private val api: KomgaApiClient,
    private val json: Json,
    private val namespace: String = "",
    private val revision: () -> Long = { 0L },
) {
    fun request(
        path: String,
        query: KomgaOrganizationQuery? = null,
        refresh: Boolean = false,
        strict: Boolean = false,
        apiVersion: Int = 1,
    ): Request {
        val url = "$baseUrl/api/v$apiVersion/".toHttpUrl().newBuilder().addPathSegments(path)
        query?.let {
            url.addQueryParameter("page", it.page.toString())
                .addQueryParameter("size", it.size.toString())
            if (it.search.isNotBlank()) url.addQueryParameter("search", it.search)
            if (it.sort.isNotBlank()) url.addQueryParameter("sort", it.sort)
            if (it.unpaged) url.addQueryParameter("unpaged", "true")
            it.filters.forEach { (key, values) ->
                values.forEach { value -> url.addQueryParameter(key, value) }
            }
        }
        return Request.Builder()
            .url(url.build())
            .headers(headers)
            .tag(KomgaCacheNamespace::class.java, KomgaCacheNamespace(namespace, revision()))
            .komgaCachePolicy(
                if (refresh) KomgaCachePolicy.NetworkFirst else KomgaCachePolicy.Default,
            )
            .cacheControl(CacheControl.FORCE_NETWORK)
            .apply { if (strict) komgaRequireNetwork() }
            .build()
    }

    suspend fun list(
        kind: KomgaOrganizationKind,
        query: KomgaOrganizationQuery,
        refresh: Boolean = false,
    ) = read<PageWrapperDto<KomgaOrganization>>(request(kind.path, query, refresh))

    suspend fun detail(
        kind: KomgaOrganizationKind,
        id: String,
        refresh: Boolean = false,
        strict: Boolean = false,
    ) = read<KomgaOrganization>(request("${kind.path}/$id", refresh = refresh, strict = strict))

    suspend fun series(
        id: String,
        query: KomgaOrganizationQuery,
        refresh: Boolean = false,
    ): PageWrapperDto<SeriesDto> {
        val result =
            read<PageWrapperDto<SeriesDto>>(
                request("collections/$id/series", memberQuery(query), refresh),
            )
        return if (query.search.isBlank()) {
            result
        } else {
            searchPage(result.content, query) { "${it.metadata.title} ${it.name}" }
        }
    }

    suspend fun books(
        id: String,
        query: KomgaOrganizationQuery,
        refresh: Boolean = false,
    ): PageWrapperDto<BookDto> {
        val result =
            read<PageWrapperDto<BookDto>>(
                request("readlists/$id/books", memberQuery(query), refresh),
            )
        return if (query.search.isBlank()) {
            result
        } else {
            searchPage(result.content, query) {
                "${it.seriesTitle} ${it.metadata.title} ${it.name}"
            }
        }
    }

    private fun memberQuery(query: KomgaOrganizationQuery) =
        query.copy(
            search = "",
            sort = "",
            page = if (query.search.isBlank()) query.page else 0,
            unpaged = query.unpaged || query.search.isNotBlank(),
        )

    internal fun <T> searchPage(
        items: List<T>,
        query: KomgaOrganizationQuery,
        title: (T) -> String,
    ): PageWrapperDto<T> {
        val filtered = items.filter { title(it).contains(query.search.trim(), ignoreCase = true) }
        val size = query.size.coerceAtLeast(1)
        val pages = (filtered.size + size - 1) / size
        val content =
            if (query.unpaged) {
                filtered
            } else {
                filtered.drop(query.page.coerceAtLeast(0) * size).take(size)
            }
        return PageWrapperDto(
            content = content,
            empty = content.isEmpty(),
            first = query.page == 0,
            last = query.unpaged || query.page + 1 >= pages,
            number = query.page.toLong(),
            numberOfElements = content.size.toLong(),
            size = size.toLong(),
            totalElements = filtered.size.toLong(),
            totalPages = if (query.unpaged) 1 else pages.toLong(),
        )
    }

    suspend fun seriesBooks(id: String, refresh: Boolean = false) =
        read<PageWrapperDto<BookDto>>(
            request(
                "series/$id/books",
                KomgaOrganizationQuery(
                    sort = "metadata.numberSort,asc",
                    unpaged = true,
                    filters =
                    mapOf("media_status" to listOf("READY"), "deleted" to listOf("false")),
                ),
                refresh,
            ),
        )
            .content

    suspend fun book(id: String) = read<BookDto>(request("books/$id"))

    suspend fun series(id: String) = read<SeriesDto>(request("series/$id"))

    suspend fun account() = read<UserDto>(request("users/me", strict = true))

    suspend fun libraries() = read<List<LibraryDto>>(request("libraries"))

    suspend fun searchSeries(query: KomgaOrganizationQuery) =
        read<PageWrapperDto<SeriesDto>>(
            request("series", query.copy(sort = "metadata.titleSort,asc")),
        )

    suspend fun searchBooks(query: KomgaOrganizationQuery) =
        read<PageWrapperDto<BookDto>>(request("books", query.copy(sort = "metadata.title,asc")))

    suspend fun sibling(id: String, bookId: String, forward: Boolean) =
        read<BookDto>(
            request(
                "readlists/$id/books/$bookId/${if (forward) "next" else "previous"}",
                strict = true,
            ),
        )

    fun payload(
        kind: KomgaOrganizationKind,
        name: String? = null,
        summary: String? = null,
        ordered: Boolean? = null,
        members: List<String>? = null,
    ): JsonObject = buildJsonObject {
        name?.let {
            require(it.isNotBlank())
            put("name", it.trim())
        }
        if (kind == KomgaOrganizationKind.READ_LIST) summary?.let { put("summary", it) }
        ordered?.let { put("ordered", it) }
        members?.let {
            require(it.isNotEmpty() && it.none(String::isBlank))
            putJsonArray(if (kind == KomgaOrganizationKind.COLLECTION) "seriesIds" else "bookIds") {
                it.distinct().forEach { id -> add(kotlinx.serialization.json.JsonPrimitive(id)) }
            }
        }
    }

    fun mutationRequest(path: String, method: String, payload: JsonObject? = null): Request =
        request(path, strict = true)
            .newBuilder()
            .method(method, payload?.let { json.encodeToString(it).toRequestBody(JSON_MEDIA_TYPE) })
            .build()

    suspend fun create(kind: KomgaOrganizationKind, payload: JsonObject) =
        read<KomgaOrganization>(mutationRequest(kind.path, "POST", payload))

    suspend fun update(kind: KomgaOrganizationKind, id: String, payload: JsonObject) {
        api.execute(mutationRequest("${kind.path}/$id", "PATCH", payload)).close()
    }

    suspend fun delete(kind: KomgaOrganizationKind, id: String) {
        api.execute(mutationRequest("${kind.path}/$id", "DELETE")).close()
    }

    suspend fun thumbnails(kind: KomgaOrganizationKind, id: String) =
        read<List<KomgaOrganizationThumbnail>>(
            request("${kind.path}/$id/thumbnails", strict = true),
        )

    suspend fun uploadThumbnail(
        kind: KomgaOrganizationKind,
        id: String,
        name: String,
        bytes: ByteArray,
        type: String,
    ) {
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody(type.toMediaType()))
                .addFormDataPart("selected", "true")
                .build()
        api.execute(
            request("${kind.path}/$id/thumbnails", strict = true)
                .newBuilder()
                .post(body)
                .build(),
        )
            .close()
    }

    suspend fun selectThumbnail(kind: KomgaOrganizationKind, id: String, thumbnailId: String) {
        api.execute(
            request("${kind.path}/$id/thumbnails/$thumbnailId/selected", strict = true)
                .newBuilder()
                .put(ByteArray(0).toRequestBody())
                .build(),
        )
            .close()
    }

    suspend fun deleteThumbnail(kind: KomgaOrganizationKind, id: String, thumbnailId: String) {
        api.execute(mutationRequest("${kind.path}/$id/thumbnails/$thumbnailId", "DELETE")).close()
    }

    suspend fun matchComicRack(name: String, bytes: ByteArray): KomgaReadListMatch {
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody("application/xml".toMediaType()))
                .build()
        return read(
            request("readlists/match/comicrack", strict = true).newBuilder().post(body).build(),
        )
    }

    suspend fun export(id: String, output: OutputStream) {
        api.execute(request("readlists/$id/file", strict = true)).use { response ->
            response.body.byteStream().use { it.copyTo(output) }
        }
    }

    suspend fun choices(path: String, filters: Map<String, List<String>>): List<String> =
        read<List<String>>(
            request(path, KomgaOrganizationQuery(filters = filters, unpaged = true, sort = "")),
        )

    suspend fun authorNames(role: String, filters: Map<String, List<String>>): List<String> {
        val names = linkedSetOf<String>()
        var page = 0
        while (true) {
            val result =
                read<PageWrapperDto<AuthorDto>>(
                    request(
                        "authors",
                        KomgaOrganizationQuery(
                            page = page,
                            size = 100,
                            filters = filters + ("role" to listOf(role)),
                            sort = "",
                        ),
                        apiVersion = 2,
                    ),
                )
            names += result.content.map { it.name }
            page++
            if (page >= result.totalPages) break
        }
        return names.toList()
    }

    private suspend inline fun <reified T> read(request: Request): T =
        api.execute(request).use { api.parse<T>(it) }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
