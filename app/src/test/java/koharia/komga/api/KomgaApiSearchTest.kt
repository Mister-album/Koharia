package koharia.komga.api

import eu.kanade.tachiyomi.network.HttpException
import koharia.source.komga.KomgaCachePolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KomgaApiSearchTest {

    @Test
    fun `repository includes tag union in modern requests and legacy fallback for both resource types`() = runTest {
        for (legacy in listOf(false, true)) {
            val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                response(request, if (legacy && request.method == "POST") 404 else 200).newBuilder()
                    .body("""{"content":[],"last":true}""".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val repository = koharia.komga.domain.repository.KomgaRepository("https://komga.test", apiClient(client))
            val filters = eu.kanade.tachiyomi.source.model.FilterList(
                koharia.source.komga.TypeSelect().apply { state = koharia.source.komga.TYPE_ALL_INDEX },
            )
            assertTrue(repository.getSearchManga(1, "排球", filters, emptySet()).mangas.isEmpty())
            assertEquals(if (legacy) 4 else 2, requests.size)
            requests.forEach { request ->
                val query = if (request.method == "POST") {
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    Json.parseToJsonElement(body).jsonObject.getValue("fullTextSearch").jsonPrimitive.content
                } else {
                    request.url.queryParameter("search")
                }
                assertEquals("(排球) OR tag:(排球)", query)
            }
        }
    }

    @Test
    fun `book list request uses current endpoint and structured conditions`() {
        val api = apiClient(OkHttpClient())

        val request = api.searchListRequest(
            page = 2,
            query = "Book title",
            type = KomgaApiClient.SearchType.BOOKS,
            defaultLibraries = setOf("default-library"),
            selectedLibraries = setOf("selected-library"),
            sortIndex = 1,
            sortAscending = false,
            readStatuses = setOf("UNREAD"),
            tags = setOf("tag"),
            authors = listOf("Author" to "writer"),
            oneshot = true,
            cachePolicy = KomgaCachePolicy.Default,
        )

        assertEquals("POST", request.method)
        assertEquals("/api/v1/books/list", request.url.encodedPath)
        assertEquals("1", request.url.queryParameter("page"))
        assertEquals("25", request.url.queryParameter("size"))
        assertEquals("metadata.title,desc", request.url.queryParameter("sort"))

        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("Book title", json.getValue("fullTextSearch").jsonPrimitive.content)
        val conditions = json.getValue("condition").jsonObject.getValue("allOf").jsonArray
        assertTrue(conditions.any { "deleted" in it.jsonObject })
        assertTrue(conditions.hasAnyOfField("libraryId"))
        assertTrue(conditions.hasAnyOfField("readStatus"))
        assertTrue(conditions.hasAnyOfField("tag"))
        assertTrue(conditions.hasAnyOfField("author"))
        assertTrue(conditions.any { "oneShot" in it.jsonObject })
    }

    @Test
    fun `404 response falls back once and caches legacy capability`() = runTest {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                methods += chain.request().method
                response(chain.request(), if (chain.request().method == "POST") 404 else 200)
            }
            .build()
        val api = apiClient(client)
        val modern = request("POST")
        val legacy = request("GET")

        api.executeSearch(KomgaApiClient.SearchType.BOOKS, modern, legacy, true).close()
        api.executeSearch(KomgaApiClient.SearchType.BOOKS, modern, legacy, true).close()

        assertEquals(listOf("POST", "GET", "GET"), methods)
    }

    @Test
    fun `server errors do not fall back`() {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                methods += chain.request().method
                response(chain.request(), 500)
            }
            .build()
        val api = apiClient(client)

        assertThrows(HttpException::class.java) {
            runTest {
                api.executeSearch(
                    KomgaApiClient.SearchType.SERIES,
                    request("POST"),
                    request("GET"),
                    true,
                )
            }
        }
        assertEquals(listOf("POST"), methods)
    }

    @Test
    fun `legacy book search rejects unsupported structured filters`() {
        val methods = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                methods += chain.request().method
                response(chain.request(), 404)
            }
            .build()
        val api = apiClient(client)

        assertThrows(IllegalStateException::class.java) {
            runTest {
                api.executeSearch(
                    KomgaApiClient.SearchType.BOOKS,
                    request("POST"),
                    request("GET"),
                    false,
                )
            }
        }
        assertEquals(listOf("POST"), methods)
    }

    @Test
    fun `time requests have stable secondary identity for both APIs and resource types`() {
        val api = apiClient(OkHttpClient())
        for (type in listOf(KomgaApiClient.SearchType.BOOKS, KomgaApiClient.SearchType.SERIES)) {
            for ((index, field) in listOf(2 to "createdDate", 3 to "lastModifiedDate")) {
                for (ascending in listOf(true, false)) {
                    val expected = listOf("$field,${if (ascending) "asc" else "desc"}", "id,asc")
                    val modern = api.searchListRequest(
                        1,
                        "query",
                        type,
                        emptySet(),
                        sortIndex = index,
                        sortAscending = ascending,
                    )
                    val legacy = api.searchRequest(
                        1,
                        "query",
                        type,
                        emptySet(),
                        sortIndex = index,
                        sortAscending = ascending,
                    )
                    assertEquals(expected, modern.url.queryParameterValues("sort"))
                    assertEquals(expected, legacy.url.queryParameterValues("sort"))
                }
            }
        }
    }

    @Test
    fun `sorted sessions reuse successful empty response cache without a request`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { error("Warm cache must not access network") }.build()
        var reads = 0
        val api =
            KomgaApiClient("https://komga.test", Headers.Builder().build(), client, Json, KomgaSearchCapabilities()) {
                reads++
                response(
                    it,
                    200,
                ).newBuilder().body(
                    """{"content":[],"last":true}""".toResponseBody("application/json".toMediaType()),
                ).build()
            }
        val repository = koharia.komga.domain.repository.KomgaRepository("https://komga.test", api)
        val filters = eu.kanade.tachiyomi.source.model.FilterList(
            koharia.source.komga.SeriesSort(eu.kanade.tachiyomi.source.model.Filter.Sort.Selection(2, false)),
        )
        repeat(2) {
            val session = repository.sortedSearchSession("query", filters, emptySet(), KomgaCachePolicy.Default) {}
            assertTrue(session.load(1).mangas.isEmpty())
            assertEquals(false, session.load(1).hasNextPage)
        }
        assertEquals(4, reads)
    }

    private fun apiClient(client: OkHttpClient): KomgaApiClient = KomgaApiClient(
        baseUrl = "https://komga.test",
        headers = Headers.Builder().build(),
        client = client,
        json = Json,
        searchCapabilities = KomgaSearchCapabilities(),
    )

    private fun request(method: String): Request {
        val builder = Request.Builder().url("https://komga.test/search")
        return if (method == "POST") {
            builder.post("{}".toRequestBody("application/json".toMediaType())).build()
        } else {
            builder.get().build()
        }
    }

    private fun response(request: Request, code: Int): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(code.toString())
        .body("{}".toResponseBody("application/json".toMediaType()))
        .build()

    private fun kotlinx.serialization.json.JsonArray.hasAnyOfField(field: String): Boolean {
        return any { condition ->
            condition.jsonObject["anyOf"]
                ?.jsonArray
                ?.any { field in it.jsonObject } == true
        }
    }
}
