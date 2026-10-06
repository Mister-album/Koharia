package koharia.suwayomi

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Optional real-server tests. Set SUWAYOMI_TEST_URL and, when needed, MODE/USERNAME/PASSWORD in the environment. */
class SuwayomiServerIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun api(internalAddress: String = ""): SuwayomiApi {
        val address = System.getenv("SUWAYOMI_TEST_URL")
        assumeTrue(!address.isNullOrBlank(), "No isolated test server configured")
        val mode = SuwayomiAuthMode.valueOf(System.getenv("SUWAYOMI_TEST_MODE") ?: "NONE")
        return SuwayomiApi(
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build(),
            json,
            address,
            mode,
            System.getenv("SUWAYOMI_TEST_USERNAME").orEmpty(),
            System.getenv("SUWAYOMI_TEST_PASSWORD").orEmpty(),
            internalAddress,
        )
    }

    @Test
    fun `real server validates and paginates library without mutations`() = runTest {
        api().use { api ->
            api.validate()
            var cursor: String? = null
            val cursors = hashSetOf<String>()
            val ids = hashSetOf<Int>()
            var first: SuwayomiManga? = null
            do {
                val page = api.libraryPage(cursor)
                if (first == null) first = page.nodes.firstOrNull()
                page.nodes.forEach {
                    assertTrue(ids.add(it.id))
                    assertTrue(it.inLibrary)
                }
                cursor = if (page.pageInfo.hasNextPage) page.pageInfo.endCursor else null
                if (page.pageInfo.hasNextPage) assertTrue(cursor != null && cursors.add(cursor))
            } while (cursor != null)
            first?.let { entry ->
                assertEquals(entry.id, api.manga(entry.id).id)
                api.chapters(entry.id).forEach { assertEquals(entry.id, it.mangaId) }
                entry.thumbnailUrl?.let { assertTrue(api.resourceUrl(it).startsWith(api.base.toString())) }
            }
        }
    }

    @Test
    fun `isolated server verifies dual-address metadata identity and cleanup`() = runTest {
        val address = System.getenv("SUWAYOMI_TEST_URL")
        assumeTrue(address?.startsWith("http://127.0.0.1:14567") == true)
        api("http://localhost:14567").use { client ->
            check(client.base.host == "127.0.0.1" && client.base.port == 14567)
            client.verifyInternal()
            val request = okhttp3.Request.Builder().url(client.base.resolve("api/graphql")!!)
                .post(
                    """{"query":"query{metas{nodes{key}}}"}"""
                        .toRequestBody("application/json".toMediaType()),
                ).build()
            client.client.newCall(request).execute().use {
                assertTrue(it.isSuccessful)
                assertTrue(!it.body.string().contains("koharia.connection.verify."))
            }
        }
    }

    @Test
    fun `isolated fixture pages and progress round trip preserve zero based indices`() = runTest {
        val id = System.getenv("SUWAYOMI_TEST_CHAPTER_ID")?.toIntOrNull()
        assumeTrue(id != null, "No disposable test chapter configured")
        api().use { api ->
            check(api.base.host == "127.0.0.1" && api.base.port == 14567) {
                "Only the disposable localhost instance may be mutated"
            }
            val pages = api.pages(checkNotNull(id))
            assertTrue(pages.pages.size >= 3)
            val updated = api.updateChapter(id, 1, false)
            assertEquals(1, updated.lastPageRead)
            assertEquals(1, api.chapter(id).lastPageRead)
            val completed = api.updateChapter(id, pages.pages.lastIndex, true)
            assertTrue(completed.isRead)
            for (path in pages.pages) {
                api.client.newCall(okhttp3.Request.Builder().url(api.resourceUrl(path)).build()).execute().use {
                    assertTrue(it.isSuccessful)
                    assertTrue(it.body.bytes().isNotEmpty())
                }
            }
            api.updateChapter(id, 0, false)
        }
    }
}
