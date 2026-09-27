package koharia.kavita

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class KavitaOrganizationTest {
    private var roles = """["Login","Download"]"""
    private var modernStatus = 200
    private var owner = "reader"
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val queries = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val request = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += path to request
            queries += exchange.requestURI.rawQuery.orEmpty()
            val auth = path.endsWith("authenticate") || path.endsWith("refresh-account")
            val status = if (path.endsWith("Rating/series")) modernStatus else 200
            val body = when {
                auth -> """{"id":1,"username":"reader","token":"fixture","roles":$roles,"kavitaVersion":"0.9.1.4"}"""
                path.endsWith("ReadingList/items") -> """[{"id":8,"order":2},{"id":6,"order":0},{"id":7,"order":1}]"""
                path.endsWith("ReadingList") ->
                    """{"id":5,"title":"old","ownerUserName":"$owner",
                    "promoted":true,"coverImageLocked":true,"startingYear":2001,"tags":[{"id":9,"title":"tag"}]}"""
                path.endsWith("/Filter") -> """[{"id":7,"name":"old"},{"id":8,"name":"taken"}]"""
                else -> ""
            }.toByteArray()
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }
    private val api =
        KavitaApiClient(okhttp3.OkHttpClient(), "http://127.0.0.1:${server.address.port}", "fixture", "test")
    private val catalog = KavitaCatalog(1, "account", MemoryKavitaRepository(), api) {}
    private val organization = KavitaOrganization(1, catalog) {}

    @AfterEach fun close() {
        api.close()
        server.stop(0)
    }

    @Test fun editingListPreservesUneditedFieldsAndConvertsTags() = runTest {
        organization.editList(5, "new", "description")
        val body = Json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals("new", body.textValue("title"))
        assertEquals("true", body["promoted"].toString())
        assertEquals("true", body["coverImageLocked"].toString())
        assertEquals("2001", body["startingYear"].toString())
        assertEquals("""["tag"]""", body["tags"].toString())
        assertFalse(body.containsKey("ownerUserName"))
    }

    @Test fun readOnlyAndDifferentOwnerCannotMutateLists() = runTest {
        roles = """["Login","Read Only"]"""
        assertTrue(runCatching { organization.addListMember(5, 3) }.exceptionOrNull() is KavitaException)
        assertFalse(requests.any { it.first.endsWith("ReadingList/update-by-series") })
        roles = """["Login"]"""
        owner = "someone-else"
        assertTrue(runCatching { organization.deleteList(5) }.exceptionOrNull() is KavitaException)
        assertTrue(requests.none { it.second == "{}" && it.first.endsWith("ReadingList") })
    }

    @Test fun listMoveUsesFreshServerOrderAndDoesNotMoveBeyondBounds() = runTest {
        organization.moveListMember(5, 7, -1)
        val body = Json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals(7L, body.longValue("readingListItemId"))
        assertEquals(1L, body.longValue("fromPosition"))
        assertEquals(0L, body.longValue("toPosition"))
        val writes = requests.count { it.first.endsWith("update-position") }
        organization.moveListMember(5, 8, 1)
        assertEquals(writes, requests.count { it.first.endsWith("update-position") })
    }

    @Test fun ratingFallsBackOnlyWhenEndpointIsMissing() = runTest {
        modernStatus = 403
        assertTrue(runCatching { organization.rate(3, 4f) }.exceptionOrNull() is KavitaException)
        assertFalse(requests.any { it.first.endsWith("Series/update-rating") })
        modernStatus = 404
        organization.rate(3, 4f)
        assertTrue(requests.any { it.first.endsWith("Series/update-rating") })
    }

    @Test fun smartFilterRenameEncodesNameAndRejectsMissingOrConflictingIdentity() = runTest {
        assertTrue(runCatching { organization.saveFilter(7, "stale name", KavitaFilter()) }.isFailure)
        assertTrue(runCatching { organization.renameFilter(9, "New") }.isFailure)
        assertTrue(runCatching { organization.renameFilter(7, "TAKEN") }.isFailure)
        assertFalse(requests.any { it.first.endsWith("Filter/rename") })
        organization.renameFilter(7, "中文 & filter")
        assertTrue(requests.last().first.endsWith("Filter/rename"))
        val parsed = okhttp3.HttpUrl.Builder().scheme("http").host("localhost").encodedQuery(queries.last()).build()
        assertEquals("7", parsed.queryParameter("filterId"))
        assertEquals("中文 & filter", parsed.queryParameter("name"))
        assertFalse(requests.any { it.first.endsWith("Filter/update/series") })
    }
}
