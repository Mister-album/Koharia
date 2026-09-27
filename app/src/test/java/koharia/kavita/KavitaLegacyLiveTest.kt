package koharia.kavita

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

class KavitaLegacyLiveTest {
    @Test fun official08SupportsLegacyIdentityPagesPublicationsProgressAndHistory() = runBlocking {
        val bridge = System.getenv("KAVITA_LEGACY_BRIDGE")
        assumeTrue(!bridge.isNullOrBlank())
        val credentials = OkHttpClient().newCall(Request.Builder().url(requireNotNull(bridge)).build()).execute().use {
            Json.parseToJsonElement(it.body.string()).jsonObject
        }
        val address = credentials.getValue("server").jsonPrimitive.content
        require(address.toHttpUrl().host == "127.0.0.1")
        KavitaApiClient(
            OkHttpClient(),
            address,
            credentials.getValue("key").jsonPrimitive.content,
            "legacy-live",
        ).use { api ->
            val user = api.getAccount()
            assertTrue(api.capabilities(user).version in KavitaVersion(0, 8, 0)..KavitaVersion(0, 8, 8))
            assertTrue(user.id > 0)
            assertFalse(api.capabilities(user).authKeys)
            assertEquals(api.capabilities(user).version >= KavitaVersion(0, 8, 8), api.capabilities(user).annotations)
            val catalog = KavitaCatalog(1, "legacy", MemoryKavitaRepository(), api) {}
            assertEquals(3, catalog.libraries().size)
            val series = catalog.page(1, KavitaFilter()).items
            assertTrue(series.map { it.format }.containsAll(listOf(1, 3, 4)))
            for (entry in series) {
                val volume = catalog.volumes(entry.id).first { it.chapters.isNotEmpty() }
                val chapter = volume.chapters.first()
                assertTrue(chapter.pages > 0)
                val progress =
                    KavitaProgress(
                        entry.libraryId,
                        entry.id,
                        volume.id,
                        chapter.id,
                        pageNum = 1,
                        lastModifiedUtc = java.time.Instant.now().toString(),
                    )
                api.saveProgress(progress)
                assertEquals(1, api.progress(chapter.id).pageNum)
                if (entry.format == 3) {
                    val target = File(requireNotNull(System.getenv("KAVITA_LEGACY_ARTIFACTS")), "export.epub")
                    try {
                        KavitaOfflineEpub(api, catalog).write(chapter.id, target) {}
                        ZipFile(target).use { zip ->
                            assertTrue(zip.entries().asSequence().any { it.name.endsWith(".xhtml") })
                        }
                    } finally {
                        target.delete()
                    }
                } else {
                    api.client.newCall(Request.Builder().url(api.page(chapter.id, 0)).build()).execute().use {
                        assertEquals(200, it.code)
                        assertTrue(it.body.bytes().size > 100)
                    }
                }
            }
            assertEquals(3, catalog.history(true).size)
            assertEquals(series.size, catalog.page(1, KavitaFilter()).items.size)
            // 0.8.8 rejects every update of its original administrator in ChangeIdentityProvider.
            if (api.capabilities(user).version >= KavitaVersion(0, 8, 8)) {
                println("Reading verified; original-admin role mutation is blocked by this server version.")
                return@use
            }
            api.mutate(
                "Account/update",
                buildJsonObject {
                    put("userId", user.id)
                    put("username", user.username)
                    put("roles", JsonArray(listOf(JsonPrimitive("Login"), JsonPrimitive("Read Only"))))
                    put("libraries", JsonArray(catalog.libraries().map { JsonPrimitive(it.id) }))
                    put(
                        "ageRestriction",
                        buildJsonObject {
                            put("ageRating", 0)
                            put("includeUnknowns", true)
                        },
                    )
                },
            )
            val readOnly = api.capabilities(api.getAccount(true))
            assertFalse(readOnly.downloads)
            assertFalse(readOnly.writable)
            val epub = series.single { it.format == 3 }
            val chapter = catalog.volumes(epub.id).first().chapters.first()
            assertTrue(api.get<KavitaBookInfo>("Book/${chapter.id}/book-info").pages > 0)
            api.client.newCall(api.rawRequest(chapter.id)).execute().use { assertEquals(403, it.code) }
            assertTrue(runCatching { api.saveProgress(KavitaProgress(chapterId = chapter.id)) }.isFailure)
        }
    }
}
