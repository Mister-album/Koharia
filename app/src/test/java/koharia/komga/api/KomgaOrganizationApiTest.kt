package koharia.komga.api

import koharia.source.komga.KomgaCacheNamespace
import koharia.source.komga.KomgaCachePolicy
import koharia.source.komga.isKomgaNetworkRequired
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class KomgaOrganizationApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = mutableListOf<Request>()

    private fun api(body: String = "{}"): KomgaOrganizationApi {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        return KomgaOrganizationApi(
            "https://komga.test",
            Headers.Builder().build(),
            KomgaApiClient("https://komga.test", Headers.Builder().build(), client, json),
            json,
            "account-a",
            { 42L },
        )
    }

    @Test
    fun `payload preserves distinct membership order and omits fields that must not change`() {
        val api = api()
        val collection =
            api.payload(
                KomgaOrganizationKind.COLLECTION,
                " Collection ",
                "ignored",
                true,
                listOf("s2", "s1", "s2"),
            )
        assertEquals(
            listOf("s2", "s1"),
            collection.getValue("seriesIds").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("Collection", collection.getValue("name").jsonPrimitive.content)
        assertFalse("summary" in collection)
        assertFalse("bookIds" in collection)
        val nameOnly = api.payload(KomgaOrganizationKind.READ_LIST, name = "New name")
        assertEquals(setOf("name"), nameOnly.keys)
        assertThrows(IllegalArgumentException::class.java) {
            api.payload(KomgaOrganizationKind.READ_LIST, members = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            api.payload(KomgaOrganizationKind.COLLECTION, name = " ")
        }
    }

    @Test
    fun `queries repeat filters and retain captured account revision`() {
        val request =
            api()
                .request(
                    "collections",
                    KomgaOrganizationQuery(filters = mapOf("library_id" to listOf("a", "b"))),
                )
        assertEquals(listOf("a", "b"), request.url.queryParameterValues("library_id"))
        assertEquals(
            KomgaCacheNamespace("account-a", 42L),
            request.tag(KomgaCacheNamespace::class.java),
        )
        assertEquals(KomgaCachePolicy.Default, request.tag(KomgaCachePolicy::class.java))
        assertFalse(request.isKomgaNetworkRequired)
        assertTrue(api().mutationRequest("readlists/id", "DELETE").isKomgaNetworkRequired)
    }

    @Test
    fun `name directory uses unpaged requests with explicit refresh and captured account scope`() = runTest {
        val api = api("""{"content":[{"id":"c","name":"Collection"}],"totalPages":1}""")
        api.list(KomgaOrganizationKind.COLLECTION, KomgaOrganizationQuery(unpaged = true))
        api.list(KomgaOrganizationKind.READ_LIST, KomgaOrganizationQuery(unpaged = true), refresh = true)
        assertEquals(listOf("/api/v1/collections", "/api/v1/readlists"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.url.queryParameter("unpaged") == "true" })
        assertEquals(
            listOf(KomgaCachePolicy.Default, KomgaCachePolicy.NetworkFirst),
            requests.map { it.tag(KomgaCachePolicy::class.java) },
        )
        assertTrue(requests.all { it.tag(KomgaCacheNamespace::class.java) == KomgaCacheNamespace("account-a", 42L) })
    }

    @Test
    fun `member title search searches full ordered server result then paginates`() = runTest {
        val body =
            """{"content":[
          {"id":"b3","seriesId":"s1","seriesTitle":"Alpha","name":"Third","fileLastModified":"","metadata":{"title":"Three"}},
          {"id":"b2","seriesId":"s2","seriesTitle":"Beta","name":"Second","fileLastModified":"","metadata":{"title":"Two"}},
          {"id":"b1","seriesId":"s1","seriesTitle":"Alpha","name":"First","fileLastModified":"","metadata":{"title":"One"},"readProgress":{"completed":true}}
        ]}"""
        val result =
            api(body).books("list", KomgaOrganizationQuery(search = "alpha", size = 1, page = 1))
        assertEquals(listOf("b1"), result.content.map { it.id })
        assertTrue(result.content.single().readProgress!!.completed)
        assertEquals(2L, result.totalPages)
        assertEquals("true", requests.single().url.queryParameter("unpaged"))
        assertEquals(null, requests.single().url.queryParameter("search"))
        assertEquals(null, requests.single().url.queryParameter("sort"))
    }

    @Test
    fun `server ordering is preserved when manual order is off`() = runTest {
        val result =
            api("""{"id":"r1","name":"List","ordered":false,"bookIds":["b2","b1"]}""")
                .detail(KomgaOrganizationKind.READ_LIST, "r1")
        assertFalse(result.ordered)
        assertEquals(listOf("b2", "b1"), result.bookIds)
    }

    @Test
    fun `poster and export calls always use network and stream unchanged bytes`() = runTest {
        val api = api("ZIP fixture")
        api.selectThumbnail(KomgaOrganizationKind.COLLECTION, "c", "t")
        val output = ByteArrayOutputStream()
        api.export("r", output)
        assertEquals("PUT", requests.first().method)
        assertEquals(
            "/api/v1/collections/c/thumbnails/t/selected",
            requests.first().url.encodedPath,
        )
        assertEquals("/api/v1/readlists/r/file", requests.last().url.encodedPath)
        assertTrue(requests.all { it.isKomgaNetworkRequired })
        assertEquals("ZIP fixture", output.toString())
    }

    @Test
    fun `read list creation and collection changes use the corresponding strict mutation paths`() =
        runTest {
            val api = api("""{"id":"r","name":"Reading","bookIds":["b2","b1"]}""")
            val result =
                api.create(
                    KomgaOrganizationKind.READ_LIST,
                    api.payload(
                        KomgaOrganizationKind.READ_LIST,
                        "Reading",
                        "Summary",
                        true,
                        listOf("b2", "b1"),
                    ),
                )
            api.update(
                KomgaOrganizationKind.COLLECTION,
                "c",
                api.payload(KomgaOrganizationKind.COLLECTION, name = "Renamed"),
            )
            api.delete(KomgaOrganizationKind.COLLECTION, "c")
            assertEquals(listOf("b2", "b1"), result.bookIds)
            assertEquals(listOf("POST", "PATCH", "DELETE"), requests.map { it.method })
            assertEquals(
                listOf("/api/v1/readlists", "/api/v1/collections/c", "/api/v1/collections/c"),
                requests.map { it.url.encodedPath },
            )
            assertTrue(requests.all { it.isKomgaNetworkRequired })
        }

    @Test
    fun `author choices follow every page and retain list and library scope`() = runTest {
        val api = api("""{"content":[{"name":"Writer","role":"writer"}],"totalPages":2}""")
        assertEquals(
            listOf("Writer"),
            api.authorNames(
                "writer",
                mapOf("readlist_id" to listOf("r"), "library_id" to listOf("l")),
            ),
        )
        assertEquals(listOf("0", "1"), requests.map { it.url.queryParameter("page") })
        assertTrue(requests.all { it.url.encodedPath == "/api/v2/authors" })
        assertTrue(
            requests.all {
                it.url.queryParameter("role") == "writer" &&
                    it.url.queryParameter("readlist_id") == "r"
            },
        )
    }

    @Test
    fun `comicrack import preserves ambiguous and unmatched candidates for user selection`() =
        runTest {
            val fixture =
                """{
          "readListMatch":{"name":"Imported","errorCode":"NAME_ALREADY_EXISTS"},
          "requests":[
            {"request":{"series":["Alpha"],"number":"1"},"matches":[
              {"series":{"seriesId":"s","title":"Alpha"},"books":[{"bookId":"b1","number":"1","title":"First"},{"bookId":"b2","number":"1","title":"Other edition"}]}
            ]},
            {"request":{"series":["Missing"],"number":"2"},"matches":[]}
          ]
        }"""
            val result = api(fixture).matchComicRack("fixture.cbl", "<ReadingList/>".toByteArray())
            assertEquals(2, result.requests.first().matches.single().books.size)
            assertTrue(result.requests.last().matches.isEmpty())
            assertEquals("NAME_ALREADY_EXISTS", result.readListMatch.errorCode)
            assertEquals("/api/v1/readlists/match/comicrack", requests.single().url.encodedPath)
            assertTrue(requests.single().isKomgaNetworkRequired)
        }
}
