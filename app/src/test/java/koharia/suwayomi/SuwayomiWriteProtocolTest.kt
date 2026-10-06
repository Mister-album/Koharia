package koharia.suwayomi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Protocol shapes for the source-metadata, filter and migration writes. The server validates
 * GraphQL field names and input fields strictly, so a wrong name is a hard failure rather than a
 * silent no-op; these tests keep the requests honest without a live server.
 */
class SuwayomiWriteProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val servers = mutableListOf<HttpServer>()
    private val clients = mutableListOf<SuwayomiApi>()

    private class Fixture {
        val requests = mutableListOf<Pair<String, JsonObject>>()
        var response: (String, JsonObject) -> String = { _, _ -> "{}" }
    }

    private fun start(fixture: Fixture): SuwayomiApi {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            val body = json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val query = body["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val variables = body["variables"]?.jsonObject ?: JsonObject(emptyMap())
            fixture.requests += query to variables
            val text = fixture.response(query, variables)
            val bytes = text.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        servers += server
        return SuwayomiApi(
            OkHttpClient(),
            json,
            "http://127.0.0.1:${server.address.port}",
            SuwayomiAuthMode.NONE,
            "",
            "",
        ).also { clients += it }
    }

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(0) }
    }

    @Test
    fun `pinning writes the shared WebUI meta key`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ -> """{"data":{"setSourceMeta":{"meta":{"key":"webUI_isPinned","value":"true"}}}}""" }
        start(fixture).use { api ->
            val meta = api.setSourceMeta(42L, SuwayomiSourceInfo.PINNED_META_KEY, "true")

            assertEquals("webUI_isPinned", meta.key)
            assertEquals("true", meta.value)
        }
        val (query, variables) = fixture.requests.single()
        assertTrue(query.contains("setSourceMeta(input:{meta:{sourceId:\$id,key:\$key,value:\$value}})"))
        assertEquals("42", variables["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("webUI_isPinned", variables["key"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `source filters are read through aliased union fragments`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ ->
            """
            {"data":{"source":{"filters":[
              {"__typename":"HeaderFilter","filterName":"Sort by"},
              {"__typename":"SelectFilter","filterName":"Genre","values":["Any","Action"],"selectDefault":0},
              {"__typename":"TextFilter","filterName":"Author","textDefault":""},
              {"__typename":"CheckBoxFilter","filterName":"Completed","checkDefault":false},
              {"__typename":"TriStateFilter","filterName":"Lewd","triDefault":"IGNORE"},
              {"__typename":"SortFilter","filterName":"Order","values":["Title"],"sortDefault":{"index":0,"ascending":true}},
              {"__typename":"GroupFilter","filterName":"More","filters":[
                 {"__typename":"SelectFilter","filterName":"Status","values":["Any","Ongoing"],"selectDefault":1}
              ]}
            ]}}}
            """.trimIndent()
        }
        val filters = start(fixture).use { api -> api.sourceFilters(42L) }

        assertEquals(7, filters.size)
        assertEquals(SuwayomiHeaderFilter("Sort by"), filters[0])
        assertEquals(SuwayomiSelectFilter("Genre", listOf("Any", "Action"), 0), filters[1])
        assertEquals(SuwayomiTextFilter("Author", ""), filters[2])
        assertEquals(SuwayomiCheckBoxFilter("Completed", false), filters[3])
        assertEquals(SuwayomiTriStateFilter("Lewd", "IGNORE"), filters[4])
        assertEquals(SuwayomiSortFilter("Order", listOf("Title"), SuwayomiSortSelection(0, true)), filters[5])
        assertEquals(
            SuwayomiGroupFilter("More", listOf(SuwayomiSelectFilter("Status", listOf("Any", "Ongoing"), 1))),
            filters[6],
        )
        val (query, _) = fixture.requests.single()
        assertTrue(query.contains("... on SelectFilter{filterName:name values selectDefault:default}"))
        assertTrue(query.contains("... on TextFilter{filterName:name textDefault:default}"))
    }

    @Test
    fun `applied filters are sent as FilterChangeInput positions`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ -> """{"data":{"fetchSourceManga":{"hasNextPage":false,"mangas":[]}}}""" }
        start(fixture).use { api ->
            api.discoverSourceManga(
                sourceId = 42L,
                page = 2,
                query = null,
                type = SuwayomiSourceMangaType.LATEST,
                filters = listOf(
                    SuwayomiFilterChange(position = 1, selectState = 2),
                    SuwayomiFilterChange(
                        position = 5,
                        groupChange = SuwayomiFilterChange(position = 0, textState = "x"),
                    ),
                ),
            )
        }
        val (query, variables) = fixture.requests.single()
        assertTrue(query.contains("filters:\$filters"))
        assertTrue(query.contains("type:\$type"))
        assertEquals("LATEST", variables["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2", variables["page"]?.jsonPrimitive?.contentOrNull)
        val filters = variables["filters"]!!.jsonArray
        assertEquals(2, filters.size)
        assertEquals(1, filters[0].jsonObject["position"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(2, filters[0].jsonObject["selectState"]?.jsonPrimitive?.contentOrNull?.toInt())
        val nested = filters[1].jsonObject["groupChange"]!!.jsonObject
        assertEquals(0, nested["position"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("x", nested["textState"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `no filters sends a null filter list so the source keeps its defaults`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ -> """{"data":{"fetchSourceManga":{"hasNextPage":false,"mangas":[]}}}""" }
        start(fixture).use { api ->
            api.discoverSourceManga(42L, 1, "", SuwayomiSourceMangaType.POPULAR, emptyList())
        }
        val (_, variables) = fixture.requests.single()
        assertEquals("null", variables["filters"].toString())
    }

    @Test
    fun `chapter migration writes only readable patch fields`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ ->
            """{"data":{"updateChapters":{"chapters":[{"id":7,"mangaId":3,"isRead":true,"lastPageRead":4}]}}}"""
        }
        start(fixture).use { api ->
            val updated = api.updateChapters(ids = listOf(7), read = true, lastPageRead = 4)

            assertEquals(listOf(7), updated.map { it.id })
            assertTrue(updated.single().isRead)
            assertEquals(4, updated.single().lastPageRead)
        }
        val (query, variables) = fixture.requests.single()
        assertTrue(query.contains("updateChapters(input:{ids:\$ids,patch:\$patch})"))
        assertTrue(query.contains("updateChapters(input:{ids:\$ids,patch:\$patch}){chapters{"))
        assertEquals(listOf("7"), variables["ids"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull })
        val patch = variables["patch"]!!.jsonObject
        assertEquals(setOf("isRead", "lastPageRead"), patch.keys)
    }

    @Test
    fun `library membership and categories use their documented inputs`() = runTest {
        val fixture = Fixture()
        fixture.response = { query, _ ->
            if (query.contains("updateMangaCategories")) {
                """{"data":{"updateMangaCategories":{"manga":{"id":9}}}}"""
            } else {
                """{"data":{"updateManga":{"manga":{"id":9,"inLibrary":true}}}}"""
            }
        }
        start(fixture).use { api ->
            api.updateMangaCategories(9, add = setOf(3, 5), remove = emptySet())
            api.setLibraryMembership(9, true)
        }
        val categoryQuery = fixture.requests.first { it.first.contains("updateMangaCategories") }
        assertTrue(categoryQuery.first.contains("addToCategories:\$add"))
        assertEquals(
            listOf(3, 5),
            categoryQuery.second["add"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull?.toInt() },
        )
        assertTrue(categoryQuery.second["remove"]!!.jsonArray.isEmpty())
        val libraryQuery = fixture.requests.first { it.first.contains("updateManga(") }
        assertTrue(libraryQuery.first.contains("patch:{inLibrary:\$inLibrary}"))
        assertEquals("true", libraryQuery.second["inLibrary"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `manga meta round trip carries reader settings`() = runTest {
        val fixture = Fixture()
        fixture.response = { query, _ ->
            if (query.contains("mutation")) {
                """{"data":{"setMangaMeta":{"meta":{"key":"flutter_rating","value":"9"}}}}"""
            } else {
                """{"data":{"manga":{"id":9,"title":"Fixture","meta":[{"key":"flutter_rating","value":"9"}]}}}"""
            }
        }
        start(fixture).use { api ->
            assertEquals(SuwayomiMangaMeta("flutter_rating", "9"), api.setMangaMeta(9, "flutter_rating", "9"))
            assertEquals(listOf(SuwayomiMangaMeta("flutter_rating", "9")), api.mangaMeta(9))
        }
        val (readQuery, readVariables) = fixture.requests[1]
        // Per-series meta is read from the manga node; the top-level metas query is global only.
        assertTrue(readQuery.contains("manga(id:\$id)"))
        assertTrue(readQuery.contains("meta{key value}"))
        assertEquals(9, readVariables["id"]?.jsonPrimitive?.contentOrNull?.toInt())
    }

    @Test
    fun `source listing decodes the migration fields it groups by`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ ->
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put(
                            "source",
                            buildJsonObject {
                                put("id", "555")
                                put("name", "Fixture")
                                put("lang", "en")
                                put("iconUrl", "/api/v1/source/555/icon")
                                put("contentWarning", "NSFW")
                                put("supportsLatest", true)
                                put(
                                    "extension",
                                    buildJsonObject {
                                        put("isInstalled", true)
                                        put("isObsolete", true)
                                    },
                                )
                                put(
                                    "meta",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("key", SuwayomiSourceInfo.PINNED_META_KEY)
                                                put("value", "true")
                                            },
                                        ),
                                    ),
                                )
                            },
                        )
                    },
                )
            }.toString()
        }
        val info = start(fixture).use { api -> api.source(555L) }

        assertEquals(555L, info.id)
        assertTrue(info.isPinned)
        assertTrue(info.isObsolete)
        assertEquals("NSFW", info.contentWarning)
    }

    @Test
    fun `source preferences are read with their variant fields and positions`() = runTest {
        val fixture = Fixture()
        fixture.response = { _, _ ->
            """
            {"data":{"source":{"preferences":[
              {"__typename":"SwitchPreference","prefKey":"k1","prefTitle":"Switch","prefSummary":"s",
               "prefVisible":true,"prefEnabled":true,"switchState":false,"switchDefault":true},
              {"__typename":"ListPreference","prefKey":"k2","prefTitle":"List","prefVisible":true,
               "prefEnabled":false,"listState":"b","listDefault":"a","listEntries":["A","B"],
               "listEntryValues":["a","b"]},
              {"__typename":"MultiSelectListPreference","prefKey":"k3","prefTitle":"Multi",
               "prefVisible":true,"prefEnabled":true,"multiState":["x"],"multiDefault":[],
               "multiEntries":["X","Y"],"multiEntryValues":["x","y"]},
              {"__typename":"EditTextPreference","prefKey":"k4","prefTitle":"Text","prefVisible":false,
               "prefEnabled":true,"editState":"v","editDefault":"d"},
              {"__typename":"CheckBoxPreference","prefKey":"k5","prefTitle":"Box","prefVisible":true,
               "prefEnabled":true,"checkState":true,"checkDefault":false}
            ]}}}
            """.trimIndent()
        }

        val preferences = start(fixture).use { api -> api.sourcePreferences(42L) }

        // The change protocol addresses each entry by its index in this list.
        assertEquals(listOf(0, 1, 2, 3, 4), preferences.map { it.position })
        assertEquals(
            SuwayomiSwitchPreference(
                position = 0,
                key = "k1",
                title = "Switch",
                summary = "s",
                currentValue = false,
                default = true,
            ),
            preferences[0],
        )
        val list = preferences[1] as SuwayomiListPreference
        assertEquals(1, list.selectedIndex)
        assertEquals(false, list.enabled)
        assertEquals(setOf("x"), (preferences[2] as SuwayomiMultiSelectPreference).selected)
        assertEquals("v", (preferences[3] as SuwayomiEditTextPreference).currentValue)
        assertEquals(false, preferences[3].visible)
        assertEquals(true, (preferences[4] as SuwayomiCheckBoxPreference).currentValue)

        // Every shared field must be aliased inside its own fragment; the union rejects bare names.
        val (query, _) = fixture.requests.single()
        assertTrue(query.contains("... on ListPreference{prefKey:key"))
        assertTrue(query.contains("listEntryValues:entryValues"))
        assertTrue(!query.contains("preferences{__typename key"))
    }

    @Test
    fun `a source preference write sends the variant field at its own position`() = runTest {
        val fixture = Fixture()
        fixture.response = { query, _ ->
            if (query.contains("mutation")) {
                """{"data":{"updateSourcePreference":{"preferences":[
                   {"__typename":"ListPreference","prefKey":"k2","prefTitle":"List","prefVisible":true,
                    "prefEnabled":true,"listState":"b","listDefault":"a","listEntries":["A","B"],
                    "listEntryValues":["a","b"]}]}}}"""
            } else {
                """{"data":{"source":{"preferences":[]}}}"""
            }
        }

        val preference = SuwayomiListPreference(
            position = 3,
            key = "k2",
            title = "List",
            entryValues = listOf("a", "b"),
        )
        val updated = start(fixture).use { api ->
            // The server resolves a change against the screen a read built, so a first write reads.
            api.updateSourcePreference(42L, preference.position, SuwayomiPreferenceChange.of(preference, "b"))
        }

        assertEquals("b", (updated.single() as SuwayomiListPreference).currentValue)
        val queries = fixture.requests.map { it.first }
        assertEquals(2, queries.size)
        assertTrue(queries[0].contains("query"))
        assertTrue(queries[1].contains("updateSourcePreference"))
        val (query, variables) = fixture.requests[1]
        assertTrue(query.contains("updateSourcePreference(input:{source:\$source,change:\$change})"))
        assertEquals("42", variables["source"]?.jsonPrimitive?.contentOrNull)
        val change = variables["change"]?.jsonObject ?: error("missing change")
        assertEquals(3, change["position"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("b", change["listState"]?.jsonPrimitive?.contentOrNull)
        // Only the field for this variant may be present.
        assertEquals(setOf("position", "listState"), change.keys)
    }

    @Test
    fun `a second write for the same source reuses the read it already did`() = runTest {
        val fixture = Fixture()
        fixture.response = { query, _ ->
            if (query.contains("mutation")) {
                """{"data":{"updateSourcePreference":{"preferences":[
                   {"__typename":"SwitchPreference","prefKey":"k","prefVisible":true,"prefEnabled":true,
                    "switchState":true,"switchDefault":false}]}}}"""
            } else {
                """{"data":{"source":{"preferences":[]}}}"""
            }
        }

        start(fixture).use { api ->
            val preference = SuwayomiSwitchPreference(position = 0, key = "k")
            api.updateSourcePreference(7L, 0, SuwayomiPreferenceChange.of(preference, true))
            api.updateSourcePreference(7L, 0, SuwayomiPreferenceChange.of(preference, false))
        }

        // One read, then only mutations.
        assertEquals(3, fixture.requests.size)
        assertTrue(fixture.requests[0].first.contains("query"))
        assertTrue(fixture.requests[1].first.contains("mutation"))
        assertTrue(fixture.requests[2].first.contains("mutation"))
    }

    @Test
    fun `each preference variant maps to a distinct change field`() {
        assertEquals(
            SuwayomiPreferenceChange(switchState = true),
            SuwayomiPreferenceChange.of(SuwayomiSwitchPreference(enabled = true), true),
        )
        assertEquals(
            SuwayomiPreferenceChange(checkBoxState = true),
            SuwayomiPreferenceChange.of(SuwayomiCheckBoxPreference(), true),
        )
        assertEquals(
            SuwayomiPreferenceChange(editTextState = "x"),
            SuwayomiPreferenceChange.of(SuwayomiEditTextPreference(), "x"),
        )
        assertEquals(
            SuwayomiPreferenceChange(listState = "x"),
            SuwayomiPreferenceChange.of(SuwayomiListPreference(), "x"),
        )
        assertEquals(
            SuwayomiPreferenceChange(multiSelectState = listOf("x", "y")),
            SuwayomiPreferenceChange.of(SuwayomiMultiSelectPreference(), listOf("x", "y")),
        )
    }
}
