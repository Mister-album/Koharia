package eu.kanade.tachiyomi.data.track.komga

import io.mockk.every
import io.mockk.mockk
import koharia.source.komga.KomgaProgressAccount
import koharia.source.komga.KomgaSource
import koharia.source.komga.isKomgaProgressSync
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.service.SourceManager

class KomgaReportedIssuesTest {
    @Test
    fun `captured page callbacks use the journal and stale pushes cannot create reading events`() = runBlocking {
        val source = mockk<KomgaSource>()
        every { source.id } returns 1L
        val account = KomgaProgressAccount(1L, "https://komga.test", "", "Credentials", "fixture", "fixture", "")
        every { source.progressAccount() } returns account
        val manager = mockk<SourceManager> { every { get(1L) } returns source }
        val intents = mockk<koharia.domain.komga.KomgaReadStatusRepository>()
        io.mockk.coEvery { intents.get(any()) } returns null
        val journal = mockk<koharia.komga.progress.KomgaPageJournal>(relaxed = true)
        val url = "https://komga.test/api/v1/books/book"
        val adapter = koharia.komga.progress.captureKomgaPageProgressSession(
            source,
            url,
            manager,
            mockk(),
            koharia.komga.testProgressOrdering(intents),
            journal,
        ).adapter
        adapter.recordLocalPageProgress(url, 0, 10, 300L, initialPage = false)
        adapter.recordLocalPageProgress(url, 3, 10, 400L, initialPage = false)
        adapter.pushPageProgress(url, 9, 10)
        io.mockk.coVerify(exactly = 1) { journal.record(any(), null, 0, 10, 300L, false, any(), any()) }
        io.mockk.coVerify(exactly = 1) { journal.record(any(), null, 3, 10, 400L, false, any(), any()) }
        io.mockk.coVerify(exactly = 2) { journal.record(any(), any(), any(), any(), any(), any(), any(), any()) }
        io.mockk.coVerify(exactly = 1) {
            journal.wake(koharia.domain.komga.KomgaReadStatusKey(1L, account.key, "book"))
        }
    }

    @Test
    fun `shelf completed progress requests the entire catalogue`() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"content\":[]}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val source = source(1, "one", client)
        val api = api(client, listOf(source))
        api.getInProgressBookProgress(1, includeCompleted = true)
        api.getInProgressBookProgress(1, includeCompleted = true)
        assertEquals(1, requests.size)
        assertTrue(requests.single().isKomgaProgressSync)
        assertEquals("true", requests.single().url.queryParameter("unpaged"))
        assertNull(requests.single().url.queryParameter("read_status"))
        api.getInProgressBookProgress(1, includeCompleted = true, forceRefresh = true)
        assertEquals(2, requests.size)
    }

    @Test
    fun `progress write on shared server URL uses the requested connection credentials`() = runBlocking {
        val accounts = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            accounts += chain.request().header("Authorization")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(204).message("OK")
                .body("".toResponseBody()).build()
        }.build()
        val api = api(client, listOf(source(1, "account-a", client), source(2, "account-b", client)))
        api.updateBookProgress(2, "https://komga.test/api/v1/books/book", 3, false)
        assertEquals(listOf(Credentials.basic("account-b", "fixture")), accounts)
    }

    @Test
    fun `retry refuses a changed remote position or timestamp`() {
        val baseline = KomgaApi.BookProgressSnapshot(
            url = "https://komga.test/api/v1/books/book", seriesUrl = null, readProgress = null,
            pageIndex = 2, totalPages = 10, completed = false, readDate = "2026-09-12T10:00:00Z",
            isEpub = false, isDivinaCompatibleEpub = false, fileHash = null,
            fileLastModified = null, sizeBytes = 0, fileName = "book.cbz",
        )
        org.junit.jupiter.api.Assertions.assertTrue(canRetryKomgaPageProgress(baseline, 2, false, baseline.readDate))
        org.junit.jupiter.api.Assertions.assertFalse(
            canRetryKomgaPageProgress(baseline.copy(pageIndex = 5), 2, false, baseline.readDate),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            canRetryKomgaPageProgress(baseline.copy(readDate = "2026-09-12T11:00:00Z"), 2, false, baseline.readDate),
        )
        org.junit.jupiter.api.Assertions.assertFalse(
            canRetryKomgaPageProgress(baseline.copy(completed = true), 2, false, baseline.readDate),
        )
    }

    @Test
    fun `pending local progress uploads only when it is not older than remote`() {
        assertTrue(
            shouldUploadPendingKomgaPageProgress(
                remotePage = 1,
                remoteCompleted = false,
                targetPage = 4,
                targetCompleted = false,
                remoteReadAt = 100L,
                localReadAt = 200L,
            ),
        )
        assertFalse(
            shouldUploadPendingKomgaPageProgress(
                remotePage = 5,
                remoteCompleted = false,
                targetPage = 4,
                targetCompleted = false,
                remoteReadAt = 300L,
                localReadAt = 200L,
            ),
        )
        assertFalse(
            shouldUploadPendingKomgaPageProgress(
                remotePage = 4,
                remoteCompleted = false,
                targetPage = 4,
                targetCompleted = false,
                remoteReadAt = 100L,
                localReadAt = 200L,
            ),
        )
    }

    private fun source(sourceId: Long, account: String, http: OkHttpClient): KomgaSource = mockk {
        every { id } returns sourceId
        every { baseUrl } returns "https://komga.test"
        every { client } returns http
        val binding = KomgaProgressAccount(sourceId, "https://komga.test", "", "Credentials", account, "fixture", "")
        every { currentHeaders() } returns binding.headers
        every { progressAccount() } returns binding
    }

    private fun api(client: OkHttpClient, sources: List<KomgaSource>): KomgaApi {
        val manager = mockk<SourceManager>()
        every { manager.getOnlineSources() } returns sources
        every { manager.get(any<Long>()) } answers { sources.firstOrNull { it.id == firstArg<Long>() } }
        return KomgaApi(0, client).also { api ->
            KomgaApi::class.java.getDeclaredField("sourceManager${'$'}delegate").apply { isAccessible = true }
                .set(api, lazyOf(manager))
            KomgaApi::class.java.getDeclaredField("json${'$'}delegate").apply { isAccessible = true }
                .set(api, lazyOf(Json { ignoreUnknownKeys = true }))
        }
    }
}
